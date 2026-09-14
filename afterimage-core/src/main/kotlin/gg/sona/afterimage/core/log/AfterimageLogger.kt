package gg.sona.afterimage.core.log

class AfterimageLogger internal constructor(val name: String) {
    fun debug(message: String) = AfterimageLog.sink.log(LogLevel.DEBUG, name, message, null)

    fun info(message: String) = AfterimageLog.sink.log(LogLevel.INFO, name, message, null)

    fun warn(message: String, error: Throwable? = null) = AfterimageLog.sink.log(LogLevel.WARN, name, message, error)

    fun error(message: String, error: Throwable? = null) = AfterimageLog.sink.log(LogLevel.ERROR, name, message, error)
}
