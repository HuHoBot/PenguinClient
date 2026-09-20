package cn.huohuas001.bot.tools

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy
import io.github.kloping.qqbot.utils.LoggerImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * 把 SDK 的 logback 日志接回 HuHoBot 原有的两条通道。
 *
 * 上游 1.5.4-R4 起 SDK 内部改用 logback（由 `StandaloneLogging` 在 `Starter.run()`
 * 时配置控制台输出），原先的 `LoggerImpl` 不再参与输出，因此这里在 logback 根
 * logger 上补两个 appender：
 *
 * 1. [LogSinkAppender]：转发给平台日志（`LoggerImpl.LogSink`，即各服务端的 logger）
 * 2. 按天滚动的日志文件（`HuHoBot.getQqBotLogFilePattern()` 的 `logs/Bot-%s.log`）
 *
 * 控制台输出仍由 SDK 负责，级别/格式可用系统属性 `qqbot.logging.level`、
 * `qqbot.logging.pattern`、`qqbot.logging.color` 调整。
 */
internal object QqBotLogbackBridge {
    /** 平台日志的警告档（LogSink 只预置了错误/调试两档）。 */
    const val WARN_LEVEL = -2

    private const val FILE_PATTERN = "[%thread] %-32.32logger{32} %d{yyyy-MM-dd HH:mm:ss} %-5p: %msg%n"

    /** 日志文件保留天数。 */
    private const val MAX_HISTORY_DAYS = 30

    private var installed = false

    /**
     * 在 QQ 客户端启动完成后调用；重复调用只生效一次。
     *
     * @param logFilePattern 日志文件格式，`%s` 为日期占位符；为 null 或空白时不写文件
     */
    fun install(logFilePattern: String?) {
        if (installed) return
        val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
        val root = context.getLogger(Logger.ROOT_LOGGER_NAME)

        val sinkAppender = LogSinkAppender()
        sinkAppender.context = context
        sinkAppender.start()
        root.addAppender(sinkAppender)

        logFilePattern?.takeIf { it.isNotBlank() }?.let { pattern ->
            root.addAppender(fileAppender(context, pattern))
        }
        installed = true
    }

    /**
     * 按天滚动的文件 appender。
     *
     * 配置里的 `%s` 是旧实现的日期占位符，这里转换为 logback 的 `%d{yyyy-MM-dd}`；
     * 不设置 `file`，当前文件名直接由滚动策略决定（如 `logs/Bot-2026-09-20.log`）。
     */
    private fun fileAppender(context: LoggerContext, pattern: String): RollingFileAppender<ILoggingEvent> {
        val encoder = PatternLayoutEncoder()
        encoder.context = context
        encoder.pattern = FILE_PATTERN
        encoder.start()

        val appender = RollingFileAppender<ILoggingEvent>()
        appender.context = context
        appender.encoder = encoder

        val policy = TimeBasedRollingPolicy<ILoggingEvent>()
        policy.context = context
        policy.setParent(appender)
        policy.fileNamePattern = pattern.replace("%s", "%d{yyyy-MM-dd}")
        policy.maxHistory = MAX_HISTORY_DAYS
        policy.start()

        appender.rollingPolicy = policy
        appender.start()
        return appender
    }

    /** 把 logback 事件转发给宿主平台日志。 */
    private class LogSinkAppender : AppenderBase<ILoggingEvent>() {
        override fun append(eventObject: ILoggingEvent) {
            val sink = LoggerImpl.getLogSink() ?: return
            sink.log(eventObject.formattedMessage, levelOf(eventObject.level))
        }
    }
}

/** logback 级别 → LogSink 级别。 */
private fun levelOf(level: Level): Int = when {
    level.isGreaterOrEqual(Level.ERROR) -> LoggerImpl.LogSink.ERROR_LEVEL
    level.isGreaterOrEqual(Level.WARN) -> QqBotLogbackBridge.WARN_LEVEL
    level.isGreaterOrEqual(Level.INFO) -> 1
    else -> LoggerImpl.LogSink.DEBUG_LEVEL
}
