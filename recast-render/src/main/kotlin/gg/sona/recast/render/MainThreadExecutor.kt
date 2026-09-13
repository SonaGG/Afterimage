package gg.sona.recast.render


interface MainThreadExecutor {
    fun <T> call(action: () -> T): T
}
