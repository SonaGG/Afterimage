package gg.sona.recast.core.log

class RecastLogger internal constructor(val name: String) {
    fun debug(message: String) = RecastLog.sink.log(LogLevel.DEBUG, name, message, null)

    fun info(message: String) = RecastLog.sink.log(LogLevel.INFO, name, message, null)

    fun warn(message: String, error: Throwable? = null) = RecastLog.sink.log(LogLevel.WARN, name, message, error)

    fun error(message: String, error: Throwable? = null) = RecastLog.sink.log(LogLevel.ERROR, name, message, error)
}
