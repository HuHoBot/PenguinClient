package cn.huohuas001.bot

import cn.huohuas001.bot.events.commands.CustomCommandRegistry
import cn.huohuas001.bot.events.commands.SensitiveFilter
import cn.huohuas001.bot.provider.*
import cn.huohuas001.bot.qr.QrCredentials
import cn.huohuas001.bot.qr.QrLoginManager
import cn.huohuas001.bot.state.CommandRepositories
import cn.huohuas001.bot.tools.QqBotLogbackBridge
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.ex.Keyboard
import io.github.kloping.qqbot.utils.LoggerImpl
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.util.concurrent.CompletableFuture

/**
 * 所有服务器端实现共享的 HuHoBot 基类接口。
 *
 * 平台端只需实现日志、配置、调度、广播和 [createCommandExecutor] 等原语；
 * 配置状态、自定义命令解析及敏感词审核由这里统一提供。
 */
interface HuHoBot : LoggerProvider, ConfigProvider, CommandProvider, SchedulerProvider, MessageProvider {

    /** QQ 开放平台 AppID；留空时进入扫码绑定流程。 */
    fun getBotAppId(): String

    /** QQ 开放平台 Secret；留空时进入扫码绑定流程。 */
    fun getBotSecret(): String

    /**
     * 扫码绑定成功后把 AppID 与 Secret 写回平台配置文件，并重新加载平台配置。
     *
     * 平台未实现时返回 false：公共运行时仍会用内存中的凭据连接，但会提示用户手动填写。
     */
    fun saveBotCredentials(appId: String, secret: String): Boolean = false

    /** 创建当前服务端平台的原生命令执行器。 */
    fun createCommandExecutor(): HExecution

    /** 重读当前适配器配置，并刷新公共运行时配置。 */
    fun reloadPluginConfig()

    /** 查询指定群内 OpenID 已认证的 QQ 号；未认证时返回 null。 */
    fun getAuthenticatedQq(groupOpenId: String, openId: String): String? =
        CommandRepositories.authentication.getBoundQq(groupOpenId, openId)

    /** 向配置中的所有 QQ 群发送自定义 Markdown。 */
    override fun sendMarkdown(markdownContent: String, keyboard: Keyboard?) {
        QClient.sendMarkdown(markdownContent, keyboard)
    }

    /** 向配置中的所有 QQ 群发送普通文本。 */
    override fun sendText(text: String) {
        QClient.sendText(text)
    }

    /** 回复触发消息所在的 QQ 群，发送普通文本，成功返回新消息 ID，失败返回 null。 */
    override fun replyText(event: GroupMessageEvent, text: String): String? =
        QClient.replyText(event, text)

    /** 回复触发消息所在的 QQ 群，发送自定义 Markdown，成功返回新消息 ID，失败返回 null。 */
    override fun replyMarkdown(
        event: GroupMessageEvent,
        markdownContent: String,
        keyboard: Keyboard?
    ): String? = QClient.replyMarkdown(event, markdownContent, keyboard)

    /** 回复触发消息所在的 QQ 群，同时发送文本和网络图片。 */
    override fun replyWithImg(
        event: GroupMessageEvent,
        text: String,
        imgUrl: String
    ): Boolean = QClient.replyWithImg(event, text, imgUrl)

    /** 撤回指定 QQ 群消息，成功返回 true。 */
    override fun recallMessage(groupOpenId: String, messageId: String): Boolean =
        QClient.recallMessage(groupOpenId, messageId)

    /**
     * QQ SDK 的按日日志文件格式。
     * 默认放到当前客户端配置目录的 logs 下，仍允许平台覆盖或返回 null 禁用文件日志。
     */
    fun getQqBotLogFilePattern(): String? =
        getConfigFile()?.parentFile?.resolve("logs/Bot-%s.log")?.path

    /**
     * 平台启动时调用：注册平台实例、初始化公共状态并异步启动 QQ 客户端。
     */
    fun initializeRuntime() {
        BotShared.setInstance(this)
        val logger = this
        LoggerImpl.setLogSink(object : LoggerImpl.LogSink {
            override fun log(message: String, level: Int) {
                when (level) {
                    LoggerImpl.LogSink.ERROR_LEVEL -> logger.log_error(message)
                    QqBotLogbackBridge.WARN_LEVEL -> logger.log_warning(message)
                    LoggerImpl.LogSink.DEBUG_LEVEL -> logger.log_debug(message)
                    else -> logger.log_info(message)
                }
            }
        })
        CommandRepositories.initialize(getConfigFile()?.parentFile)
        reloadRuntimeConfig()
        launchQqClient()
    }

    /** 平台停止时调用，释放 SDK 日志桥接和公共运行时资源。 */
    fun shutdownRuntime() {
        QrLoginManager.cancel()
        try {
            QClient.shutdown()
        } finally {
            LoggerImpl.clearLogSink()
        }
    }

    /** 配置重载后调用，使公共自定义命令表立即更新。 */
    fun reloadRuntimeConfig() {
        initializeMarkdownTemplates()
        CustomCommandRegistry.replace(getCustomCommands())
        QClient.syncGroupPanels()
    }

