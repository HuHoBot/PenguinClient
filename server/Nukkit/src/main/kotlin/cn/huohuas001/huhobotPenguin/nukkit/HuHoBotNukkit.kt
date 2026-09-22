package cn.huohuas001.huhobotPenguin.nukkit

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.QClient
import cn.huohuas001.bot.addon.Addon
import cn.huohuas001.bot.events.commands.CustomCommandRegistry
import cn.huohuas001.bot.provider.*
import cn.huohuas001.bot.tools.Cancelable
import cn.huohuas001.huhobotPenguin.adapter.config.YamlConfig
import cn.huohuas001.huhobotPenguin.nukkit.commands.HuHoBotCommand
import cn.huohuas001.huhobotPenguin.nukkit.events.OnBotCommand
import cn.huohuas001.huhobotPenguin.nukkit.events.OnBotGroupMemberAdd
import cn.huohuas001.huhobotPenguin.nukkit.events.OnBotGroupMemberRemove
import cn.huohuas001.huhobotPenguin.nukkit.events.OnBotInteraction
import cn.huohuas001.huhobotPenguin.nukkit.events.OnBotJoinRequest
import cn.huohuas001.huhobotPenguin.nukkit.events.OnBotRecvMsg
import cn.huohuas001.huhobotPenguin.nukkit.events.PlayerEvents
import cn.huohuas001.huhobotPenguin.nukkit.tools.NukkitConsoleSender
import cn.huohuas001.huhobotPenguin.adapter.api.MsgPack
import cn.huohuas001.huhobotPenguin.adapter.api.toGroupMemberPack
import cn.huohuas001.huhobotPenguin.adapter.api.toInteractionPack
import cn.huohuas001.huhobotPenguin.adapter.api.toJoinRequestPack
import cn.huohuas001.huhobotPenguin.adapter.api.toMsgPack
import cn.huohuas001.huhobotPenguin.adapter.api.withCommand
import cn.nukkit.event.Event
import cn.nukkit.plugin.PluginBase
import cn.nukkit.plugin.PluginLogger
import io.github.kloping.qqbot.api.event.InterActionEvent
import io.github.kloping.qqbot.api.v2.GroupJoinRequestEvent
import io.github.kloping.qqbot.api.v2.GroupMemberAddEvent
import io.github.kloping.qqbot.api.v2.GroupMemberRemoveEvent
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.ex.Keyboard
import java.io.File
import java.util.concurrent.CompletableFuture

class HuHoBotNukkit : PluginBase(), HuHoBot {
    private lateinit var config: YamlConfig
    private lateinit var pluginLogger: PluginLogger

    override fun onEnable() {
        pluginLogger = logger
        config = YamlConfig(File(dataFolder, "config.yml"), 19132, ::log_warning)
        config.initialize { javaClass.classLoader.getResourceAsStream("config.yml") }
        server.commandMap.register("huhobot", HuHoBotCommand(this))
        server.pluginManager.registerEvents(PlayerEvents(this), this)
        initializeRuntime()
        log_info("HuHoBotPenguin Nukkit 已加载")
    }

    override fun onDisable() = shutdownRuntime()

    override fun reloadPluginConfig() {
        config.reload()
        reloadRuntimeConfig()
    }

    override fun onBotReceivedGroupMessage(event: GroupMessageEvent, messageSequence: Int): Boolean {
        val msgPack = event.toMsgPack(messageSequence)
        val botEvent = OnBotRecvMsg(
            msgPack = msgPack,
            replyTextAction = { text -> replyText(msgPack, text) },
            replyMarkdownAction = { markdown, keyboard -> replyMarkdown(msgPack, markdown, keyboard) }
        )
        callSyncEvent(botEvent)
        return botEvent.isCancelled
    }

    override fun onBotCommand(event: GroupMessageEvent, messageSequence: Int): Boolean {
        val msgPack = event.toMsgPack(messageSequence).withCommand(event.rawMessage.content.orEmpty())
        val botEvent = OnBotCommand(
            msgPack = msgPack,
            replyTextAction = { text -> replyText(msgPack, text) },
            replyMarkdownAction = { markdown, keyboard -> replyMarkdown(msgPack, markdown, keyboard) }
        )
        callSyncEvent(botEvent)
        return botEvent.isCancelled
    }

