package org.rsmod.content.other.botswarm.report

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import java.util.concurrent.ConcurrentLinkedQueue
import org.slf4j.LoggerFactory

/**
 * Captures ERROR-level log events while a swarm runs. Per-player failures are logged by
 * `GameExceptionLogHandler` as "Error processing <phase> for player: Player(username=..., ...)", so
 * an event is attributed to a bot by its username appearing in the message.
 *
 * Logback appenders may fire on any thread, so events queue here and are drained on the world
 * thread.
 */
class ErrorCapture {
    data class CapturedError(val message: String, val throwable: String?, val stack: List<String>)

    private val queue = ConcurrentLinkedQueue<CapturedError>()

    private val appender =
        object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                if (!event.level.isGreaterOrEqual(Level.ERROR)) return
                if (queue.size >= MAX_QUEUED) return
                val proxy = event.throwableProxy
                queue +=
                    CapturedError(
                        message = event.formattedMessage ?: "",
                        throwable = proxy?.let { "${it.className}: ${it.message}" },
                        stack =
                            proxy?.stackTraceElementProxyArray?.take(STACK_DEPTH)?.map {
                                it.steAsString
                            } ?: emptyList(),
                    )
            }
        }

    fun install() {
        val root = rootLogger() ?: return
        appender.context = root.loggerContext
        appender.name = "botswarm-error-capture"
        appender.start()
        root.addAppender(appender)
    }

    fun uninstall() {
        rootLogger()?.detachAppender(appender)
        appender.stop()
        queue.clear()
    }

    fun drain(): List<CapturedError> {
        if (queue.isEmpty()) return emptyList()
        val out = ArrayList<CapturedError>()
        while (true) out += queue.poll() ?: break
        return out
    }

    private fun rootLogger(): Logger? {
        val factory = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return null
        return factory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
    }

    private companion object {
        const val MAX_QUEUED = 500
        const val STACK_DEPTH = 12
    }
}
