package gg.sona.afterimage.editor.imgui

import imgui.type.ImBoolean

abstract class DialogPanel(
    override val title: String,
    override val icon: Icon,
    width: Float,
    height: Float,
    sections: List<Dialog.Section> = emptyList(),
    private val hasFooter: Boolean = false,
) : EditorPanel {

    override val dockArea: DockArea get() = DockArea.FLOATING

    override val open: ImBoolean = ImBoolean(false)

    protected val dialog = Dialog(title, icon, width, height).also { it.sections = sections }

    private val guard = PanelGuard(title)

    override fun draw(frame: FrameContext) {
        tick(frame)
        dialog.draw(open, { guard.run { content(frame) } }, if (hasFooter) ({ guard.run { footer(frame) } }) else null)
    }

    protected open fun tick(frame: FrameContext) = Unit

    protected abstract fun content(frame: FrameContext)

    protected open fun footer(frame: FrameContext) = Unit
}
