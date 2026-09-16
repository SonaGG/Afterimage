package gg.sona.afterimage.mc263.replay

import gg.sona.afterimage.camera.CameraMode
import gg.sona.afterimage.mc263.ScreenCapture
import gg.sona.afterimage.mc263.mixin.BookEditScreenAccessor
import gg.sona.afterimage.mc263.mixin.ChatScreenAccessor
import gg.sona.afterimage.mc263.mixin.CreativeModeInventoryScreenAccessor
import gg.sona.afterimage.mc263.mixin.MultiPlayerGameModeAccessor
import gg.sona.afterimage.mc263.mixin.SignEditScreenAccessor
import gg.sona.afterimage.mc263.state.ShadowWindow
import gg.sona.afterimage.net.PackedPosition
import gg.sona.afterimage.protocol.LocalScreen
import gg.sona.afterimage.replay.session.ReplaySession
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.DeathScreen
import net.minecraft.client.gui.screens.MenuScreens
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
import net.minecraft.client.gui.screens.inventory.BookEditScreen
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.gui.screens.inventory.SignEditScreen
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.CreativeModeTabs
import net.minecraft.world.item.component.WritableBookContent
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignText
import net.minecraft.world.level.block.entity.SignTextSlot
import org.slf4j.LoggerFactory

// one day this shit will be better
// but that day is not today
class ScreenMirror(private val minecraft: Minecraft, private val cameraDriver: CameraDriver, private val session: () -> ReplaySession?) {
    private val logger = LoggerFactory.getLogger("Afterimage")
    private var screen: Screen? = null
    private var key: Key? = null
    private var itemsVersion = -1
    private var syncedText: String? = null
    private var syncedCursor = -1
    private var syncedDetail = -1
    var renderingModel: Boolean = false
    var extracting: Boolean = false
        private set

    private data class Key(val kind: Int, val windowId: Int, val type: String, val size: Int, val position: Long, val width: Int, val height: Int, val player: Int)

    fun active(): Boolean {
        val settings = cameraDriver.settings
        return !(session() == null || !settings.mirrorScreens) && settings.mode == CameraMode.FIRST_PERSON && settings.targetsRecorder()
    }

    fun chatFocused(): Boolean = active() && session()?.shadow?.localPlayer?.screen?.kind == LocalScreen.CHAT

    fun recordedState(): LocalScreen? = if (active()) session()?.shadow?.localPlayer?.screen else null

    fun modelEntity(fallback: LivingEntity): LivingEntity {
        if (!active()) return fallback
        val recorderId = session()?.shadow?.localPlayer?.entityId ?: return fallback
        return minecraft.level?.getEntity(recorderId) as? LivingEntity ?: fallback
    }

    fun tick() {
        val current = screen ?: return
        if (!active()) return
        try {
            when (current) {
                is ChatScreen, is DeathScreen, is AbstractSignEditScreen, is BookEditScreen -> current.tick()
                else -> Unit
            }
        } catch (error: Throwable) {
            logger.warn("Mirrored screen tick failed", error)
        }
    }

    fun close() {
        val current = screen
        if (current != null) runCatching { current.removed() }
        val player = minecraft.player
        if (player != null && current is AbstractContainerScreen<*>) player.containerMenu = player.inventoryMenu
        screen = null
        key = null
        itemsVersion = -1
        syncedText = null
        syncedCursor = -1
        syncedDetail = -1
    }

    fun extract(graphics: GuiGraphicsExtractor, partialTick: Float) {
        if (!active()) {
            if (screen != null) close()
            return
        }
        val shadow = session()?.shadow ?: return
        val state = shadow.localPlayer.screen
        val player = minecraft.player
        if (!state.isOpen || player == null || minecraft.level == null) {
            if (screen != null) close()
            return
        }
        val window = minecraft.window
        val width = window.guiScaledWidth
        val height = window.guiScaledHeight
        val built = ensure(state, shadow.window, player, width, height)
        if (built == null) {
            if (screen != null) close()
            return
        }
        sync(built, state, shadow.window)
        val mouseX = mapMouseX(state, width)
        val mouseY = mapMouseY(state, height)
        try {
            extracting = true
            withCreativeMode(built) {
                graphics.nextStratum()
                built.extractRenderStateWithTooltipAndSubtitles(graphics, mouseX, mouseY, partialTick)
            }
        } catch (error: Throwable) {
            logger.warn("Mirrored screen render failed, dropping it", error)
            close()
            return
        } finally {
            extracting = false
        }
        if (cameraDriver.settings.mirrorCursor && state.kind != LocalScreen.CHAT) drawCursor(graphics, mouseX, mouseY)
    }

