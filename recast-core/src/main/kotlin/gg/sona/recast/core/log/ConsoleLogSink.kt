package gg.sona.recast.core.log

object ConsoleLogSink : LogSink {
    override fun log(level: LogLevel, logger: String, message: String, error: Throwable?) {
        val stream = if (level == LogLevel.ERROR || level == LogLevel.WARN) System.err else System.out
        stream.println("[$level] [$logger] $message")
        error?.printStackTrace(stream)
    }
}
