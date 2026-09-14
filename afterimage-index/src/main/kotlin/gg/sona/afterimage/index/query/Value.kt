package gg.sona.afterimage.index.query

sealed class Value {
    data class Num(val value: Double) : Value()
    data class Bool(val value: Boolean) : Value()
    data class Str(val value: String) : Value()
    object Missing : Value()

    val truthy: Boolean
        get() = when (this) {
            is Num -> !value.isNaN() && value != 0.0
            is Bool -> value
            is Str -> value.isNotEmpty()
            Missing -> false
        }

    companion object {
        val TRUE = Bool(true)
        val FALSE = Bool(false)
        fun of(value: Boolean): Bool = if (value) TRUE else FALSE
        fun of(value: Double): Value = if (value.isNaN()) Missing else Num(value)
    }
}
