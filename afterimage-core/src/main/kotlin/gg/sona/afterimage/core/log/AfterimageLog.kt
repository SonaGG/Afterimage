package gg.sona.afterimage.core.log

object AfterimageLog {
    @Volatile
    var sink: LogSink = ConsoleLogSink

    fun logger(name: String): AfterimageLogger = AfterimageLogger(name)
}
