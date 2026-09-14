package gg.sona.afterimage.render


object DirectExecutor : MainThreadExecutor {
    override fun <T> call(action: () -> T): T = action()
}