    override fun onBotInteractionCreate(event: InterActionEvent): Boolean {
        val pack = event.toInteractionPack()
        val botEvent = OnBotInteraction(
            interaction = pack,
            respondAction = { code -> QClient.respondInteraction(pack.eventId, code) },
            replyTextAction = { text ->
                QClient.replyText(pack.groupOpenId, pack.eventId, pack.messageSequence, text)
            },
            replyMarkdownAction = { markdown, keyboard ->
                QClient.replyMarkdown(
                    pack.groupOpenId,
                    pack.eventId,
                    pack.messageSequence,
                    markdown,
                    keyboard
                )
            }
        )
        callSyncEvent(botEvent)
        // QQ 要求 5 秒内响应互动事件；插件没有显式响应时统一回执成功，避免客户端提示操作失败。
        if (!botEvent.isResponded()) botEvent.respond(0)
        return botEvent.isCancelled
    }

    override fun onBotGroupMemberAdd(event: GroupMemberAddEvent): Boolean {
        val botEvent = OnBotGroupMemberAdd(event.toGroupMemberPack())
        callSyncEvent(botEvent)
        return botEvent.isCancelled
    }

    override fun onBotGroupMemberRemove(event: GroupMemberRemoveEvent): Boolean {
        val botEvent = OnBotGroupMemberRemove(event.toGroupMemberPack())
        callSyncEvent(botEvent)
        return botEvent.isCancelled
    }

    override fun onBotGroupJoinRequest(event: GroupJoinRequestEvent): Boolean {
        val pack = event.toJoinRequestPack()
        val botEvent = OnBotJoinRequest(
            request = pack,
            approveAction = { joinRequestId ->
                QClient.approveJoinRequest(pack.groupOpenId, pack.memberOpenId.orEmpty(), joinRequestId)
            },
            declineAction = { joinRequestId, rejectReason, addToMemberBlacklist ->
                QClient.declineJoinRequest(
                    pack.groupOpenId,
                    pack.memberOpenId.orEmpty(),
                    joinRequestId,
                    rejectReason,
                    addToMemberBlacklist
                )
            }
        )
        callSyncEvent(botEvent)
        return botEvent.isCancelled
    }

    private fun replyText(msgPack: MsgPack, text: String): String? = QClient.replyText(
        groupOpenId = msgPack.groupOpenId,
        messageId = msgPack.messageId,
        messageSequence = msgPack.messageSequence,
        text = text
    )

    private fun replyMarkdown(msgPack: MsgPack, markdown: String, keyboard: Keyboard?): String? =
        QClient.replyMarkdown(
            groupOpenId = msgPack.groupOpenId,
            messageId = msgPack.messageId,
            messageSequence = msgPack.messageSequence,
            markdownContent = markdown,
            keyboard = keyboard
        )

    private fun <T : Event> callSyncEvent(event: T): T {
        if (server.isPrimaryThread) {
            server.pluginManager.callEvent(event)
            return event
        }
        return try {
            val future = CompletableFuture<T>()
            submit {
                try {
                    server.pluginManager.callEvent(event)
                    future.complete(event)
                } catch (error: Throwable) {
                    future.completeExceptionally(error)
                }
            }
            future.get()
        } catch (error: Exception) {
            log_error("同步触发 Nukkit 事件失败: ${error.message}")
            event
        }
    }

    /** 注册附属插件。 */
    fun registerAddon(addon: Addon) = cn.huohuas001.bot.addon.AddonManager.register(addon)

    /** 注册运行时自定义命令，并按 pushMenu 更新 QQ 命令面板。 */
    @JvmOverloads
    fun registerBotCommand(
        key: String,
        command: String,
        permission: Int = 0,
        pushMenu: Boolean = true
    ): Boolean {
        val registered = CustomCommandRegistry.register(
            CustomCommandDetail(key, command, permission, pushMenu)
        )
        if (registered) QClient.syncGroupPanels()
        return registered
    }

    /** 注册附属插件命令（5 参数版本）。 */
    fun registerBotCommand(
        addonName: String,
        key: String,
        command: String,
        permission: Int = 0,
        pushMenu: Boolean = true
    ): Boolean {
        val registered = CustomCommandRegistry.register(
            CustomCommandDetail(key, command, permission, pushMenu)
        )
        if (registered) {
            cn.huohuas001.bot.addon.AddonManager.addCommand(
                addonName,
                cn.huohuas001.bot.events.commands.RegisteredCommand(key, command, permission > 0, addonName)
            )
            QClient.syncGroupPanels()
        }
        return registered
    }

    /** 注销运行时自定义命令，并刷新 QQ 命令面板。 */
    fun unregisterBotCommand(key: String): Boolean {
        val removed = CustomCommandRegistry.unregister(key)
        if (removed) QClient.syncGroupPanels()
        return removed
    }