    /** 创建 Markdown 目录，并补充不存在的内置模板；不会覆盖用户已经编辑的文件。 */
    fun initializeMarkdownTemplates() {
        val configDirectory = getConfigFile()?.absoluteFile?.parentFile
        if (configDirectory == null) {
            log_warning("无法确定插件配置目录，未初始化 Markdown 模板")
            return
        }

        val markdownDirectory = configDirectory.resolve("Markdown")
        if (!markdownDirectory.isDirectory && !markdownDirectory.mkdirs()) {
            log_warning("无法创建 Markdown 目录: ${markdownDirectory.path}")
            return
        }

        DEFAULT_MARKDOWN_TEMPLATES.forEach { (fileName, resourcePath) ->
            val target = markdownDirectory.resolve(fileName)
            if (target.exists()) return@forEach

            val resource = HuHoBot::class.java.classLoader.getResourceAsStream(resourcePath)
            if (resource == null) {
                log_warning("找不到内置 Markdown 模板资源: $resourcePath")
                return@forEach
            }

            try {
                resource.use { Files.copy(it, target.toPath()) }
                log_info("已初始化 Markdown 模板: ${target.path}")
            } catch (_: FileAlreadyExistsException) {
                // 另一个初始化流程已经创建了文件，保留现有内容。
            } catch (error: Exception) {
                log_warning("初始化 Markdown 模板 ${target.path} 失败: ${error.message}")
            }
        }
    }

    /** 使用当前平台的命令执行器执行原生命令。 */
    override fun dispatchCommand(command: String): CompletableFuture<HExecution> =
        createCommandExecutor().execute(command.removePrefix("/"))

    /**
     * 异步启动 QQ 客户端，避免阻塞各服务端平台的主线程。
     *
     * 只要 `bot.app-id` 或 `bot.secret` 有一个为空，就改为进入扫码绑定流程：
     * 控制台输出二维码 → 手机 QQ 扫码授权 → 自动写回两个字段并重新连接。
     */
    fun launchQqClient() {
        val appId = getBotAppId().trim()
        val secret = getBotSecret().trim()
        if (appId.isEmpty() || secret.isEmpty()) {
            startQrLogin()
            return
        }

        startQqClient(appId, secret)
    }

    /** 用给定凭据（重新）启动 QQ 客户端。 */
    private fun startQqClient(appId: String, secret: String) {
        submitAsync {
            try {
                // 扫码绑定完成后可能已有旧连接（例如热重载），先释放再重新建立。
                QClient.shutdown()
                QClient.launchClient(appId, secret, getQqBotLogFilePattern())
            } catch (error: Exception) {
                log_error("QQ 机器人启动失败: ${error.message}")
            }
        }
    }

    /** `bot.app-id` / `bot.secret` 缺失时输出二维码等待扫码绑定。 */
    private fun startQrLogin() {
        log_warning("检测到 bot.app-id 或 bot.secret 为空，QQ 机器人未启动，改为输出二维码等待扫码绑定")

        val started = QrLoginManager.start(this) { credentials -> onQrCredentials(credentials) }
        if (!started) {
            log_warning("扫码绑定流程已在运行中，本次不再重复输出二维码")
        }
    }

    /** 扫码成功：写回配置文件，并用新凭据重新连接 QQ 机器人。 */
    private fun onQrCredentials(credentials: QrCredentials) {
        val openidSuffix = credentials.userOpenid?.let { "，openid=$it" }.orEmpty()
        log_info("扫码绑定成功: appId=${credentials.appId}$openidSuffix")

        val saved = try {
            saveBotCredentials(credentials.appId, credentials.appSecret)
        } catch (error: Exception) {
            log_error("写入 bot.app-id / bot.secret 失败: ${error.message}")
            false
        }

        if (saved) {
            log_info("已自动写入配置文件 bot.app-id 与 bot.secret")
        } else {
            log_warning("未能写入配置文件，本次仅在内存中使用扫码得到的凭据，重启后需要重新扫码")
        }

        reloadRuntimeConfig()
        startQqClient(credentials.appId, credentials.appSecret)
    }

    /**
     * 统一命令入口。
     *
     * 普通服务器命令直接交给平台；`huhobot run/adminrun` 会先解析
     * `custom-commands`，完成权限和占位符替换后再交给平台执行。
     */
    fun sendCommand(command: String): CompletableFuture<HExecution> {
        val resolved = CustomCommandRegistry.resolve(command)
        if (resolved.error != null) {
            return CompletableFuture.completedFuture(TextExecution(resolved.error, this))
        }
        return dispatchCommand(resolved.command!!)
    }

    /** 统一执行正则过滤、本地敏感词首检和可选 AI 二审。 */
    fun auditText(text: String): String = SensitiveFilter.filter(
        value = filterText(text),
        baseUrl = getAuditBaseUrl(),
        apiKey = getAuditApiKey(),
        model = getAuditModel(),
        words = getSensitiveWords()
    )

    fun getOnlineList(): List<String>
}

private val DEFAULT_MARKDOWN_TEMPLATES = mapOf(
    "online.md" to "Markdown/online.md",
    "motd.md" to "Markdown/motd.md"
)

private class TextExecution(
    private val text: String,
    private val bot: HuHoBot
) : HExecution {
    override fun getRawString(): String = text
    override fun execute(command: String): CompletableFuture<HExecution> = bot.sendCommand(command)
}
