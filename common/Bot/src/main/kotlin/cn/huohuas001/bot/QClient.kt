package cn.huohuas001.bot

import cn.huohuas001.bot.addon.Addon
import cn.huohuas001.bot.addon.AddonManager
import cn.huohuas001.bot.events.GroupEventHandler
import cn.huohuas001.bot.events.GroupMessageHandler
import cn.huohuas001.bot.events.InteractionHandler
import cn.huohuas001.bot.events.commands.BaseCommand
import cn.huohuas001.bot.events.commands.CustomCommandRegistry
import cn.huohuas001.bot.events.commands.RegisteredCommand
import cn.huohuas001.bot.provider.BotShared
import cn.huohuas001.bot.tools.QqBotConsoleOutputFilter
import cn.huohuas001.bot.tools.QqBotLogbackBridge
import com.alibaba.fastjson.JSON
import io.github.kloping.qqbot.Starter
import io.github.kloping.qqbot.api.Intents
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.ex.Keyboard
import io.github.kloping.qqbot.entities.ex.Markdown
import io.github.kloping.qqbot.entities.ex.msg.MessageChain
import io.github.kloping.qqbot.entities.qqpd.Channel
import io.github.kloping.qqbot.entities.qqpd.v2.Member
import io.github.kloping.qqbot.entities.qqpd.v2.Mute
import io.github.kloping.qqbot.entities.qqpd.v2.data.BatchRemoveMembersRequest
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
import io.github.kloping.qqbot.entities.qqpd.v2.data.SetMemberMuteState
import io.github.kloping.qqbot.http.GroupBaseV2
import io.github.kloping.qqbot.http.data.V2MsgData
import io.github.kloping.qqbot.http.data.V2Result
import java.time.OffsetDateTime
import java.time.ZoneOffset

object QClient {
    private lateinit var starter: Starter
    private lateinit var groupMessageHandler: GroupMessageHandler

    /**
     * 注册指令处理器,收到群消息后会自动分发
     */
    fun registerCommand(command: BaseCommand) {
        check(::groupMessageHandler.isInitialized) {
            "QQ client has not been launched"
        }
        groupMessageHandler.registerCommand(command)
        syncGroupPanels()
    }

    fun registerCommand(addon: Addon, command: BaseCommand) {
        check(::groupMessageHandler.isInitialized) {
            "QQ client has not been launched"
        }
        AddonManager.register(addon, command.registeredCommands().map { it.copy(source = addon.name) })
        groupMessageHandler.registerCommand(command, addon.name)
        syncGroupPanels()
    }

    /** 是否已经启动过 QQ 客户端（不代表当前连接仍然可用）。 */
    fun isInitialized(): Boolean = ::starter.isInitialized

    @Synchronized
    fun launchClient(appid: String, secret: String, logFilePattern: String? = null) {
        val plugin = BotShared.getPlugin()
        val suppressConsoleOutput = plugin.shouldSuppressQqBotConsoleOutput()
        if (suppressConsoleOutput) {
            QqBotConsoleOutputFilter.install()
        } else {
            QqBotConsoleOutputFilter.uninstall()
        }

        try {
            groupMessageHandler = GroupMessageHandler(plugin)
            starter = Starter(appid, secret)
            // GROUP_MEMBER_EVENT(1<<24) 覆盖群成员进退群与入群申请事件，需要机器人
            // 在群内具备相应管理权限；订阅不被允许时连接会被拒，因此默认不订阅，
            // 由 features.group-member-events 显式开启。
            starter.config.code = if (plugin.isGroupMemberEventsEnabled()) {
                Intents.PUBLIC_INTENTS.and(Intents.GROUP_INTENTS, Intents.GROUP_MEMBER_EVENT)
            } else {
                Intents.PUBLIC_INTENTS.and(Intents.GROUP_INTENTS)
            }
            starter.run()
            starter.registerListenerHost(groupMessageHandler)
            starter.registerListenerHost(InteractionHandler(plugin))
            starter.registerListenerHost(GroupEventHandler(plugin))
            // 上游 1.5.4-R4 起 SDK 改用 logback（StandaloneLogging 自行配置控制台输出），
            // SpringTool 0.7.2-L2 也移除了 APPLICATION.logger；
            // 平台日志转发与按天日志文件由 QqBotLogbackBridge 补回。
            QqBotLogbackBridge.install(logFilePattern)
            syncGroupPanels()
        } catch (error: Exception) {
            if (suppressConsoleOutput) {
                QqBotConsoleOutputFilter.uninstall()
            }
            throw error
        }
    }

