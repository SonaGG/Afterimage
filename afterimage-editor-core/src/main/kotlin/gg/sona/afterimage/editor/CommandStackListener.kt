package gg.sona.afterimage.editor

interface CommandStackListener {
    fun onHistoryChanged(stack: CommandStack) {}
}
