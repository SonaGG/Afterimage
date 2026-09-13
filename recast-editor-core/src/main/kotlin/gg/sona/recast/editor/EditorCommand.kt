package gg.sona.recast.editor


interface EditorCommand {
    val label: String

    fun apply(project: EditorProject)

    fun revert(project: EditorProject)

    fun mergeWith(next: EditorCommand): EditorCommand? = null
}
