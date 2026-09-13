package gg.sona.recast.mc

import gg.sona.recast.core.log.LogLevel
import gg.sona.recast.core.log.LogSink
import org.apache.logging.log4j.LogManager

object Log4jSink : LogSink {
    override fun log(level: LogLevel, logger: String, message: String, error: Throwable?) {
        val target = LogManager.getLogger(logger)
        when (level) {
            LogLevel.DEBUG -> target.debug(message, error)
            LogLevel.INFO -> target.info(message, error)
            LogLevel.WARN -> target.warn(message, error)
            LogLevel.ERROR -> target.error(message, error)
        }
    }
}
