package gg.sona.recast.editor

interface CommandStackListener {
    fun onHistoryChanged(stack: CommandStack) {}
}
