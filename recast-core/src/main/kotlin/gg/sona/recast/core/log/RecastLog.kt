package gg.sona.recast.core.log

object RecastLog {
    @Volatile
    var sink: LogSink = ConsoleLogSink

    fun logger(name: String): RecastLogger = RecastLogger(name)
}
