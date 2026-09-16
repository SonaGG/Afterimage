package gg.sona.afterimage.mc263

import gg.sona.afterimage.mc263.mixin.BookEditScreenAccessor
import gg.sona.afterimage.mc263.mixin.BookSignScreenAccessor
import gg.sona.afterimage.mc263.mixin.ChatScreenAccessor
import gg.sona.afterimage.mc263.mixin.CreativeModeInventoryScreenAccessor
import gg.sona.afterimage.mc263.mixin.SignEditScreenAccessor
import gg.sona.afterimage.net.PackedPosition
import gg.sona.afterimage.protocol.LocalScreen
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.DeathScreen
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen
import net.minecraft.client.gui.screens.inventory.AnvilScreen
import net.minecraft.client.gui.screens.inventory.BookEditScreen
import net.minecraft.client.gui.screens.inventory.BookSignScreen
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.gui.screens.inventory.MerchantScreen
import net.minecraft.world.item.CreativeModeTabs

object ScreenCapture {
    const val SCROLL_SCALE = 10_000f

    fun capture(minecraft: Minecraft): LocalScreen {
        val flags = hudFlags(minecraft)
        val screen = minecraft.gui.screen() ?: return if (flags == 0) LocalScreen.CLOSED else LocalScreen.CLOSED.copy(flags = flags)
        val window = minecraft.window
        val width = window.guiScaledWidth
        val height = window.guiScaledHeight
        val mouseX = if (window.screenWidth > 0) (minecraft.mouseHandler.xpos() * width / window.screenWidth).toInt() else 0
        val mouseY = if (window.screenHeight > 0) (minecraft.mouseHandler.ypos() * height / window.screenHeight).toInt() else 0
        fun screen(kind: Int, windowId: Int = 0, text: String = "", cursor: Int = 0, detail: Int = 0, position: Long = 0L) =
            LocalScreen(kind, windowId, mouseX, mouseY, width, height, text.take(LocalScreen.MAX_TEXT), cursor, detail, position, flags)
        return when (screen) {
            is CreativeModeInventoryScreen -> {
                val accessor = screen as CreativeModeInventoryScreenAccessor
                val tabs = CreativeModeTabs.tabs()
                screen(
                    LocalScreen.CREATIVE,
                    0,
                    accessor.afterimage_searchBox()?.value ?: "",
                    (accessor.afterimage_scrollOffs() * SCROLL_SCALE).toInt(),
                    tabs.indexOf(CreativeModeInventoryScreenAccessor.afterimage_selectedTab()).coerceAtLeast(0),
                )
            }

            is InventoryScreen -> screen(LocalScreen.INVENTORY)
            is AnvilScreen -> screen(LocalScreen.CONTAINER, screen.menu.containerId and 0xFF)
            is MerchantScreen -> screen(LocalScreen.CONTAINER, screen.menu.containerId and 0xFF)
            is AbstractContainerScreen<*> -> screen(LocalScreen.CONTAINER, screen.menu.containerId and 0xFF)
            is ChatScreen -> {
                val field = (screen as ChatScreenAccessor).afterimage_input()
                val text = field?.value ?: ""
                screen(LocalScreen.CHAT, 0, text, (field?.cursorPosition ?: 0).coerceIn(0, text.length))
            }

            is PauseScreen -> screen(LocalScreen.PAUSE)
            is DeathScreen -> screen(LocalScreen.DEATH)
            is AbstractSignEditScreen -> {
                val accessor = screen as SignEditScreenAccessor
                val pos = accessor.afterimage_sign().blockPos
                screen(LocalScreen.SIGN, 0, accessor.afterimage_messages().joinToString("\n") { it ?: "" }, accessor.afterimage_line(), 0, PackedPosition.pack(pos.x, pos.y, pos.z))
            }

            is BookEditScreen -> {
                val accessor = screen as BookEditScreenAccessor
                val page = accessor.afterimage_currentPage()
                screen(LocalScreen.BOOK, 0, accessor.afterimage_pages().getOrNull(page) ?: "", 0, page)
            }

            is BookSignScreen -> screen(LocalScreen.BOOK, 0, (screen as BookSignScreenAccessor).afterimage_titleBox()?.value ?: "", 1, 0)
            else -> screen(LocalScreen.OTHER, 0, screen.javaClass.simpleName)
        }
    }

    private fun hudFlags(minecraft: Minecraft): Int {
        var flags = 0
        if (minecraft.gui.hud.isHidden) flags = flags or LocalScreen.FLAG_HIDE_GUI
        if (minecraft.options.keyPlayerList.isDown && minecraft.gui.screen() == null) flags = flags or LocalScreen.FLAG_PLAYER_LIST
        return flags or LocalScreen.perspectiveFlags(minecraft.options.cameraType.ordinal)
    }
}
