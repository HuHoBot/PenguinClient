package cn.huohuas001.bot

import cn.huohuas001.bot.addon.Addon
import cn.huohuas001.bot.addon.AddonManager
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
import io.github.kloping.qqbot.http.data.V2MsgData
import io.github.kloping.qqbot.http.data.V2Result

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
            starter.config.code = Intents.PUBLIC_INTENTS.and(Intents.GROUP_INTENTS)
            starter.run()
            starter.registerListenerHost(groupMessageHandler)
            starter.registerListenerHost(InteractionHandler(plugin))
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

    @Synchronized
    fun shutdown() {
        try {
            if (::starter.isInitialized) starter.shutdown()
        } finally {
            QqBotConsoleOutputFilter.uninstall()
        }
    }
}