    fun syncGroupPanels() {
        if (!::starter.isInitialized || !::groupMessageHandler.isInitialized) return
        val plugin = BotShared.getPlugin()
        val builtInCommands = groupMessageHandler.registeredCommands()
            .filter { plugin.getCommandMenuList()[it.command] != false }
        val customCommands = CustomCommandRegistry.snapshot().filter { it.pushMenu }.map {
            RegisteredCommand(
                command = it.key,
                describe = "自定义命令",
                onlyAdmin = it.permission > 0
            )
        }
        MenuManager.syncGroupPanels(
            starter = starter,
            groupOpenIds = plugin.getGroupOpenIdList(),
            builtInCommands = builtInCommands,
            customCommands = customCommands
        )
    }

    /** 将游戏聊天按配置格式发送到 bot.groups 中的 QQ 群。 */
    fun broadcastGameMessage(playerName: String, message: String) {
        if (!::starter.isInitialized) return
        val plugin = BotShared.getPlugin()
        val format = plugin.getChatFormat()
        if (!format.postChat) return
        if (!message.startsWith(format.startWith)) return

        val messageWithoutPrefix = message.removePrefix(format.startWith)
        val filtered = plugin.auditText(messageWithoutPrefix)
        val content = plugin.formatGameMessage(playerName, filtered)
        val payload = V2MsgData().setContent(content)
        plugin.getGroupOpenIdList().forEach { groupId ->
            try {
                starter.bot.groupBaseV2.send(groupId, JSON.toJSONString(payload), Channel.SEND_MESSAGE_HEADERS)
            } catch (e: Exception) {
                plugin.log_error("向QQ群 $groupId 转发游戏聊天失败: ${e.message}")
            }
        }
    }

    /** 按配置向所有 QQ 群发送玩家进服通知。 */
    fun broadcastPlayerJoin(playerName: String) {
        if (!::starter.isInitialized) return
        val plugin = BotShared.getPlugin()
        if (!plugin.getPlayerEventFormat().joinEnabled) return
        sendTextToGroups(plugin.formatPlayerJoinMessage(playerName), "发送玩家进服通知")
    }

    /** 按配置向所有 QQ 群发送玩家退服通知。 */
    fun broadcastPlayerQuit(playerName: String) {
        if (!::starter.isInitialized) return
        val plugin = BotShared.getPlugin()
        if (!plugin.getPlayerEventFormat().quitEnabled) return
        sendTextToGroups(plugin.formatPlayerQuitMessage(playerName), "发送玩家退服通知")
    }

    private fun sendTextToGroups(content: String, action: String) {
        if (content.isBlank()) return
        val plugin = BotShared.getPlugin()
        val payload = V2MsgData().setContent(content)
        plugin.getGroupOpenIdList().forEach { groupId ->
            try {
                starter.bot.groupBaseV2.send(groupId, JSON.toJSONString(payload), Channel.SEND_MESSAGE_HEADERS)
            } catch (e: Exception) {
                plugin.log_error("向QQ群 $groupId ${action}失败: ${e.message}")
            }
        }
    }

    /** 向 bot.groups 中配置的所有 QQ 群发送普通文本。 */
    fun sendText(text: String) {
        if (!::starter.isInitialized) {
            BotShared.getPlugin().log_warning("QQ 机器人未启动，无法发送文本")
            return
        }
        sendTextToGroups(text, "发送文本")
    }

