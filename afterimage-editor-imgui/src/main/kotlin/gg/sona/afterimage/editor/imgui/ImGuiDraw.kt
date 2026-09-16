package gg.sona.afterimage.editor.imgui

import gg.sona.afterimage.gfx.Filter
import gg.sona.afterimage.gfx.Gfx
import gg.sona.afterimage.gfx.Target
import gg.sona.afterimage.gfx.Texture
import gg.sona.afterimage.gfx.UiRenderer
import imgui.ImDrawData
import imgui.ImGui
import imgui.ImVec4
import imgui.type.ImInt

class ImGuiDraw(private val gfx: Gfx) {
    private var renderer: UiRenderer? = null
    private var fontTexture: Texture? = null
    private val clip = ImVec4()

    fun start() {
        if (renderer == null) renderer = gfx.createUiRenderer()
    }

    fun createFontAtlas() {
        val width = ImInt()
        val height = ImInt()
        val pixels = ImGui.getIO().fonts.getTexDataAsRGBA32(width, height)
        fontTexture?.close()
        val texture = gfx.uploadTexture(width.get(), height.get(), pixels, Filter.LINEAR)
        fontTexture = texture
        ImGui.getIO().fonts.setTexID(texture.handle.toLong())
    }

    fun render(data: ImDrawData, target: Target?, framebufferWidth: Int, framebufferHeight: Int) {
        val renderer = renderer ?: return
        if (!data.valid || data.cmdListsCount == 0) return
        if (framebufferWidth <= 0 || framebufferHeight <= 0) return
        val offsetX = data.displayPosX
        val offsetY = data.displayPosY
        val scaleX = data.framebufferScaleX
        val scaleY = data.framebufferScaleY
        renderer.begin(target, framebufferWidth, framebufferHeight)
        try {
            for (list in 0 until data.cmdListsCount) {
                renderer.uploadVertices(data.getCmdListVtxBufferData(list))
                renderer.uploadIndices(data.getCmdListIdxBufferData(list))
                for (command in 0 until data.getCmdListCmdBufferSize(list)) {
                    val elements = data.getCmdListCmdBufferElemCount(list, command)
                    if (elements == 0) continue
                    data.getCmdListCmdBufferClipRect(clip, list, command)
                    val left = ((clip.x - offsetX) * scaleX).toInt()
                    val right = ((clip.z - offsetX) * scaleX).toInt()
                    val top = ((clip.y - offsetY) * scaleY).toInt()
                    val bottom = ((clip.w - offsetY) * scaleY).toInt()
                    renderer.draw(
                        left, top, right - left, bottom - top,
                        data.getCmdListCmdBufferTextureId(list, command).toInt(),
                        elements,
                        data.getCmdListCmdBufferIdxOffset(list, command),
                        data.getCmdListCmdBufferVtxOffset(list, command),
                    )
                }
            }
        } finally {
            renderer.end()
        }
    }

    fun destroy() {
        fontTexture?.close()
        fontTexture = null
        renderer?.close()
        renderer = null
    }
}
