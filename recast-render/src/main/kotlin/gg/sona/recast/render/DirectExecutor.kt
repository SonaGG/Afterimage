package gg.sona.recast.render


object DirectExecutor : MainThreadExecutor {
    override fun <T> call(action: () -> T): T = action()
}
