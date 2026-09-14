package gg.sona.afterimage.editor.host

data class EntityOverride(val hideNametag: Boolean = false) {
    companion object {
        val NONE = EntityOverride()
    }
}
