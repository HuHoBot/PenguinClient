package cn.huohuas001.bot

import cn.huohuas001.bot.events.GroupMessageHandler
import cn.huohuas001.bot.events.commands.BaseCommand
import cn.huohuas001.bot.events.commands.CustomCommandRegistry
import cn.huohuas001.bot.events.commands.RegisteredCommand
import cn.huohuas001.bot.provider.BotShared
import cn.huohuas001.bot.tools.QqBotConsoleOutputFilter
import com.alibaba.fastjson.JSON
import io.github.kloping.qqbot.Starter
import io.github.kloping.qqbot.api.Intents
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.ex.Keyboard
import io.github.kloping.qqbot.entities.ex.Markdown
import io.github.kloping.qqbot.entities.ex.msg.MessageChain
import io.github.kloping.qqbot.entities.qqpd.Channel
import io.github.kloping.qqbot.http.data.V2MsgData

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
            starter = Starter(appid, "", secret)
            starter.config.code = Intents.PUBLIC_INTENTS.and(Intents.GROUP_INTENTS)
            starter.run()
            starter.registerListenerHost(groupMessageHandler)
            starter.APPLICATION.logger.setLogLevel(1)
            starter.APPLICATION.logger.setOutFile(logFilePattern)
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

    /** 回复指定群消息并发送自定义 Markdown。 */
    fun replyMarkdown(
        event: GroupMessageEvent,
        markdownContent: String,
        keyboard: Keyboard? = null
    ): Boolean = replyMarkdown(
        groupOpenId = event.groupOpenId ?: event.groupId,
        messageId = event.rawMessage.id.orEmpty(),
        messageSequence = event.msgSeq,
        markdownContent = markdownContent,
        keyboard = keyboard
    )

    /** 使用消息快照字段回复 Markdown，避免 Bukkit 插件依赖 QQ SDK 事件对象。 */
    fun replyMarkdown(
        groupOpenId: String,
        messageId: String,
        messageSequence: Int,
        markdownContent: String,
        keyboard: Keyboard? = null
    ): Boolean {
        val plugin = BotShared.getPlugin()
        if (!::starter.isInitialized) {
            plugin.log_warning("QQ 机器人未启动，无法回复 Markdown")
            return false
        }
        if (markdownContent.isBlank()) return false

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

        try {
            starter.bot.groupBaseV2.send(
                groupOpenId,
                JSON.toJSONString(payload),
                Channel.SEND_MESSAGE_HEADERS
            )
            return true
        } catch (error: Exception) {
            plugin.log_error("回复 Markdown 失败: ${error.message}")
            return false
        }
    }

    /** 回复指定群消息并发送普通文本。 */
    fun replyText(event: GroupMessageEvent, text: String): Boolean = replyText(
        groupOpenId = event.groupOpenId ?: event.groupId,
        messageId = event.rawMessage.id.orEmpty(),
        messageSequence = event.msgSeq,
        text = text
    )

    /** 使用消息快照字段回复普通文本。 */
    fun replyText(
        groupOpenId: String,
        messageId: String,
        messageSequence: Int,
        text: String
    ): Boolean {
        val plugin = BotShared.getPlugin()
        if (!::starter.isInitialized) {
            plugin.log_warning("QQ 机器人未启动，无法回复文本")
            return false
        }
        if (text.isBlank()) return false
        val payload = V2MsgData()
            .setContent(text)
            .setMsg_id(messageId)
            .setMsg_seq(messageSequence)
        return try {
            starter.bot.groupBaseV2.send(
                groupOpenId,
                JSON.toJSONString(payload),
                Channel.SEND_MESSAGE_HEADERS
            )
            true
        } catch (error: Exception) {
            plugin.log_error("回复文本失败: ${error.message}")
            false
        }
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

    fun shutdown() {
        try {
            if (::starter.isInitialized) starter.shutdown()
        } finally {
            QqBotConsoleOutputFilter.uninstall()
        }
    }
}