    /** 主动向指定 QQ 群发送普通文本。 */
    fun sendText(groupOpenId: String, text: String): Boolean {
        val plugin = BotShared.getPlugin()
        if (!::starter.isInitialized) {
            plugin.log_warning("QQ 机器人未启动，无法发送文本")
            return false
        }
        if (groupOpenId.isBlank() || text.isBlank()) return false

        val payload = V2MsgData().setContent(text)
        return try {
            starter.bot.groupBaseV2.send(
                groupOpenId,
                JSON.toJSONString(payload),
                Channel.SEND_MESSAGE_HEADERS
            )
            true
        } catch (error: Exception) {
            plugin.log_error("向QQ群 $groupOpenId 发送文本失败: ${error.message}")
            false
        }
    }

    /** 向 bot.groups 中配置的所有 QQ 群发送自定义 Markdown。 */
    fun sendMarkdown(markdownContent: String, keyboard: Keyboard? = null) {
        val plugin = BotShared.getPlugin()
        if (!::starter.isInitialized) {
            plugin.log_warning("QQ 机器人未启动，无法发送 Markdown")
            return
        }

        val markdown = Markdown().setContent(markdownContent)
        val payload = V2MsgData()
            .setContent(markdownContent)
            .setMsg_type(2)
            .setMarkdown(markdown)
        if (keyboard != null) {
            markdown.setKeyboard(keyboard)
            payload.setKeyboard(keyboard)
        }

        plugin.getGroupOpenIdList().forEach { groupId ->
            try {
                starter.bot.groupBaseV2.send(groupId, JSON.toJSONString(payload), Channel.SEND_MESSAGE_HEADERS)
            } catch (e: Exception) {
                plugin.log_error("向QQ群 $groupId 发送 Markdown 失败: ${e.message}")
            }
        }

    }

    /** 主动向指定 QQ 群发送 Markdown，可选附带消息键盘。 */
    fun sendMarkdown(
        groupOpenId: String,
        markdownContent: String,
        keyboard: Keyboard? = null
    ): Boolean {
        val plugin = BotShared.getPlugin()
        if (!::starter.isInitialized) {
            plugin.log_warning("QQ 机器人未启动，无法发送 Markdown")
            return false
        }
        if (groupOpenId.isBlank() || markdownContent.isBlank()) return false

        val markdown = Markdown().setContent(markdownContent)
        val payload = V2MsgData()
            .setContent(markdownContent)
            .setMsg_type(2)
            .setMarkdown(markdown)
        if (keyboard != null) {
            markdown.setKeyboard(keyboard)
            payload.setKeyboard(keyboard)
        }

        return try {
            starter.bot.groupBaseV2.send(
                groupOpenId,
                JSON.toJSONString(payload),
                Channel.SEND_MESSAGE_HEADERS
            )
            true
        } catch (error: Exception) {
            plugin.log_error("向QQ群 $groupOpenId 发送 Markdown 失败: ${error.message}")
            false
        }
    }

    /** 回复指定群消息并发送自定义 Markdown，成功返回新消息 ID，失败返回 null。 */
    fun replyMarkdown(
        event: GroupMessageEvent,
        markdownContent: String,
        keyboard: Keyboard? = null
    ): String? = replyMarkdown(
        groupOpenId = event.groupOpenId ?: event.groupId,
        messageId = event.rawMessage.id.orEmpty(),
        messageSequence = event.msgSeq,
        markdownContent = markdownContent,
        keyboard = keyboard
    )

