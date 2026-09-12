package cn.huohuas001.bot.events

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.events.commands.AdministrationCommands
import cn.huohuas001.bot.events.commands.AuthenticationCommands
import cn.huohuas001.bot.events.commands.BaseCommand
import cn.huohuas001.bot.events.commands.PublicCommands
import cn.huohuas001.bot.events.commands.RegisteredCommand
import cn.huohuas001.bot.state.CommandRepositories
import cn.huohuas001.bot.tools.FaceEmojiParser
import cn.huohuas001.bot.tools.MessageAttachmentParser
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.impl.ListenerHost
import java.util.concurrent.CopyOnWriteArrayList

/** QQ 群消息事件入口，负责群限制、命令分发和全量聊天转发。 */
class GroupMessageHandler(
    private val plugin: HuHoBot
) : ListenerHost() {
    private val commands = CopyOnWriteArrayList<BaseCommand>()

    init {
        registerCommand(PublicCommands())
        registerCommand(AdministrationCommands())
        registerCommand(AuthenticationCommands())
    }

    fun registerCommand(command: BaseCommand) {
        commands.add(command)
    }

    /** 汇总所有实际注册的指令，供 QQ 指令面板自动同步。 */
    fun registeredCommands(): List<RegisteredCommand> = commands
        .flatMap { it.registeredCommands() }
        .distinctBy { it.command }
        .sortedBy { it.command }

    /** 公域机器人只有在被 @ 时才会收到此事件。 */
    @EventReceiver
    fun onGroupMessage(event: GroupMessageEvent) {
        val groupId = event.groupOpenId ?: event.groupId
        val content = event.rawMessage.content ?: return
        // SDK 的 getter 会递增序号，整条分发链只读取一次。
        val messageSequence = event.msgSeq

        // 在群限制和内置指令处理之前通知平台适配器，保证“收到消息”事件不会漏掉命令消息。
        val receivedMessageCancelled = plugin.onBotReceivedGroupMessage(event, messageSequence)

        if (!content.contains("查信息")) {
            if (!isAllowedGroup(groupId)) return
        }
        when (dispatchCommand(event)) {
            BaseCommand.DispatchResult.CUSTOM_COMMAND -> {
                val customCommandCancelled = plugin.onBotCommand(event, messageSequence)
                if (!receivedMessageCancelled && !customCommandCancelled) {
                    forwardFullGroupMessage(groupId, event)
                }
            }

            BaseCommand.DispatchResult.HANDLED -> Unit
            BaseCommand.DispatchResult.NOT_HANDLED -> {
                if (!receivedMessageCancelled) forwardFullGroupMessage(groupId, event)
            }
        }
    }

    private fun isAllowedGroup(groupId: String): Boolean {
        val allowedGroups = plugin.getGroupOpenIdList()
        return allowedGroups.isEmpty() || groupId in allowedGroups
    }

    private fun dispatchCommand(event: GroupMessageEvent): BaseCommand.DispatchResult {
        val content = event.rawMessage.content.orEmpty()
        val isSlashCommand = Regex("<@!?[^>]+>").replace(content, "").trim().startsWith("/")

        // 斜杠命令先让所有处理器完成内置命令匹配，避免前面的处理器过早进入 custom fallback。
        if (isSlashCommand) {
            for (command in commands) {
                try {
                    if (command.handleMessage(
                            plugin,
                            event,
                            allowCustomFallback = false
                        ) != BaseCommand.DispatchResult.NOT_HANDLED
                    ) {
                        return BaseCommand.DispatchResult.HANDLED
                    }
                } catch (error: Exception) {
                    plugin.log_error("指令处理异常: ${error.message}")
                }
            }
        }

        for (command in commands) {
            try {
                val result = command.handleMessage(plugin, event)
                if (result != BaseCommand.DispatchResult.NOT_HANDLED) return result
            } catch (error: Exception) {
                plugin.log_error("指令处理异常: ${error.message}")
            }
        }
        return BaseCommand.DispatchResult.NOT_HANDLED
    }

    private fun forwardFullGroupMessage(groupId: String, event: GroupMessageEvent) {
        val enabled = CommandRepositories.groupSettings
            .fullForwarding(groupId, plugin.getFullAmount())
        if (!enabled || !plugin.getChatFormat().postChat) return

        var message = event.rawMessage.toString0()
        val senderName = event.sender?.username ?: "unknown"

        //格式化Mentions到@UserName
        val mentions = event.mentions
        mentions.forEach { mention ->
            message = message
                .replace("<@!${mention.openid}>", "@${mention.username}")
                .replace("<@${mention.openid}>", "@${mention.username}")
                .replace("<${mention.openid}>", "@${mention.username}")
                .replace(mention.openid, "@${mention.username}")
        }
        //解析[voice:xxx.amr|text][pic:xxx][video:xxx][file:xxx]
        message = MessageAttachmentParser.parse(message)

        //格式化表情
        message = FaceEmojiParser.parse(message)

        plugin.broadcastMessage(plugin.formatGroupMessage(senderName, plugin.auditText(message)))
    }
}