    private fun ensure(state: LocalScreen, window: ShadowWindow?, player: LocalPlayer, width: Int, height: Int): Screen? {
        val container = state.kind == LocalScreen.CONTAINER
        val wanted = Key(
            kind = state.kind,
            windowId = if (container) state.windowId else 0,
            type = if (container) window?.type ?: "" else "",
            size = if (container) window?.size ?: 0 else 0,
            position = if (state.kind == LocalScreen.SIGN) state.position else 0L,
            width = width,
            height = height,
            player = System.identityHashCode(player),
        )
        val current = screen
        if (current != null && wanted == key) return current
        close()
        val created = runCatching { create(state, window, player) }.onFailure { logger.warn("Could not mirror screen kind ${state.kind}", it) }.getOrNull() ?: return null
        try {
            withCreativeMode(created) { created.init(width, height) }
        } catch (error: Throwable) {
            logger.warn("Mirrored screen init failed", error)
            return null
        } finally {
            player.containerMenu = player.inventoryMenu
        }
        logger.debug("Mirroring screen kind {} as {}", state.kind, created.javaClass.simpleName)
        screen = created
        key = wanted
        itemsVersion = -1
        syncedText = null
        syncedCursor = -1
        syncedDetail = -1
        return created
    }

    private fun create(state: LocalScreen, window: ShadowWindow?, player: LocalPlayer): Screen? = when (state.kind) {
        LocalScreen.INVENTORY -> InventoryScreen(player)
        LocalScreen.CREATIVE -> CreativeModeInventoryScreen(player, player.connection.enabledFeatures(), false)
        LocalScreen.CONTAINER -> if (window != null && window.id == state.windowId) containerScreen(window, player) else null
        LocalScreen.CHAT -> MirrorChatScreen(state.text)
        LocalScreen.PAUSE -> PauseScreen(true)
        LocalScreen.DEATH -> DeathScreen(null, false, player)
        LocalScreen.SIGN -> signScreen(state)
        LocalScreen.BOOK -> bookScreen(state, player)
        else -> null
    }

    private fun signScreen(state: LocalScreen): Screen? {
        val level = minecraft.level ?: return null
        val pos = BlockPos(PackedPosition.x(state.position), PackedPosition.y(state.position), PackedPosition.z(state.position))
        val sign = level.getBlockEntity(pos) as? SignBlockEntity ?: return null
        return MirrorSignScreen(sign)
    }

    private fun bookScreen(state: LocalScreen, player: LocalPlayer): Screen? {
        val held = player.mainHandItem
        if (held.isEmpty) return null
        return BookEditScreen(player, held.copy(), InteractionHand.MAIN_HAND, held.get(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT) ?: WritableBookContent.EMPTY)
    }

    private fun containerScreen(window: ShadowWindow, player: LocalPlayer): Screen? {
        val open = window.open ?: return null
        @Suppress("UNCHECKED_CAST")
        val type = open.type as MenuType<AbstractContainerMenu>
        val constructor = MenuScreens.getConstructor(type) ?: return null
        val menu = type.create(open.containerId, player.inventory)
        @Suppress("UNCHECKED_CAST")
        return (constructor as MenuScreens.ScreenConstructor<AbstractContainerMenu, *>).create(menu, player.inventory, open.title) as Screen
    }

