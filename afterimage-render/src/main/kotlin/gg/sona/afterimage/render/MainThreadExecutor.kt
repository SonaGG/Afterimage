package gg.sona.afterimage.render


interface MainThreadExecutor {
    fun <T> call(action: () -> T): T
}