    /** 向配置中的所有 QQ 群发送普通文本。 */
    fun sendBotText(text: String) = sendText(text)

    /** 主动向指定 QQ 群发送普通文本。 */
    fun sendBotText(groupOpenId: String, text: String): Boolean = QClient.sendText(groupOpenId, text)

    /** 向配置中的所有 QQ 群发送 Markdown。 */
    @JvmOverloads
    fun sendBotMarkdown(markdown: String, keyboard: Keyboard? = null) = sendMarkdown(markdown, keyboard)

    /** 主动向指定 QQ 群发送 Markdown。 */
    @JvmOverloads
    fun sendBotMarkdown(groupOpenId: String, markdown: String, keyboard: Keyboard? = null): Boolean =
        QClient.sendMarkdown(groupOpenId, markdown, keyboard)

    override fun createCommandExecutor(): HExecution = NukkitExecution(this)
    override fun broadcastMessage(msg: String) {
        server.broadcastMessage(msg)
    }

    override fun submit(task: Runnable): Cancelable =
        NukkitTaskCancelable(server.scheduler.scheduleTask(this, task))

    override fun submitLater(delay: Long, task: Runnable): Cancelable =
        NukkitTaskCancelable(server.scheduler.scheduleDelayedTask(this, task, delay.toInt()))

    override fun submitTimer(delay: Long, period: Long, task: Runnable): Cancelable =
        NukkitTaskCancelable(
            server.scheduler.scheduleDelayedRepeatingTask(
                this,
                task,
                delay.toInt(),
                period.toInt()
            )
        )

    override fun getOnlineList(): List<String> = server.onlinePlayers.map { it.value.username }.toMutableList()
    override fun getConfigFile(): File = config.file
    override fun getBotAppId(): String = config.botAppId()
    override fun getBotSecret(): String = config.botSecret()
    override fun saveBotCredentials(appId: String, secret: String): Boolean =
        config.saveBotCredentials(appId, secret)
    override fun getChatFormat(): ChatFormat = config.chatFormat()
    override fun getPlayerEventFormat(): PlayerEventFormat = config.playerEventFormat()
    override fun getMarkdownFiles(): Map<String, String> = config.markdownFiles()
    override fun getMotd(): Motd = config.motd()
    override fun getWhiteList(): WhiteList = config.whiteList()
    override fun getFilterRegexList(): List<String> = config.filterRegexList()
    override fun getAdminMode(): AdminMode = config.adminMode()
    override fun getAdminList(): List<String> = config.adminOpenIds()
    override fun getGroupOpenIdList(): List<String> = config.groupOpenIds()
    override fun shouldSuppressQqBotConsoleOutput(): Boolean = config.suppressQqBotConsoleOutput()
    override fun isAuthenticationEnabled(): Boolean = config.isAuthenticationEnabled()
    override fun isGroupMemberEventsEnabled(): Boolean = config.groupMemberEvents()
    override fun getFullAmount(): Boolean = config.fullForwardingByDefault()
    override fun getCommandList(): Map<String, Boolean> = config.commandSwitches()
    override fun getCommandMenuList(): Map<String, Boolean> = config.commandMenuSwitches()
    override fun getAuditBaseUrl(): String? = config.auditBaseUrl()
    override fun getAuditApiKey(): String? = config.auditApiKey()
    override fun getAuditModel(): String? = config.auditModel()
    override fun getCustomCommands(): List<CustomCommandDetail> = config.customCommands()
    override fun getBotName(): String = config.botName()
    override fun getServerName(): String = config.serverName()
    override fun getPlatform(): String = "Nukkit"
    override fun getPluginVersion(): String = description.version
    override fun log_info(msg: String) = pluginLogger.info(msg)
    override fun log_warning(msg: String) = pluginLogger.warning(msg)
    override fun log_error(msg: String) = pluginLogger.error(msg)

}

private class NukkitExecution(private val plugin: HuHoBotNukkit) : HExecution {
    private val sender = NukkitConsoleSender(plugin.server.consoleSender, plugin)
    override fun getRawString(): String = sender.output.toString()
    override fun execute(command: String): CompletableFuture<HExecution> {
        val future = CompletableFuture<HExecution>()
        plugin.submit {
            try {
                plugin.server.dispatchCommand(sender, command.removePrefix("/"))
                future.complete(this)
            } catch (error: Throwable) {
                future.completeExceptionally(error)
            }
        }
        return future
    }
}
