package gg.sona.recast.mc

import gg.sona.recast.camera.CameraMode
import gg.sona.recast.mc.mixin.*
import gg.sona.recast.net.PacketWriter
import gg.sona.recast.protocol.LocalScreen
import gg.sona.recast.protocol.SlotCodec
import gg.sona.recast.replay.session.ReplaySession
import gg.sona.recast.replay.state.shadow.ShadowWindow
import io.netty.buffer.Unpooled
import net.minecraft.block.entity.SignBlockEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.entity.living.player.ClientPlayerEntity
import net.minecraft.client.gui.screen.ChatScreen
import net.minecraft.client.gui.screen.DeathScreen
import net.minecraft.client.gui.screen.GameMenuScreen
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.screen.inventory.BookEditScreen
import net.minecraft.client.gui.screen.inventory.menu.*
import net.minecraft.client.render.Window
import net.minecraft.client.render.platform.GlStateManager
import net.minecraft.client.render.platform.Lighting
import net.minecraft.client.world.villager.trade.ClientTrader
import net.minecraft.entity.living.LivingEntity
import net.minecraft.entity.living.mob.passive.animal.HorseBaseEntity
import net.minecraft.entity.living.player.PlayerEntity
import net.minecraft.inventory.AnimalInventory
import net.minecraft.inventory.MenuInventory
import net.minecraft.inventory.SimpleInventory
import net.minecraft.inventory.menu.EmptyMenuProvider
import net.minecraft.inventory.menu.TraderMenu
import net.minecraft.item.CreativeModeTab
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtList
import net.minecraft.nbt.NbtString
import net.minecraft.network.PacketByteBuf
import net.minecraft.text.LiteralText
import net.minecraft.text.Text
import net.minecraft.util.math.BlockPos
import net.minecraft.world.WorldSettings
import net.minecraft.world.village.trade.TradeOffers
import org.apache.logging.log4j.LogManager
import org.lwjgl.input.Keyboard
import org.lwjgl.opengl.GL11

