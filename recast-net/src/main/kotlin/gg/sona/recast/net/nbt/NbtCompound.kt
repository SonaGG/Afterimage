package gg.sona.recast.net.nbt


class NbtCompound(val entries: LinkedHashMap<String, NbtTag> = LinkedHashMap()) : NbtTag(10) {

    operator fun get(key: String): NbtTag? = entries[key]

    fun string(key: String): String? = (entries[key] as? NbtString)?.value

    fun int(key: String): Int? = when (val tag = entries[key]) {
        is NbtInt -> tag.value
        is NbtShort -> tag.value.toInt()
        is NbtByte -> tag.value.toInt()
        is NbtLong -> tag.value.toInt()
        else -> null
    }

    fun compound(key: String): NbtCompound? = entries[key] as? NbtCompound

    fun list(key: String): NbtList? = entries[key] as? NbtList

    fun put(key: String, tag: NbtTag): NbtCompound {
        entries[key] = tag
        return this
    }

    val size: Int get() = entries.size
}