    /**
     * 使用消息快照字段回复 Markdown，避免 Bukkit 插件依赖 QQ SDK 事件对象。
     *
     * @return 发送成功时为 QQ 返回的新消息 ID，失败为 null。
     */
    fun replyMarkdown(
        groupOpenId: String,
        messageId: String,
        messageSequence: Int,
        markdownContent: String,
        keyboard: Keyboard? = null
    ): String? {
        val plugin = BotShared.getPlugin()
        if (!::starter.isInitialized) {
            plugin.log_warning("QQ 机器人未启动，无法回复 Markdown")
            return null
        }
        if (markdownContent.isBlank()) return null

        val markdown = Markdown().setContent(markdownContent)
        if (keyboard != null) {
            markdown.setKeyboard(keyboard)
        }

        val payload = V2MsgData()
            .setContent(markdownContent)
            .setMsg_type(2)
            .setMarkdown(markdown)
            .setMsg_id(messageId)
            .setMsg_seq(messageSequence)
        if (keyboard != null) {
            payload.setKeyboard(keyboard)
        }

        return sendReply("回复 Markdown 失败") {
            starter.bot.groupBaseV2.send(
                groupOpenId,
                JSON.toJSONString(payload),
                Channel.SEND_MESSAGE_HEADERS
            )
        }
    }

    /** 回复指定群消息并发送普通文本，成功返回新消息 ID，失败返回 null。 */
    fun replyText(event: GroupMessageEvent, text: String): String? = replyText(
        groupOpenId = event.groupOpenId ?: event.groupId,
        messageId = event.rawMessage.id.orEmpty(),
        messageSequence = event.msgSeq,
        text = text
    )

    /**
     * 使用消息快照字段回复普通文本。
     *
     * @return 发送成功时为 QQ 返回的新消息 ID，失败为 null。
     */
    fun replyText(
        groupOpenId: String,
        messageId: String,
        messageSequence: Int,
        text: String
    ): String? {
        val plugin = BotShared.getPlugin()
        if (!::starter.isInitialized) {
            plugin.log_warning("QQ 机器人未启动，无法回复文本")
            return null
        }
        if (text.isBlank()) return null
        val payload = V2MsgData()
            .setContent(text)
            .setMsg_id(messageId)
            .setMsg_seq(messageSequence)
        return sendReply("回复文本失败") {
            starter.bot.groupBaseV2.send(
                groupOpenId,
                JSON.toJSONString(payload),
                Channel.SEND_MESSAGE_HEADERS
            )
        }
    }

    /**
     * 执行一次回复发送，并从 QQ 接口结果中取出新消息 ID。
     *
     * QQ 接口报错会抛出 [Exception]；接口返回但缺少消息 ID 时同样视为发送失败。
     * 两种情况都记录 [failure] 日志并返回 null。
     */
    private fun sendReply(failure: String, send: () -> V2Result?): String? = try {
        val sentMessageId = send()?.id?.takeIf { it.isNotBlank() }
        if (sentMessageId == null) {
            BotShared.getPlugin().log_error("$failure: QQ 接口未返回消息 ID")
        }
        sentMessageId
    } catch (error: Exception) {
        BotShared.getPlugin().log_error("$failure: ${error.message}")
        null
    }

    /** 回复指定群消息，同时发送文本和网络图片。 */
    fun replyWithImg(event: GroupMessageEvent, text: String, imgUrl: String): Boolean {
        val plugin = BotShared.getPlugin()
        if (!::starter.isInitialized) {
            plugin.log_warning("QQ 机器人未启动，无法回复图片消息")
            return false
        }
        if (imgUrl.isBlank()) {
            plugin.log_warning("图片 URL 为空，无法回复图片消息")
            return false
        }

        val message = MessageChain()
            .text(text.ifBlank { "[图片]" })
            .image(imgUrl)

        try {
            // MessageChain 会先上传网络图片，再携带原消息的 msg_id 发送文本和图片。
            event.sendMessage(message)
            return true
        } catch (error: Exception) {
            plugin.log_error("回复图片消息失败: ${error.message}")
            return false
        }
    }

