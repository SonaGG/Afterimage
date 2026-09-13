package gg.sona.recast.core.log

fun interface LogSink {
    fun log(level: LogLevel, logger: String, message: String, error: Throwable?)
}
