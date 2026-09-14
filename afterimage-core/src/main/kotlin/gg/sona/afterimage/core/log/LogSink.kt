package gg.sona.afterimage.core.log

fun interface LogSink {
    fun log(level: LogLevel, logger: String, message: String, error: Throwable?)
}
