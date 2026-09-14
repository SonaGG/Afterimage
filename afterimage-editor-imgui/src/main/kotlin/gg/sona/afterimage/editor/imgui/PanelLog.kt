package gg.sona.afterimage.editor.imgui


object PanelLog {
    var sink: (String, Throwable) -> Unit = { message, error ->
        System.err.println(message)
        error.printStackTrace()
    }

    fun error(message: String, error: Throwable) = sink(message, error)
}
