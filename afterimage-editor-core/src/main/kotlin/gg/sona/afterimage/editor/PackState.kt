package gg.sona.afterimage.editor

data class PackState(val packs: List<String>) {
    val isDefault: Boolean get() = packs.isEmpty()

    fun toggled(name: String): PackState =
        if (name in packs) PackState(packs - name) else PackState(packs + name)

    companion object {
        val DEFAULT = PackState(emptyList())
    }
}