class ScreenMirror(
    private val minecraft: Minecraft,
    private val cameraDriver: CameraDriver,
    private val session: () -> ReplaySession?
) {

    private val logger = LogManager.getLogger("Recast")
    private var screen: Screen? = null
    private var key: Key? = null
    private var itemsVersion = -1
    private var syncedText: String? = null
    private var syncedCursor = -1
    private var syncedDetail = -1
    private val itemWriter = PacketWriter(256)

    var renderingModel: Boolean = false
        private set

    private data class Key(
        val kind: Int,
        val windowId: Int,
        val type: String,
        val size: Int,
        val title: String,
        val position: Long,
        val width: Int,
        val height: Int,
        val player: Int
    )

    fun active(): Boolean {
        val settings = cameraDriver.settings
        if (session() == null || !settings.mirrorScreens) return false
        return settings.mode == CameraMode.FIRST_PERSON && settings.targetsRecorder()
    }

    fun chatFocused(): Boolean = active() && session()?.shadow?.localPlayer?.screen?.kind == LocalScreen.CHAT

    fun recordedState(): LocalScreen? = if (active()) session()?.shadow?.localPlayer?.screen else null

    fun tick() {
        val current = screen ?: return
        if (!active()) return
        try {
            when (current) {
                is ChatScreen, is DeathScreen, is SignEditScreen, is BookEditScreen, is VillagerScreen -> current.tick()
                else -> Unit
            }
        } catch (error: Throwable) {
            logger.warn("Mirrored screen tick failed", error)
        }
    }

    fun close() {
        val current = screen
        if (current is CreativeInventoryScreen) {
            val listener = (current as CreativeInventoryScreenAccessor).`recast$listener`()
            if (listener != null) minecraft.player?.playerMenu?.removeListener(listener)
        }
        screen = null
        key = null
        itemsVersion = -1
        syncedText = null
        syncedCursor = -1
        syncedDetail = -1
    }

    fun render(tickDelta: Float) {
        if (!active()) {
            if (screen != null) close()
            return
        }
        val shadow = session()?.shadow ?: return
        val state = shadow.localPlayer.screen
        val player = minecraft.player
        if (!state.isOpen || player == null || minecraft.world == null) {
            if (screen != null) close()
            return
        }
        val window = Window(minecraft)
        val width = window.width
        val height = window.height
        val built = ensure(state, shadow.localPlayer.window, player, width, height)
        if (built == null) {
            if (screen != null) close()
            return
        }
        sync(built, state, shadow.localPlayer.window)
        val mouseX = mapMouseX(state, width)
        val mouseY = mapMouseY(state, height)
        GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT)
        try {
            withCreativeMode(built) { built.render(mouseX, mouseY, tickDelta) }
        } catch (error: Throwable) {
            logger.warn("Mirrored screen render failed, dropping it", error)
            close()
            return
        }
        if (cameraDriver.settings.mirrorCursor && state.kind != LocalScreen.CHAT) drawCursor(mouseX, mouseY)
        restoreGuiState()
    }

    private fun ensure(
        state: LocalScreen,
        window: ShadowWindow?,
        player: PlayerEntity,
        width: Int,
        height: Int
    ): Screen? {
        val container = state.kind == LocalScreen.CONTAINER
        val wanted = Key(
            kind = state.kind,
            windowId = if (container) state.windowId else 0,
            type = if (container) window?.type ?: "" else "",
            size = if (container) window?.size ?: 0 else 0,
            title = if (container) window?.titleJson ?: "" else "",
            position = if (state.kind == LocalScreen.SIGN) state.position else 0L,
            width = width,
            height = height,
            player = System.identityHashCode(player),
        )
        val current = screen
        if (current != null && wanted == key) return current
        close()
        val created = runCatching {
            create(
                state,
                window,
                player
            )
        }.onFailure { logger.warn("Could not mirror screen kind ${state.kind}", it) }.getOrNull() ?: return null
        val repeat = Keyboard.areRepeatEventsEnabled()
        try {
            withCreativeMode(created) { created.init(minecraft, width, height) }
        } catch (error: Throwable) {
            logger.warn("Mirrored screen init failed", error)
            return null
        } finally {
            Keyboard.enableRepeatEvents(repeat)
            player.menu = player.playerMenu
        }
        screen = created
        key = wanted
        itemsVersion = -1
        syncedText = null
        syncedCursor = -1
        syncedDetail = -1
        return created
    }

    private fun create(state: LocalScreen, window: ShadowWindow?, player: PlayerEntity): Screen? = when (state.kind) {
        LocalScreen.INVENTORY -> MirrorInventoryScreen(player) { modelEntity(player) }
        LocalScreen.CREATIVE -> CreativeInventoryScreen(player)
        LocalScreen.CONTAINER -> if (window != null && window.id == state.windowId) containerScreen(
            window,
            player
        ) else null

        LocalScreen.CHAT -> MirrorChatScreen(state.text)
        LocalScreen.PAUSE -> GameMenuScreen()
        LocalScreen.DEATH -> DeathScreen()
        LocalScreen.SIGN -> signScreen(state)
        LocalScreen.BOOK -> bookScreen(state, player)
        else -> null
    }

    private fun signScreen(state: LocalScreen): Screen? {
        val world = minecraft.world ?: return null
        val pos = BlockPos.fromLong(state.position)
        val sign = world.getBlockEntity(pos) as? SignBlockEntity ?: SignBlockEntity().also {
            it.setWorld(world)
            it.pos = pos
        }
        return MirrorSignScreen(sign)
    }

    private fun bookScreen(state: LocalScreen, player: PlayerEntity): Screen? {
        val held = player.getItemInHand() ?: return null
        val book = held.copy()
        return BookEditScreen(player, book, true)
    }

    private fun containerScreen(window: ShadowWindow, player: PlayerEntity): Screen? {
        val world = minecraft.world ?: return null
        val inventory = (player as? ClientPlayerEntity)?.inventory ?: player.inventory
        val title = title(window.titleJson)
        return when (window.type) {
            "minecraft:crafting_table" -> CraftingTableScreen(inventory, world)
            "minecraft:enchanting_table" -> EnchantingTableScreen(
                inventory,
                world,
                EmptyMenuProvider(window.type, title)
            )

            "minecraft:anvil" -> AnvilScreen(inventory, world)
            "minecraft:villager" -> VillagerScreen(inventory, ClientTrader(player, title), world)
            "EntityHorse" -> {
                val horse = world.getEntity(window.horseEntityId) as? HorseBaseEntity ?: return null
                HorseScreen(inventory, AnimalInventory(title, window.size), horse)
            }

            else -> {
                val contents = MenuInventory(window.type, title, window.size.coerceAtLeast(0))
                when (window.type) {
                    "minecraft:hopper" -> HopperScreen(inventory, contents)
                    "minecraft:furnace" -> FurnaceScreen(inventory, contents)
                    "minecraft:brewing_stand" -> BrewingStandScreen(inventory, contents)
                    "minecraft:beacon" -> BeaconScreen(inventory, contents)
                    "minecraft:dispenser", "minecraft:dropper" -> DispenserScreen(inventory, contents)
                    else -> ChestScreen(
                        inventory,
                        if (window.type == "minecraft:container") SimpleInventory(
                            title,
                            window.size.coerceAtLeast(0)
                        ) else contents
                    )
                }
            }
        }
    }

    private fun sync(target: Screen, state: LocalScreen, window: ShadowWindow?) {
        when (target) {
            is MirrorChatScreen -> if (textChanged(state)) target.setDraft(state.text, state.cursor)
            is CreativeInventoryScreen -> syncCreative(target, state)
            is MirrorSignScreen -> if (textChanged(state)) target.setLines(state.text, state.cursor)
            is BookEditScreen -> if (textChanged(state) || detailChanged(state)) syncBook(target, state)
            is InventoryMenuScreen -> {
                if (state.kind != LocalScreen.CONTAINER || window == null) return
                if (target is AnvilScreen && textChanged(state)) {
                    val field = (target as AnvilScreenAccessor).`recast$renameField`()
                    if (field != null) {
                        field.text = state.text
                        field.cursor = state.cursor.coerceIn(0, state.text.length)
                    }
                }
                if (target is VillagerScreen && detailChanged(state)) {
                    (target as VillagerScreenAccessor).`recast$setCurrentPage`(state.detail)
                    (target.menu as? TraderMenu)?.setRecipeIndex(state.detail)
                }
                if (window.version == itemsVersion) return
                itemsVersion = window.version
                val menu = target.menu ?: return
                if (target is VillagerScreen) window.trades?.let { applyTrades(target, it) }
                val slots = menu.slots.size
                if (window.items.isNotEmpty()) {
                    val converted = arrayOfNulls<ItemStack>(slots)
                    for (index in 0 until minOf(slots, window.items.size)) converted[index] =
                        toStack(window.items[index])
                    menu.setItems(converted)
                }
                for ((property, value) in window.properties) menu.setData(property, value)
                if (target is VillagerScreen) (target.menu as? TraderMenu)?.setRecipeIndex(state.detail)
            }

            else -> Unit
        }
    }

    private fun textChanged(state: LocalScreen): Boolean {
        if (state.text == syncedText && state.cursor == syncedCursor) return false
        syncedText = state.text
        syncedCursor = state.cursor
        return true
    }

    private fun detailChanged(state: LocalScreen): Boolean {
        if (state.detail == syncedDetail) return false
        syncedDetail = state.detail
        return true
    }

    private fun syncCreative(target: CreativeInventoryScreen, state: LocalScreen) {
        val accessor = target as CreativeInventoryScreenAccessor
        val tabChanged = detailChanged(state)
        val text = textChanged(state)
        withCreativeMode(target) {
            if (tabChanged) {
                val tab = CreativeModeTab.ALL.getOrNull(state.detail) ?: CreativeModeTab.ALL[0]
                accessor.`recast$selectTab`(tab)
            }
            val field = accessor.`recast$searchField`()
            if (field != null && (text || tabChanged) && state.detail == CreativeModeTab.SEARCH.getId()) {
                field.text = state.text
                field.cursor = state.text.length
                accessor.`recast$search`()
            }
            accessor.`recast$setScrollPosition`((state.cursor / ScreenCapture.SCROLL_SCALE).coerceIn(0f, 1f))
        }
    }

    private fun syncBook(target: BookEditScreen, state: LocalScreen) {
        val accessor = target as BookEditScreenAccessor
        val signing = state.cursor == 1
        accessor.`recast$setSigning`(signing)
        if (signing) {
            accessor.`recast$setTitle`(state.text)
            return
        }
        val pages = accessor.`recast$pages`() ?: NbtList()
        while (pages.size() <= state.detail) pages.addElement(NbtString(""))
        pages.setElement(state.detail, NbtString(state.text))
        accessor.`recast$setPageCount`(maxOf(1, pages.size()))
        accessor.`recast$setCurrentPage`(state.detail.coerceIn(0, pages.size() - 1))
    }

    private fun applyTrades(target: VillagerScreen, data: ByteArray) {
        val buffer = PacketByteBuf(Unpooled.wrappedBuffer(data))
        try {
            buffer.readInt()
            target.trader.setOffers(TradeOffers.deserialize(buffer))
        } catch (error: Exception) {
            logger.debug("Could not decode mirrored trade list", error)
        } finally {
            buffer.release()
        }
    }

    private inline fun withCreativeMode(target: Screen, block: () -> Unit) {
        if (target !is CreativeInventoryScreen) {
            block()
            return
        }
        val manager = minecraft.interactionManager
        val previous = manager?.gameMode
        if (manager != null && previous != WorldSettings.GameMode.CREATIVE) (manager as ClientPlayerInteractionManagerAccessor).`recast$setRawGameMode`(
            WorldSettings.GameMode.CREATIVE
        )
        try {
            block()
        } finally {
            if (manager != null && previous != null && previous != WorldSettings.GameMode.CREATIVE) (manager as ClientPlayerInteractionManagerAccessor).`recast$setRawGameMode`(
                previous
            )
        }
    }

    private fun toStack(item: gg.sona.recast.protocol.ItemStack): ItemStack? {
        if (item.isEmpty) return null
        itemWriter.reset()
        SlotCodec.write(itemWriter, item)
        val buffer = PacketByteBuf(Unpooled.wrappedBuffer(itemWriter.toByteArray()))
        return try {
            buffer.readItem()
        } catch (error: Exception) {
            null
        } finally {
            buffer.release()
        }
    }

    private fun title(json: String): Text =
        runCatching { Text.Serializer.fromJson(json) }.getOrNull() ?: LiteralText(json)

    private fun modelEntity(player: PlayerEntity): LivingEntity {
        val recorderId = session()?.shadow?.localPlayer?.entityId ?: return player
        val world = minecraft.world ?: return player
        return world.getEntity(recorderId) as? LivingEntity ?: player
    }

    fun beginModel() {
        renderingModel = true
    }

    fun endModel() {
        renderingModel = false
    }

    private fun mapMouseX(state: LocalScreen, width: Int): Int = when (state.kind) {
        LocalScreen.CHAT -> state.mouseX
        else -> width / 2 + (state.mouseX - state.guiWidth / 2)
    }.coerceIn(0, maxOf(0, width - 1))

    private fun mapMouseY(state: LocalScreen, height: Int): Int = when (state.kind) {
        LocalScreen.CHAT -> height - (state.guiHeight - state.mouseY)
        else -> height / 2 + (state.mouseY - state.guiHeight / 2)
    }.coerceIn(0, maxOf(0, height - 1))

    private fun drawCursor(x: Int, y: Int) {
        GlStateManager.disableTexture()
        GlStateManager.disableLighting()
        GlStateManager.disableDepthTest()
        GlStateManager.enableBlend()
        GlStateManager.blendFuncSeparate(770, 771, 1, 0)
        GlStateManager.pushMatrix()
        GlStateManager.translatef(x.toFloat(), y.toFloat(), 400f)
        polygon(CURSOR_OUTLINE, 0f, 0f, 0f, 0.9f)
        polygon(CURSOR_FILL, 1f, 1f, 1f, 1f)
        GlStateManager.popMatrix()
        GlStateManager.disableBlend()
        GlStateManager.enableTexture()
        GlStateManager.color4f(1f, 1f, 1f, 1f)
    }

    private fun polygon(points: FloatArray, r: Float, g: Float, b: Float, a: Float) {
        GlStateManager.color4f(r, g, b, a)
        GL11.glBegin(GL11.GL_TRIANGLE_FAN)
        var index = 0
        while (index < points.size) {
            GL11.glVertex2f(points[index], points[index + 1])
            index += 2
        }
        GL11.glEnd()
    }

    private fun restoreGuiState() {
        Lighting.turnOff()
        GlStateManager.disableLighting()
        GlStateManager.disableDepthTest()
        GlStateManager.disableRescaleNormal()
        GlStateManager.enableBlend()
        GlStateManager.blendFuncSeparate(770, 771, 1, 0)
        GlStateManager.enableAlphaTest()
        GlStateManager.color4f(1f, 1f, 1f, 1f)
    }

    private inner class MirrorInventoryScreen(player: PlayerEntity, private val model: () -> LivingEntity) :
        SurvivalInventoryScreen(player) {

        private var lastMouseX = 0f
        private var lastMouseY = 0f

        override fun tick() = Unit

        override fun render(mouseX: Int, mouseY: Int, tickDelta: Float) {
            lastMouseX = mouseX.toFloat()
            lastMouseY = mouseY.toFloat()
            super.render(mouseX, mouseY, tickDelta)
        }

        override fun renderMenuBackground(tickDelta: Float, mouseX: Int, mouseY: Int) {
            GlStateManager.color4f(1f, 1f, 1f, 1f)
            minecraft.textureManager.bind(MENU_LOCATION)
            val left = x
            val top = y
            drawTexture(left, top, 0, 0, backgroundWidth, backgroundHeight)
            beginModel()
            try {
                renderEntity(left + 51, top + 75, 30, left + 51 - lastMouseX, top + 75 - 50 - lastMouseY, model())
            } finally {
                endModel()
            }
        }
    }

    private class MirrorChatScreen(draft: String) : ChatScreen(draft) {

        fun setDraft(text: String, cursor: Int) {
            val field = chatField ?: return
            field.text = text
            field.cursor = cursor.coerceIn(0, text.length)
        }

        override fun render(mouseX: Int, mouseY: Int, tickDelta: Float) {
            fill(2, height - 14, width - 2, height - 2, Int.MIN_VALUE)
            chatField?.render()
        }

        override fun handleMouse() = Unit
    }

    private class MirrorSignScreen(private val target: SignBlockEntity) : SignEditScreen(target) {

        fun setLines(text: String, row: Int) {
            val lines = text.split('\n')
            for (index in target.lines.indices) target.lines[index] = LiteralText(lines.getOrElse(index) { "" })
            target.currentRow = row.coerceIn(0, target.lines.size - 1)
            (this as SignEditScreenAccessor).`recast$setRow`(target.currentRow)
        }

        override fun removed() = Unit
    }

    private companion object {
        val CURSOR_OUTLINE =
            floatArrayOf(-1f, -1f, -1f, 13.5f, 2.6f, 10.2f, 5.2f, 15.6f, 8.6f, 14.1f, 6f, 8.9f, 10.7f, 8.9f)
        val CURSOR_FILL = floatArrayOf(0f, 0f, 0f, 11f, 2.8f, 8.4f, 5.2f, 13.4f, 6.8f, 12.7f, 4.5f, 7.6f, 8.2f, 7.6f)
    }
}
