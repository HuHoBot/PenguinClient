package cn.huohuas001.huhobotPenguin.allay

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.QClient
import cn.huohuas001.bot.events.commands.CustomCommandRegistry
import cn.huohuas001.bot.provider.*
import cn.huohuas001.bot.tools.Cancelable
import cn.huohuas001.huhobotPenguin.adapter.api.MsgPack
import cn.huohuas001.huhobotPenguin.adapter.api.toMsgPack
import cn.huohuas001.huhobotPenguin.adapter.api.withCommand
import cn.huohuas001.huhobotPenguin.adapter.config.YamlConfig
import cn.huohuas001.huhobotPenguin.allay.commands.HuHoBotCommand
import cn.huohuas001.huhobotPenguin.allay.events.OnBotCommand
import cn.huohuas001.huhobotPenguin.allay.events.OnBotRecvMsg
import cn.huohuas001.huhobotPenguin.allay.utils.HuHoBotCommandSender
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.ex.Keyboard
import org.allaymc.api.eventbus.EventHandler
import org.allaymc.api.eventbus.event.player.PlayerChatEvent
import org.allaymc.api.eventbus.event.server.PlayerJoinEvent
import org.allaymc.api.eventbus.event.server.PlayerQuitEvent
import org.allaymc.api.plugin.Plugin
import org.allaymc.api.registry.Registries
import org.allaymc.api.server.Server
import java.io.File
import java.util.concurrent.CompletableFuture

class HuHoBotAllay : Plugin(), HuHoBot {
    private lateinit var config: YamlConfig
    private val configFile = File("plugins/HuHoBot/config.yml")

    override fun onLoad() {
        config = YamlConfig(configFile, 19132, ::log_warning)
        config.initialize { javaClass.classLoader.getResourceAsStream("config.yml") }
        Registries.COMMANDS.register(HuHoBotCommand(this))
        log_info("HuHoBotPenguin Allay 已加载")
    }

    override fun onEnable() {
        Server.getInstance().eventBus.registerListener(this)
        initializeRuntime()
    }

    override fun onDisable() {
        shutdownRuntime()
    }

    @EventHandler
    private fun onPlayerChat(event: PlayerChatEvent) {
        QClient.broadcastGameMessage(event.player.displayName, event.message)
    }

    @EventHandler
    private fun onPlayerJoin(event: PlayerJoinEvent) {
        if(event.isCancelled) return
        QClient.broadcastPlayerJoin(event.player.originName)
    }

    @EventHandler
    private fun onPlayerQuit(event: PlayerQuitEvent) {
        if(event.isCancelled) return
        QClient.broadcastPlayerQuit(event.player.originName)
    }

    override fun reloadPluginConfig() {
        config.reload()
        reloadRuntimeConfig()
    }

    override fun createCommandExecutor(): HExecution = AllayExecution(this)

    override fun onBotReceivedGroupMessage(event: GroupMessageEvent, messageSequence: Int): Boolean {
        val msgPack = event.toMsgPack(messageSequence)
        val botEvent = OnBotRecvMsg(
            msgPack,
            { text -> QClient.replyText(msgPack.groupOpenId, msgPack.messageId, msgPack.messageSequence, text) },
            { markdown, keyboard -> QClient.replyMarkdown(
                msgPack.groupOpenId,
                msgPack.messageId,
                msgPack.messageSequence,
                markdown,
                keyboard
            ) }
        )
        callSyncEvent(botEvent)
        return botEvent.isCancelled
    }

    override fun onBotCommand(event: GroupMessageEvent, messageSequence: Int): Boolean {
        val msgPack = event.toMsgPack(messageSequence).withCommand(event.rawMessage.content.orEmpty())
        val botEvent = OnBotCommand(
            msgPack,
            { text -> QClient.replyText(msgPack.groupOpenId, msgPack.messageId, msgPack.messageSequence, text) },
            { markdown, keyboard -> QClient.replyMarkdown(
                msgPack.groupOpenId,
                msgPack.messageId,
                msgPack.messageSequence,
                markdown,
                keyboard
            ) }
        )
        callSyncEvent(botEvent)
        return botEvent.isCancelled
    }

