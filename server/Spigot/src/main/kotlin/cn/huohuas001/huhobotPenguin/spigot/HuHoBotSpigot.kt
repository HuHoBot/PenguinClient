package cn.huohuas001.huhobotPenguin.spigot

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.QClient
import cn.huohuas001.bot.events.commands.CustomCommandRegistry
import cn.huohuas001.bot.provider.*
import cn.huohuas001.bot.tools.Cancelable
import cn.huohuas001.huhobotPenguin.spigot.commands.BukkitConsoleSender
import cn.huohuas001.huhobotPenguin.spigot.commands.CommandOutputAppender
import cn.huohuas001.huhobotPenguin.spigot.commands.HuHoBotCommand
import cn.huohuas001.huhobotPenguin.spigot.commands.HybridCommandExecutor
import cn.huohuas001.huhobotPenguin.spigot.events.GameChat
import cn.huohuas001.huhobotPenguin.spigot.events.OnBotCommand
import cn.huohuas001.huhobotPenguin.spigot.events.OnBotRecvMsg
import cn.huohuas001.huhobotPenguin.spigot.manager.ConfigManager
import cn.huohuas001.huhobotPenguin.adapter.api.MsgPack
import cn.huohuas001.huhobotPenguin.adapter.api.toMsgPack
import cn.huohuas001.huhobotPenguin.adapter.api.withCommand
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.ex.Keyboard
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class HuHoBotSpigot : JavaPlugin(), HuHoBot {
    private lateinit var configManager: ConfigManager

    override fun onEnable() {
        configManager = ConfigManager(this)
        configManager.initialize()
        initializeRuntime()
        logCommandExecutor()
        val command = HuHoBotCommand(this)
        getCommand("huhobot")?.apply {
            setExecutor(command)
            tabCompleter = command
        } ?: log_error("无法注册 /huhobot 命令，请检查 plugin.yml")
        server.pluginManager.registerEvents(GameChat(), this)
        log_info("HuHoBot Penguin 已加载")
    }

    override fun onDisable() {
        shutdownRuntime()
        CommandOutputAppender.removeInstance()
    }

    private fun logCommandExecutor() {
        val useHybridExecutor = configManager.commandSender().equals("Hybrid", ignoreCase = true)
        if (useHybridExecutor) {
            log_info("已启用混合控制台命令执行器")
        } else {
            log_info("已启用模拟控制台命令执行器")
        }
    }

    override fun reloadPluginConfig() {
        configManager.reload()
        reloadRuntimeConfig()
        logCommandExecutor()
    }

    override fun createCommandExecutor(): HExecution =
        if (configManager.commandSender().equals("Hybrid", ignoreCase = true)) {
            HybridCommandExecutor(this)
        } else {
            BukkitConsoleSender(this)
        }

    override fun broadcastMessage(msg: String) {
        server.scheduler.runTask(this, Runnable { Bukkit.broadcastMessage(msg) })
    }

    override fun onBotReceivedGroupMessage(event: GroupMessageEvent, messageSequence: Int): Boolean {
        val msgPack = event.toMsgPack(messageSequence)
        val botEvent = OnBotRecvMsg(
            msgPack = msgPack,
            replyTextAction = { text ->
                QClient.replyText(msgPack.groupOpenId, msgPack.messageId, msgPack.messageSequence, text)
            },
            replyMarkdownAction = { markdown, keyboard ->
                QClient.replyMarkdown(
                    msgPack.groupOpenId,
                    msgPack.messageId,
                    msgPack.messageSequence,
                    markdown,
                    keyboard
                )
            }
        )
        callSyncEvent(botEvent)
        return botEvent.isCancelled
    }

    override fun onBotCommand(event: GroupMessageEvent, messageSequence: Int): Boolean {
        val msgPack = event.toMsgPack(messageSequence).withCommand(event.rawMessage.content.orEmpty())
        val botEvent = OnBotCommand(
            msgPack = msgPack,
            replyTextAction = { text ->
                QClient.replyText(msgPack.groupOpenId, msgPack.messageId, msgPack.messageSequence, text)
            },
            replyMarkdownAction = { markdown, keyboard ->
                QClient.replyMarkdown(
                    msgPack.groupOpenId,
                    msgPack.messageId,
                    msgPack.messageSequence,
                    markdown,
                    keyboard
                )
            }
        )
        callSyncEvent(botEvent)
        return botEvent.isCancelled
    }

    private fun <T : org.bukkit.event.Event> callSyncEvent(event: T): T {
        if (server.isPrimaryThread) {
            server.pluginManager.callEvent(event)
            return event
        }
        return try {
            server.scheduler.callSyncMethod(this) {
                server.pluginManager.callEvent(event)
                event
            }.get()
        } catch (error: Exception) {
            log_error("同步触发 Bukkit 事件失败: ${error.message}")
            event
        }
    }

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

    /** 注销运行时自定义命令，并按需刷新 QQ 命令面板。 */
    fun unregisterBotCommand(key: String): Boolean {
        val removed = CustomCommandRegistry.unregister(key)
        if (removed) QClient.syncGroupPanels()
        return removed
    }

    /** 向配置中的所有 QQ 群发送普通文本。 */
    fun sendBotText(text: String) = sendText(text)

    /** 主动向指定 QQ 群发送普通文本。 */
    fun sendBotText(groupOpenId: String, text: String): Boolean =
        QClient.sendText(groupOpenId, text)

    /** 向配置中的所有 QQ 群发送 Markdown。 */
    @JvmOverloads
    fun sendBotMarkdown(markdown: String, keyboard: Keyboard? = null) = sendMarkdown(markdown, keyboard)

    /** 主动向指定 QQ 群发送 Markdown。 */
    @JvmOverloads
    fun sendBotMarkdown(
        groupOpenId: String,
        markdown: String,
        keyboard: Keyboard? = null
    ): Boolean = QClient.sendMarkdown(groupOpenId, markdown, keyboard)

    override fun submit(task: Runnable): Cancelable =
        HuHoBotTask(server.scheduler.runTask(this, task))

    override fun submitAsync(task: Runnable): Cancelable =
        HuHoBotTask(server.scheduler.runTaskAsynchronously(this, task))

    override fun submitLater(delay: Long, task: Runnable): Cancelable =
        HuHoBotTask(server.scheduler.runTaskLater(this, task, delay))

    override fun submitTimer(delay: Long, period: Long, task: Runnable): Cancelable =
        HuHoBotTask(server.scheduler.runTaskTimer(this, task, delay, period))

    override fun getOnlineList(): List<String> = server.onlinePlayers.map { it.name }.toMutableList()
    override fun getConfigFile(): File = configManager.configFile
    override fun getBotAppId(): String = configManager.botAppId()
    override fun getBotSecret(): String = configManager.botSecret()
    override fun getChatFormat(): ChatFormat = configManager.chatFormat()
    override fun getPlayerEventFormat(): PlayerEventFormat = configManager.playerEventFormat()
    override fun getMarkdownFiles(): Map<String, String> = configManager.markdownFiles()
    override fun getMotd(): Motd = configManager.motd()
    override fun getWhiteList(): WhiteList = configManager.whiteList()
    override fun getFilterRegexList(): List<String> = configManager.filterRegexList()
    override fun getAdminMode(): AdminMode = configManager.adminMode()
    override fun getAdminList(): List<String> = configManager.adminOpenIds()
    override fun getGroupOpenIdList(): List<String> = configManager.groupOpenIds()
    override fun shouldSuppressQqBotConsoleOutput(): Boolean =
        configManager.suppressQqBotConsoleOutput()

    override fun getFullAmount(): Boolean = configManager.fullForwardingByDefault()
    override fun getCommandList(): Map<String, Boolean> = configManager.commandSwitches()
    override fun getCommandMenuList(): Map<String, Boolean> = configManager.commandMenuSwitches()
    override fun getAuditBaseUrl(): String? = configManager.auditBaseUrl()
    override fun getAuditApiKey(): String? = configManager.auditApiKey()
    override fun getAuditModel(): String? = configManager.auditModel()
    override fun getCustomCommands(): List<CustomCommandDetail> = configManager.customCommands()
    override fun getBotName(): String = configManager.botName()
    override fun getServerName(): String = configManager.serverName()
    override fun getPlatform(): String = "Spigot"
    override fun getPluginVersion(): String = description.version

    override fun log_info(msg: String) = logger.info(msg)
    override fun log_warning(msg: String) = logger.warning(msg)
    override fun log_error(msg: String) = logger.severe(msg)
}