    private fun sync(target: Screen, state: LocalScreen, window: ShadowWindow?) {
        when (target) {
            is MirrorChatScreen -> if (textChanged(state)) target.setDraft(state.text, state.cursor)
            is CreativeModeInventoryScreen -> syncCreative(target, state)
            is MirrorSignScreen -> if (textChanged(state)) target.setLines(state.text, state.cursor)
            is BookEditScreen -> if (textChanged(state) || detailChanged(state)) syncBook(target, state)
            is AbstractContainerScreen<*> -> {
                if (state.kind != LocalScreen.CONTAINER || window == null) return
                if (window.version == itemsVersion) return
                itemsVersion = window.version
                val menu = target.menu
                if (window.items.isNotEmpty()) {
                    val slots = menu.slots.size
                    val items = net.minecraft.core.NonNullList.withSize(slots, net.minecraft.world.item.ItemStack.EMPTY)
                    for (index in 0 until minOf(slots, window.items.size)) items[index] = window.items[index].copy()
                    menu.initializeContents(window.stateId, items, window.carried.copy())
                }
                for ((property, value) in window.properties) menu.setData(property, value)
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

    private fun syncCreative(target: CreativeModeInventoryScreen, state: LocalScreen) {
        val accessor = target as CreativeModeInventoryScreenAccessor
        val tabChanged = detailChanged(state)
        val text = textChanged(state)
        withCreativeMode(target) {
            if (tabChanged) {
                val tab = CreativeModeTabs.tabs().getOrNull(state.detail) ?: CreativeModeTabs.getDefaultTab()
                accessor.afterimage_selectTab(tab)
            }
            val field = accessor.afterimage_searchBox()
            if (field != null && (text || tabChanged) && CreativeModeTabs.tabs().getOrNull(state.detail) === CreativeModeTabs.searchTab()) {
                field.value = state.text
                field.moveCursorToEnd(false)
                accessor.afterimage_refreshSearchResults()
            }
            accessor.afterimage_setScrollOffs((state.cursor / ScreenCapture.SCROLL_SCALE).coerceIn(0f, 1f))
        }
    }

    private fun syncBook(target: BookEditScreen, state: LocalScreen) {
        val accessor = target as BookEditScreenAccessor
        val pages = accessor.afterimage_pages()
        while (pages.size <= state.detail) pages.add("")
        pages[state.detail] = state.text
        accessor.afterimage_setCurrentPage(state.detail.coerceIn(0, pages.size - 1))
        accessor.afterimage_updatePageContent()
        accessor.afterimage_updateButtonVisibility()
    }

    private inline fun withCreativeMode(target: Screen, block: () -> Unit) {
        if (target !is CreativeModeInventoryScreen) {
            block()
            return
        }
        val gameMode = minecraft.gameMode as? MultiPlayerGameModeAccessor
        val previous = gameMode?.afterimage_localPlayerMode()
        val abilities = minecraft.player?.abilities
        val previousInstabuild = abilities?.instabuild ?: true
        if (gameMode != null && previous != GameType.CREATIVE) gameMode.afterimage_setLocalPlayerMode(GameType.CREATIVE)
        if (abilities != null) abilities.instabuild = true
        try {
            block()
        } finally {
            if (abilities != null) abilities.instabuild = previousInstabuild
            if (gameMode != null && previous != null && previous != GameType.CREATIVE) gameMode.afterimage_setLocalPlayerMode(previous)
        }
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

    private fun drawCursor(graphics: GuiGraphicsExtractor, x: Int, y: Int) {
        graphics.nextStratum()
        val left = x - CURSOR_HOTSPOT_X
        val top = y - CURSOR_HOTSPOT_Y
        graphics.blit(CURSOR_TEXTURE, left, top, left + CURSOR_WIDTH, top + CURSOR_HEIGHT, 0f, 1f, 0f, 1f)
    }

    private class MirrorChatScreen(draft: String) : ChatScreen(draft, true, false) {
        fun setDraft(text: String, cursor: Int) {
            val field = (this as ChatScreenAccessor).afterimage_input() ?: return
            field.value = text
            field.setCursorPosition(cursor.coerceIn(0, text.length))
            field.setHighlightPos(field.cursorPosition)
        }

        override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) = Unit

        override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
            graphics.fill(2, height - 14, width - 2, height - 2, minecraft.options.getBackgroundColor(Int.MIN_VALUE))
            (this as ChatScreenAccessor).afterimage_input()?.extractRenderState(graphics, mouseX, mouseY, partialTick)
        }
    }

    private class MirrorSignScreen(private val target: SignBlockEntity) : SignEditScreen(target, SignTextSlot.FRONT, false) {
        fun setLines(text: String, row: Int) {
            val lines = text.split('\n')
            val accessor = this as SignEditScreenAccessor
            val messages = accessor.afterimage_messages()
            for (index in messages.indices) messages[index] = lines.getOrElse(index) { "" }
            accessor.afterimage_setLine(row.coerceIn(0, messages.size - 1))
            val slotText = target.getText(SignTextSlot.FRONT)
            val components = messages.map { Component.literal(it) as Component }
            target.setText(SignText(components, components, slotText.color, slotText.hasGlowingText()), SignTextSlot.FRONT)
        }

        override fun removed() = Unit
    }

    private companion object {
        val CURSOR_TEXTURE: Identifier = Identifier.fromNamespaceAndPath("afterimage", "textures/gui/cursor.png")
        const val CURSOR_WIDTH = 15
        const val CURSOR_HEIGHT = 22
        const val CURSOR_HOTSPOT_X = 1
        const val CURSOR_HOTSPOT_Y = 0
    }
}