    private fun <T : org.allaymc.api.eventbus.event.Event> callSyncEvent(event: T): T {
        return try {
            val future = CompletableFuture<T>()
            submit {
                try {
                    Server.getInstance().eventBus.callEvent(event)
                    future.complete(event)
                } catch (error: Throwable) {
                    future.completeExceptionally(error)
                }
            }
            future.get()
        } catch (error: Exception) {
            log_error("同步触发 Allay 事件失败: ${error.message}")
            event
        }
    }

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

    fun unregisterBotCommand(key: String): Boolean {
        val removed = CustomCommandRegistry.unregister(key)
        if (removed) QClient.syncGroupPanels()
        return removed
    }

    fun sendBotText(text: String) = sendText(text)
    fun sendBotText(groupOpenId: String, text: String): Boolean = QClient.sendText(groupOpenId, text)

    @JvmOverloads
    fun sendBotMarkdown(markdown: String, keyboard: Keyboard? = null) = sendMarkdown(markdown, keyboard)

    @JvmOverloads
    fun sendBotMarkdown(groupOpenId: String, markdown: String, keyboard: Keyboard? = null): Boolean =
        QClient.sendMarkdown(groupOpenId, markdown, keyboard)

    override fun broadcastMessage(msg: String) {
        Server.getInstance().messageChannel.broadcastMessage(msg)
    }

    override fun submit(task: Runnable): Cancelable {
        Server.getInstance().scheduler.scheduleDelayed(this, HuHoBotTask(task), 0)
        return NoopCancelable()
    }

    override fun submitLater(delay: Long, task: Runnable): Cancelable {
        Server.getInstance().scheduler.scheduleDelayed(this, HuHoBotTask(task), delay.toInt())
        return NoopCancelable()
    }

    override fun submitTimer(delay: Long, period: Long, task: Runnable): Cancelable {
        Server.getInstance().scheduler.scheduleRepeating(this, HuHoBotTask(task), period.toInt())
        return NoopCancelable()
    }

    override fun getOnlineList(): List<String> = Server.getInstance().playerManager.players.map { it.value.originName }.toMutableList()
    override fun getConfigFile(): File = config.file
    override fun getBotAppId(): String = config.botAppId()
    override fun getBotSecret(): String = config.botSecret()
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
    override fun getFullAmount(): Boolean = config.fullForwardingByDefault()
    override fun getCommandList(): Map<String, Boolean> = config.commandSwitches()
    override fun getCommandMenuList(): Map<String, Boolean> = config.commandMenuSwitches()
    override fun getAuditBaseUrl(): String? = config.auditBaseUrl()
    override fun getAuditApiKey(): String? = config.auditApiKey()
    override fun getAuditModel(): String? = config.auditModel()
    override fun getCustomCommands(): List<CustomCommandDetail> = config.customCommands()
    override fun getBotName(): String = config.botName()
    override fun getServerName(): String = config.serverName()
    override fun getPlatform(): String = "Allay"
    override fun getPluginVersion(): String = projectVersion()

    private fun projectVersion(): String = try {
        getPluginContainer().descriptor().version
    } catch (_: Exception) { "1.0.0" }

    override fun log_info(msg: String) = pluginLogger.info(msg)
    override fun log_warning(msg: String) = pluginLogger.warn(msg)
    override fun log_error(msg: String) = pluginLogger.error(msg)
}

private class AllayExecution(private val plugin: HuHoBotAllay) : HExecution {
    private val sender = HuHoBotCommandSender(plugin)

    override fun getRawString(): String = sender.outputs.toString()

    override fun execute(command: String): CompletableFuture<HExecution> {
        val future = CompletableFuture<HExecution>()
        plugin.submit {
            try {
                Registries.COMMANDS.execute(sender, command.removePrefix("/"))
                future.complete(this)
            } catch (error: Throwable) {
                future.completeExceptionally(error)
            }
        }
        return future
    }
}

private class NoopCancelable : Cancelable {
    override fun cancel() = Unit
}
