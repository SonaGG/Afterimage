package gg.sona.recast.index.query

sealed class Expr {
    abstract val position: Int

    data class Num(val value: Double, override val position: Int) : Expr()
    data class Str(val value: String, override val position: Int) : Expr()
    data class Ref(val name: String, override val position: Int) : Expr()
    data class Call(val name: String, val args: List<Expr>, override val position: Int) : Expr()
    data class Compare(val op: String, val left: Expr, val right: Expr, override val position: Int) : Expr()
    data class And(val left: Expr, val right: Expr, override val position: Int) : Expr()
    data class Or(val left: Expr, val right: Expr, override val position: Int) : Expr()
    data class Not(val inner: Expr, override val position: Int) : Expr()
}
