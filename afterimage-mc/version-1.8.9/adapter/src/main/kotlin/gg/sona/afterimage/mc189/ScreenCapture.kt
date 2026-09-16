package gg.sona.afterimage.mc189

import gg.sona.afterimage.mc189.ui.ExportScreen
import gg.sona.afterimage.mc189.mixin.*
import gg.sona.afterimage.mc189.ui.WorkspaceScreen
import gg.sona.afterimage.protocol.LocalScreen
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screen.ChatScreen
import net.minecraft.client.gui.screen.DeathScreen
import net.minecraft.client.gui.screen.GameMenuScreen
import net.minecraft.client.gui.screen.inventory.BookEditScreen
import net.minecraft.client.gui.screen.inventory.menu.*
import net.minecraft.client.render.Window
import net.minecraft.text.Text
import org.lwjgl.input.Mouse

object ScreenCapture {

    fun capture(minecraft: Minecraft): LocalScreen {
        val flags = hudFlags(minecraft)
        val screen =
            minecraft.screen ?: return if (flags == 0) LocalScreen.CLOSED else LocalScreen.CLOSED.copy(flags = flags)
        if (screen is WorkspaceScreen || screen is ExportScreen) return LocalScreen.CLOSED.copy(flags = flags)
        val window = Window(minecraft)
        val width = window.width
        val height = window.height
        val mouseX = if (minecraft.width > 0) Mouse.getX() * width / minecraft.width else 0
        val mouseY = if (minecraft.height > 0) height - Mouse.getY() * height / minecraft.height - 1 else 0
        fun screen(
            kind: Int,
            windowId: Int = 0,
            text: String = "",
            cursor: Int = 0,
            detail: Int = 0,
            position: Long = 0L
        ) =
            LocalScreen(
                kind,
                windowId,
                mouseX,
                mouseY,
                width,
                height,
                text.take(LocalScreen.MAX_TEXT),
                cursor,
                detail,
                position,
                flags
            )
        return when (screen) {
            is CreativeInventoryScreen -> {
                val accessor = screen as CreativeInventoryScreenAccessor
                val search = accessor.`afterimage$searchField`()?.text ?: ""
                screen(
                    LocalScreen.CREATIVE,
                    0,
                    search,
                    (accessor.`afterimage$scrollPosition`() * SCROLL_SCALE).toInt(),
                    screen.selectedTab
                )
            }

            is SurvivalInventoryScreen -> screen(LocalScreen.INVENTORY)
            is AnvilScreen -> {
                val field = (screen as AnvilScreenAccessor).`afterimage$renameField`()
                screen(
                    LocalScreen.CONTAINER,
                    (screen as InventoryMenuScreen).menu.networkId and 0xFF,
                    field?.text ?: "",
                    field?.cursor ?: 0
                )
            }

            is VillagerScreen -> screen(
                LocalScreen.CONTAINER,
                screen.menu.networkId and 0xFF,
                "",
                0,
                (screen as VillagerScreenAccessor).`afterimage$currentPage`()
            )

            is InventoryMenuScreen -> screen(LocalScreen.CONTAINER, screen.menu.networkId and 0xFF)
            is ChatScreen -> {
                val field = (screen as ChatScreenAccessor).`afterimage$chatField`()
                val text = field?.text ?: ""
                screen(LocalScreen.CHAT, 0, text, (field?.cursor ?: 0).coerceIn(0, text.length))
            }

            is GameMenuScreen -> screen(LocalScreen.PAUSE)
            is DeathScreen -> screen(LocalScreen.DEATH)
            is SignEditScreen -> {
                val accessor = screen as SignEditScreenAccessor
                val sign = accessor.`afterimage$sign`()
                val lines = sign.lines.joinToString("\n") { line: Text? -> line?.string ?: "" }
                screen(LocalScreen.SIGN, 0, lines, accessor.`afterimage$row`(), 0, sign.pos.toLong())
            }

            is BookEditScreen -> {
                val accessor = screen as BookEditScreenAccessor
                val page = accessor.`afterimage$currentPage`()
                val pages = accessor.`afterimage$pages`()
                val text = if (accessor.`afterimage$signing`()) accessor.`afterimage$title`() else pages?.let {
                    if (page in 0 until it.size()) it.getString(page) else ""
                } ?: ""
                screen(LocalScreen.BOOK, 0, text, if (accessor.`afterimage$signing`()) 1 else 0, page)
            }

            else -> screen(LocalScreen.OTHER, 0, screen.javaClass.simpleName)
        }
    }

    private fun hudFlags(minecraft: Minecraft): Int {
        var flags = 0
        if (minecraft.options.hideGui) flags = flags or LocalScreen.FLAG_HIDE_GUI
        if (minecraft.options.playerListKey.isPressed && minecraft.screen == null) flags =
            flags or LocalScreen.FLAG_PLAYER_LIST
        return flags or LocalScreen.perspectiveFlags(minecraft.options.perspective)
    }

    const val SCROLL_SCALE = 10_000f
}
