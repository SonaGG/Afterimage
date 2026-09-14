package gg.sona.afterimage.editor


interface EditorSessionListener {
    fun onSelectionChanged(selection: Selection) {}
    fun onInboxChanged(entries: List<InboxEntry>) {}
    fun onProjectChanged(project: EditorProject) {}
}
