package gg.sona.afterimage.flashback

object ChatText {

    private val textPattern = Regex("\"(?:text|translate)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val colorCodes = Regex("§.")

    fun plain(json: String): String {
        if (json.isEmpty()) return json
        if (!json.startsWith("{") && !json.startsWith("[")) return strip(unquote(json))
        val builder = StringBuilder()
        for (match in textPattern.findAll(json)) builder.append(unescape(match.groupValues[1]))
        return strip(builder.toString())
    }

    fun strip(text: String): String = colorCodes.replace(text, "")

    private fun unquote(value: String): String =
        if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) unescape(
            value.substring(
                1,
                value.length - 1
            )
        ) else value

    private fun unescape(value: String): String {
        if (!value.contains('\\')) return value
        val builder = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char != '\\' || index + 1 >= value.length) {
                builder.append(char)
                index++
                continue
            }
            val next = value[index + 1]
            when (next) {
                'n' -> builder.append('\n')
                't' -> builder.append('\t')
                'u' -> {
                    if (index + 5 < value.length) {
                        builder.append(value.substring(index + 2, index + 6).toInt(16).toChar())
                        index += 4
                    }
                }

                else -> builder.append(next)
            }
            index += 2
        }
        return builder.toString()
    }
}
