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
import io.github.kloping.qqbot.entities.qqpd.v2.Member
import io.github.kloping.qqbot.entities.qqpd.v2.data.BatchRemoveMembersResult
import io.github.kloping.qqbot.entities.qqpd.v2.data.GroupBotState
import io.github.kloping.qqbot.entities.qqpd.v2.data.GroupInfo
import io.github.kloping.qqbot.entities.qqpd.v2.data.GroupMemberList
import io.github.kloping.qqbot.entities.qqpd.v2.data.GroupMuteSetting
import io.github.kloping.qqbot.entities.qqpd.v2.data.JoinApproval
import io.github.kloping.qqbot.entities.qqpd.v2.data.JoinApprovalStrategyList
import io.github.kloping.qqbot.entities.qqpd.v2.data.JoinRequestList
import io.github.kloping.qqbot.entities.qqpd.v2.data.MemberBlacklist
import io.github.kloping.qqbot.entities.qqpd.v2.data.MemberBlacklistRequest
import io.github.kloping.qqbot.entities.qqpd.v2.data.MemberBlacklistResult
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

    // ------------------------------------------------------------------
    // 群管理接口：直接转交 QClient，使所有平台适配器都具备同一套能力。
    // flowDocs/apidoc.md 中的“接口”部分即对应以下方法。
    // 返回值使用 QQ SDK 数据类；机器人未启动或接口报错时返回 null / false。
    // ------------------------------------------------------------------

    /** 获取群基本信息（群名、简介、分类、标签、成员数）。 */
    fun getGroupInfo(groupOpenId: String): GroupInfo? = QClient.getGroupInfo(groupOpenId)

    /** 获取机器人在指定群内的状态。 */
    fun getGroupBotState(groupOpenId: String): GroupBotState? = QClient.getBotState(groupOpenId)

    /** 查询指定群的禁言状态（全员规则与成员禁言列表）。 */
    fun getGroupMuteSetting(groupOpenId: String): GroupMuteSetting? =
        QClient.getMuteSetting(groupOpenId)

    /** 批量设置群成员禁言（可同时传入多条成员禁言状态）。 */
    fun setGroupMuteSetting(
        groupOpenId: String,
        request: GroupMuteSetting.GroupMuteSettingRequest
    ): Boolean = QClient.setMuteSetting(groupOpenId, request)

    /** 禁言指定群成员，时长单位为秒。 */
    fun muteGroupMember(groupOpenId: String, memberOpenId: String, seconds: Long): Boolean =
        QClient.muteMember(groupOpenId, memberOpenId, seconds)

    /** 禁言指定群成员；`update = true` 用于该成员已有禁言的场景。 */
    fun muteGroupMember(
        groupOpenId: String,
        memberOpenId: String,
        seconds: Long,
        update: Boolean
    ): Boolean = QClient.muteMember(groupOpenId, memberOpenId, seconds, update)

    /** 解除指定群成员的禁言。 */
    fun unmuteGroupMember(groupOpenId: String, memberOpenId: String): Boolean =
        QClient.unmuteMember(groupOpenId, memberOpenId)

    /** 拉取指定群第一页入群申请。 */
    fun getGroupJoinRequestList(groupOpenId: String): JoinRequestList? =
        QClient.getJoinRequestList(groupOpenId)

    /** 拉取指定群入群申请；`limit` 最大 50。 */
    fun getGroupJoinRequestList(
        groupOpenId: String,
        cursor: String?,
        limit: Int?
    ): JoinRequestList? = QClient.getJoinRequestList(groupOpenId, cursor, limit)

    /** 按 [JoinApproval] 审批入群申请（op 为 approve / decline）。 */
    fun approvalGroupJoinRequest(
        groupOpenId: String,
        memberOpenId: String,
        approval: JoinApproval
    ): Boolean = QClient.approvalJoinRequest(groupOpenId, memberOpenId, approval)

    /** 通过指定成员的入群申请。 */
    fun approveGroupJoinRequest(groupOpenId: String, memberOpenId: String): Boolean =
        QClient.approveJoinRequest(groupOpenId, memberOpenId)

    /** 通过指定成员的入群申请，并回传申请 ID。 */
    fun approveGroupJoinRequest(
        groupOpenId: String,
        memberOpenId: String,
        joinRequestId: String?
    ): Boolean = QClient.approveJoinRequest(groupOpenId, memberOpenId, joinRequestId)

    /** 拒绝指定成员的入群申请。 */
    fun declineGroupJoinRequest(groupOpenId: String, memberOpenId: String): Boolean =
        QClient.declineJoinRequest(groupOpenId, memberOpenId)

    /** 拒绝指定成员的入群申请，可选拒绝理由与同时加入群黑名单。 */
    fun declineGroupJoinRequest(
        groupOpenId: String,
        memberOpenId: String,
        joinRequestId: String?,
        rejectReason: String?,
        addToMemberBlacklist: Boolean
    ): Boolean = QClient.declineJoinRequest(
        groupOpenId,
        memberOpenId,
        joinRequestId,
        rejectReason,
        addToMemberBlacklist
    )

    /** 获取群成员列表第一页（该能力仍在 QQ 内邀接入中）。 */
    fun getGroupMembers(groupOpenId: String): GroupMemberList? = QClient.getMembers(groupOpenId)

    /** 获取群成员列表（分页，该能力仍在 QQ 内邀接入中）。 */
    fun getGroupMembers(groupOpenId: String, cursor: String?): GroupMemberList? =
        QClient.getMembers(groupOpenId, cursor)

    /** 获取指定群成员的详细信息。 */
    fun getGroupMember(groupOpenId: String, memberOpenId: String): Member? =
        QClient.getMember(groupOpenId, memberOpenId)

    /** 批量移除群成员（单次最多 20 个），默认不加入群黑名单。 */
    fun batchRemoveGroupMembers(
        groupOpenId: String,
        memberOpenIds: List<String>
    ): BatchRemoveMembersResult? = QClient.batchRemoveMembers(groupOpenId, memberOpenIds)

    /** 批量移除群成员，可选择同时加入群黑名单。 */
    fun batchRemoveGroupMembers(
        groupOpenId: String,
        memberOpenIds: List<String>,
        addToMemberBlacklist: Boolean
    ): BatchRemoveMembersResult? =
        QClient.batchRemoveMembers(groupOpenId, memberOpenIds, addToMemberBlacklist)

    /** 查询群黑名单第一页。 */
    fun getGroupMemberBlacklist(groupOpenId: String): MemberBlacklist? =
        QClient.getMemberBlacklist(groupOpenId)

    /** 查询群黑名单（分页）。 */
    fun getGroupMemberBlacklist(
        groupOpenId: String,
        cursor: String?,
        limit: Int?
    ): MemberBlacklist? = QClient.getMemberBlacklist(groupOpenId, cursor, limit)

    /** 操作群黑名单（op 为 add / del，单次最多 20 个）。 */
    fun operateGroupMemberBlacklist(
        groupOpenId: String,
        request: MemberBlacklistRequest
    ): MemberBlacklistResult? = QClient.operateMemberBlacklist(groupOpenId, request)

    /** 将成员加入群黑名单。 */
    fun addGroupMemberBlacklist(
        groupOpenId: String,
        memberOpenIds: List<String>
    ): MemberBlacklistResult? = QClient.addToMemberBlacklist(groupOpenId, memberOpenIds)

    /** 将成员移出群黑名单。 */
    fun removeGroupMemberBlacklist(
        groupOpenId: String,
        memberOpenIds: List<String>
    ): MemberBlacklistResult? = QClient.removeFromMemberBlacklist(groupOpenId, memberOpenIds)

    /** 查询入群自动审批策略列表（应用级能力）。 */
    fun getGroupJoinApprovalStrategies(): JoinApprovalStrategyList? =
        QClient.getJoinApprovalStrategyList()

    /** 查询入群自动审批策略列表（分页）。 */
    fun getGroupJoinApprovalStrategies(cursor: String?, limit: Int?): JoinApprovalStrategyList? =
        QClient.getJoinApprovalStrategyList(cursor, limit)

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