    /**
     * 响应 QQ 互动事件（消息按钮 / 快捷菜单回调）。
     *
     * QQ 要求在收到互动事件后 5 秒内响应，否则客户端会提示操作失败。
     *
     * @param interactionId 互动事件 ID
     * @param code 0 成功，1 操作失败，2 操作频繁，3 重复操作，4 没有权限，5 仅管理员操作
     * @return 是否成功提交响应
     */
    fun respondInteraction(interactionId: String, code: Int): Boolean {
        val plugin = BotShared.getPlugin()
        if (!::starter.isInitialized) {
            plugin.log_warning("QQ 机器人未启动，无法响应互动事件")
            return false
        }
        if (interactionId.isBlank()) return false
        return starter.bot.restApi.respondInteraction(interactionId, code)
    }

    /**
     * 撤回指定 QQ 群消息。
     *
     * 发送超过 2 分钟的消息不可撤回；机器人为群管理员时可撤回群成员的消息，
     * 普通成员只能撤回机器人自己发送的消息。
     *
     * @param groupOpenId 群的 openid
     * @param messageId 消息 ID（消息发送响应里的 id，或群消息事件的 d.id）
     * @return 是否撤回成功
     */
    fun recallMessage(groupOpenId: String, messageId: String): Boolean {
        val plugin = BotShared.getPlugin()
        if (!::starter.isInitialized) {
            plugin.log_warning("QQ 机器人未启动，无法撤回消息")
            return false
        }
        if (groupOpenId.isBlank() || messageId.isBlank()) return false
        return starter.bot.restApi.recallMessage(groupOpenId, messageId)
    }

    // ------------------------------------------------------------------
    // 群管理接口（QQ 开放平台 v2 群 API，对应 flowDocs/apidoc.md 的“接口”部分）
    //
    // 所有方法在机器人未启动、参数不合法或接口报错时返回 null / false，
    // 并保持与其它 QClient 方法一致的日志行为，避免调用方处理异常。
    // ------------------------------------------------------------------

    /** 获取群基本信息（群名、简介、分类、标签、成员数）；失败返回 null。 */
    fun getGroupInfo(groupOpenId: String): GroupInfo? =
        groupApi("获取群基本信息", groupOpenId) { it.getGroupInfo(groupOpenId) }

    /** 获取机器人在指定群内的状态（入群时间、角色、主动消息开关等）；失败返回 null。 */
    fun getBotState(groupOpenId: String): GroupBotState? =
        groupApi("获取机器人群内状态", groupOpenId) { it.getBotState(groupOpenId) }

    /** 查询指定群的禁言状态（全员禁言规则与成员禁言列表）；失败返回 null。 */
    fun getMuteSetting(groupOpenId: String): GroupMuteSetting? =
        groupApi("查询群禁言状态", groupOpenId) { it.getMuteSetting(groupOpenId) }

    /** 批量设置群成员禁言（可同时传入多条成员禁言状态）；成功返回 true。 */
    fun setMuteSetting(
        groupOpenId: String,
        request: GroupMuteSetting.GroupMuteSettingRequest
    ): Boolean = groupAction("设置群成员禁言", groupOpenId) { it.setMuteSetting(groupOpenId, request) }

    /**
     * 禁言指定群成员，时长单位为秒。
     *
     * @param update 该成员已有禁言时传 true，走 `update` 操作；否则使用 `add`
     */
    fun muteMember(
        groupOpenId: String,
        memberOpenId: String,
        seconds: Long,
        update: Boolean = false
    ): Boolean {
        if (memberOpenId.isBlank()) return false
        val operation = if (update) Mute.Update else Mute.Add
        return setMuteSetting(groupOpenId, memberMuteRequest(memberOpenId, operation, seconds))
    }

    /** 解除指定群成员的禁言。 */
    fun unmuteMember(groupOpenId: String, memberOpenId: String): Boolean =
        if (memberOpenId.isBlank()) false
        else setMuteSetting(groupOpenId, memberMuteRequest(memberOpenId, null, 0))

