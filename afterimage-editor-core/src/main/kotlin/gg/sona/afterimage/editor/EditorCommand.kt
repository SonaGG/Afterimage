package gg.sona.afterimage.editor


interface EditorCommand {
    val label: String

    fun apply(project: EditorProject)

    fun revert(project: EditorProject)

    fun mergeWith(next: EditorCommand): EditorCommand? = null
}