    /**
     * 拉取指定群的入群申请列表。
     *
     * @param cursor 分页游标，首次请求传 null/空
     * @param limit 单页数量，最大 50
     */
    fun getJoinRequestList(
        groupOpenId: String,
        cursor: String? = null,
        limit: Int? = null
    ): JoinRequestList? =
        groupApi("拉取入群申请列表", groupOpenId) { it.getJoinRequestList(groupOpenId, cursor, limit) }

    /** 按 [JoinApproval] 审批入群申请（op 为 approve / decline）；成功返回 true。 */
    fun approvalJoinRequest(
        groupOpenId: String,
        memberOpenId: String,
        approval: JoinApproval
    ): Boolean =
        if (memberOpenId.isBlank()) false
        else groupAction("审批入群申请", groupOpenId) {
            it.approvalJoinRequest(groupOpenId, memberOpenId, approval)
        }

    /** 通过指定成员的入群申请；成功返回 true。 */
    fun approveJoinRequest(
        groupOpenId: String,
        memberOpenId: String,
        joinRequestId: String? = null
    ): Boolean = approvalJoinRequest(
        groupOpenId,
        memberOpenId,
        JoinApproval().setOp(JOIN_APPROVE_OP).setJoinRequestId(joinRequestId)
    )

    /**
     * 拒绝指定成员的入群申请；成功返回 true。
     *
     * @param rejectReason 拒绝理由，可空
     * @param addToMemberBlacklist 是否同时加入群黑名单
     */
    fun declineJoinRequest(
        groupOpenId: String,
        memberOpenId: String,
        joinRequestId: String? = null,
        rejectReason: String? = null,
        addToMemberBlacklist: Boolean = false
    ): Boolean = approvalJoinRequest(
        groupOpenId,
        memberOpenId,
        JoinApproval()
            .setOp(JOIN_DECLINE_OP)
            .setJoinRequestId(joinRequestId)
            .setRejectReason(rejectReason)
            .setAddToMemberBlacklist(addToMemberBlacklist)
    )

    /**
     * 获取群成员列表（分页）。
     *
     * 该能力仍在 QQ 内邀接入中，未开通时会报错并返回 null。
     *
     * @param cursor 分页游标，首次请求传 null/空
     */
    fun getMembers(groupOpenId: String, cursor: String? = null): GroupMemberList? =
        groupApi("获取群成员列表", groupOpenId) { it.getMembers(groupOpenId, cursor.orEmpty()) }

    /** 获取指定群成员的详细信息；失败返回 null。 */
    fun getMember(groupOpenId: String, memberOpenId: String): Member? {
        if (memberOpenId.isBlank()) return null
        return groupApi("获取群成员信息", groupOpenId) { it.getMember(groupOpenId, memberOpenId) }
    }

    /** 批量移除群成员（单次最多 20 个）；失败返回 null。 */
    fun batchRemoveMembers(
        groupOpenId: String,
        memberOpenIds: List<String>,
        addToMemberBlacklist: Boolean = false
    ): BatchRemoveMembersResult? {
        if (memberOpenIds.isEmpty()) return null
        return groupApi("批量移除群成员", groupOpenId) {
            it.batchRemoveMembers(
                groupOpenId,
                BatchRemoveMembersRequest()
                    .setMemberOpenids(memberOpenIds)
                    .setAddToMemberBlacklist(addToMemberBlacklist)
            )
        }
    }

    /** 查询群黑名单（分页）；失败返回 null。 */
    fun getMemberBlacklist(
        groupOpenId: String,
        cursor: String? = null,
        limit: Int? = null
    ): MemberBlacklist? = groupApi("查询群黑名单", groupOpenId) {
        it.getMemberBlacklist(groupOpenId, cursor.orEmpty(), limit ?: DEFAULT_PAGE_LIMIT)
    }

    /** 操作群黑名单（op 为 add / del，单次最多 20 个）；失败返回 null。 */
    fun operateMemberBlacklist(
        groupOpenId: String,
        request: MemberBlacklistRequest
    ): MemberBlacklistResult? =
        groupApi("操作群黑名单", groupOpenId) { it.operateMemberBlacklist(groupOpenId, request) }

    /** 将成员加入群黑名单；失败返回 null。 */
    fun addToMemberBlacklist(
        groupOpenId: String,
        memberOpenIds: List<String>
    ): MemberBlacklistResult? {
        if (memberOpenIds.isEmpty()) return null
        return operateMemberBlacklist(
            groupOpenId,
            MemberBlacklistRequest().setOp(BLACKLIST_ADD_OP).setMemberOpenids(memberOpenIds)
        )
    }

    /** 将成员移出群黑名单；失败返回 null。 */
    fun removeFromMemberBlacklist(
        groupOpenId: String,
        memberOpenIds: List<String>
    ): MemberBlacklistResult? {
        if (memberOpenIds.isEmpty()) return null
        return operateMemberBlacklist(
            groupOpenId,
            MemberBlacklistRequest().setOp(BLACKLIST_DEL_OP).setMemberOpenids(memberOpenIds)
        )
    }

    /** 查询入群自动审批策略列表（应用级能力，与具体群无关）；失败返回 null。 */
    fun getJoinApprovalStrategyList(
        cursor: String? = null,
        limit: Int? = null
    ): JoinApprovalStrategyList? = groupApi("查询入群自动审批策略列表", null) {
        it.getJoinApprovalStrategyList(cursor, limit)
    }

    /** 构造单成员禁言请求；[operation] 为 null 时表示解除禁言。 */
    private fun memberMuteRequest(
        memberOpenId: String,
        operation: Mute?,
        seconds: Long
    ): GroupMuteSetting.GroupMuteSettingRequest =
        GroupMuteSetting.GroupMuteSettingRequest().setMembers(
            listOf(
                SetMemberMuteState()
                    .setOp(operation?.value ?: UNMUTE_OP)
                    .setMemberOpenid(memberOpenId)
                    .setMuteExpireAt(
                        if (operation == null) ""
                        else OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(seconds).toString()
                    )
            )
        )

    /** 执行一次返回数据的群接口调用；[groupOpenId] 为 null 时不校验群号。 */
    private fun <T> groupApi(action: String, groupOpenId: String?, block: (GroupBaseV2) -> T?): T? {
        val plugin = BotShared.getPlugin()
        if (groupOpenId != null && groupOpenId.isBlank()) return null
        if (!::starter.isInitialized) {
            plugin.log_warning("QQ 机器人未启动，无法$action")
            return null
        }
        return try {
            block(starter.bot.groupBaseV2)
        } catch (error: Exception) {
            plugin.log_error("$action 失败: ${error.message}")
            null
        }
    }

    /** 执行一次只关心成功与否的群接口调用。 */
    private fun groupAction(action: String, groupOpenId: String, block: (GroupBaseV2) -> Unit): Boolean {
        val plugin = BotShared.getPlugin()
        if (groupOpenId.isBlank()) return false
        if (!::starter.isInitialized) {
            plugin.log_warning("QQ 机器人未启动，无法$action")
            return false
        }
        return try {
            block(starter.bot.groupBaseV2)
            true
        } catch (error: Exception) {
            plugin.log_error("$action 失败: ${error.message}")
            false
        }
    }

    private const val DEFAULT_PAGE_LIMIT = 20
    private const val UNMUTE_OP = "del"
    private const val JOIN_APPROVE_OP = "approve"
    private const val JOIN_DECLINE_OP = "decline"
    private const val BLACKLIST_ADD_OP = "add"
    private const val BLACKLIST_DEL_OP = "del"

    @Synchronized
    fun shutdown() {
        try {
            if (::starter.isInitialized) starter.shutdown()
        } finally {
            QqBotConsoleOutputFilter.uninstall()
        }
    }
}
