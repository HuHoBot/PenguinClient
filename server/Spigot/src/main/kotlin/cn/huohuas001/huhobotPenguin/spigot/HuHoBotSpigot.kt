package cn.huohuas001.huhobotPenguin.spigot

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.QClient
import cn.huohuas001.bot.addon.Addon
import cn.huohuas001.bot.events.commands.CustomCommandRegistry
import cn.huohuas001.bot.provider.*
import cn.huohuas001.bot.tools.Cancelable
import cn.huohuas001.huhobotPenguin.spigot.commands.BukkitConsoleSender
import cn.huohuas001.huhobotPenguin.spigot.commands.CommandOutputAppender
import cn.huohuas001.huhobotPenguin.spigot.commands.HuHoBotCommand
import cn.huohuas001.huhobotPenguin.spigot.commands.HybridCommandExecutor
import cn.huohuas001.huhobotPenguin.spigot.events.GameChat
import cn.huohuas001.huhobotPenguin.spigot.events.OnBotCommand
import cn.huohuas001.huhobotPenguin.spigot.events.OnBotGroupMemberAdd
import cn.huohuas001.huhobotPenguin.spigot.events.OnBotGroupMemberRemove
import cn.huohuas001.huhobotPenguin.spigot.events.OnBotInteraction
import cn.huohuas001.huhobotPenguin.spigot.events.OnBotJoinRequest
import cn.huohuas001.huhobotPenguin.spigot.events.OnBotRecvMsg
import cn.huohuas001.huhobotPenguin.spigot.manager.ConfigManager
import cn.huohuas001.huhobotPenguin.adapter.api.toGroupMemberPack
import cn.huohuas001.huhobotPenguin.adapter.api.toInteractionPack
import cn.huohuas001.huhobotPenguin.adapter.api.toJoinRequestPack
import cn.huohuas001.huhobotPenguin.adapter.api.toMsgPack
import cn.huohuas001.huhobotPenguin.adapter.api.withCommand
import io.github.kloping.qqbot.api.event.InterActionEvent
import io.github.kloping.qqbot.api.v2.GroupJoinRequestEvent
import io.github.kloping.qqbot.api.v2.GroupMemberAddEvent
import io.github.kloping.qqbot.api.v2.GroupMemberRemoveEvent
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.ex.Keyboard
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

private class ServerNotifyManager(
    private val plugin: HuHoBotSpigot,
    private val config: ConfigManager
) {
    @Volatile private var active = true
    @Volatile private var notifier: Thread? = null
    private val stateFile get() = File(plugin.dataFolder, "server-notify-state.properties")
    private val stoppingMethod by lazy { runCatching { Bukkit.getServer().javaClass.getMethod("isStopping") }.getOrNull() }
    private val nmsServer by lazy {
        runCatching { Bukkit.getServer().javaClass.getMethod("getServer").invoke(Bukkit.getServer()) }.getOrNull()
    }

    fun started() {
        val previous = readState()
        val jvmStart = java.lang.management.ManagementFactory.getRuntimeMXBean().startTime
        if (previous?.getProperty("jvm-start")?.toLongOrNull()?.let { it > 0 && it != jvmStart } == true && config.serverNotifyEnabled()) {
            val clean = previous.getProperty("clean")?.toBooleanStrictOrNull() ?: false
            val stopTime = previous.getProperty("stop-time")?.toLongOrNull() ?: 0L
            val age = System.currentTimeMillis() - stopTime
            when {
                !clean -> notifyAfterStartup(config.serverNotifyCrashEnabled(), config.serverNotifyCrashMessage())
                stopTime > 0 && age in 0..(config.serverNotifyRestartWindowSeconds() * 1000L) ->
                    notifyAfterStartup(config.serverNotifyRestartEnabled(), config.serverNotifyRestartMessage())
            }
        }
        writeState(clean = false, stopTime = 0L, jvmStart)
    }

    fun stopping() {
        active = false
        notifier?.interrupt()
        notifier = null
        val realStop = isRealStop()
        if (realStop && config.serverNotifyEnabled() && config.serverNotifyShutdownEnabled()) {
            val message = config.serverNotifyShutdownMessage()
            if (message.isNotBlank() && QClient.connectionState() == "connected") {
                plugin.getGroupOpenIdList().forEach { QClient.sendText(it, format(message)) }
            }
        }
        writeState(
            clean = true,
            stopTime = if (realStop) System.currentTimeMillis() else 0L,
            jvmStart = java.lang.management.ManagementFactory.getRuntimeMXBean().startTime
        )
    }

    private fun notifyAfterStartup(enabled: Boolean, message: String) {
        if (!enabled || message.isBlank() || plugin.getGroupOpenIdList().isEmpty()) return
        notifier = Thread({
            var waited = 0
            while (active && QClient.connectionState() != "connected" && waited++ < 30) {
                try { Thread.sleep(2000) } catch (_: InterruptedException) { return@Thread }
            }
            if (!active || QClient.connectionState() != "connected") return@Thread
            plugin.getGroupOpenIdList().forEach { QClient.sendText(it, format(message)) }
        }, "HuHoBot-ServerNotify").apply { isDaemon = true; start() }
    }

    private fun isRealStop(): Boolean {
        stoppingMethod?.let { runCatching { it.invoke(Bukkit.getServer()) as? Boolean }.getOrNull()?.let { return it } }
        val server = nmsServer
        val running = runCatching { server?.javaClass?.getMethod("isRunning") }.getOrNull()
        if (server != null && running != null) {
            runCatching { running.invoke(server) as? Boolean }.getOrNull()?.let { return !it }
        }
        return true
    }

    private fun format(message: String) = message.replace("{server}", plugin.getServerName())
        .replace("{platform}", plugin.getPlatform()).trim()

    private fun readState(): java.util.Properties? = if (!stateFile.isFile) null else runCatching {
        java.util.Properties().apply { stateFile.inputStream().use(::load) }
    }.getOrNull()

    private fun writeState(clean: Boolean, stopTime: Long, jvmStart: Long) {
        runCatching {
            stateFile.parentFile?.mkdirs()
            java.util.Properties().apply {
                setProperty("jvm-start", jvmStart.toString())
                setProperty("stop-time", stopTime.toString())
                setProperty("clean", clean.toString())
            }.store(stateFile.outputStream(), "HuHoBot server notification state")
        }.onFailure { plugin.log_warning("写入服务器状态失败: ${it.message}") }
    }
}

class HuHoBotSpigot : JavaPlugin(), HuHoBot {
    private lateinit var configManager: ConfigManager
    private lateinit var serverNotifyManager: ServerNotifyManager
    override fun onEnable() {
        configManager = ConfigManager(this)
        configManager.initialize()
        serverNotifyManager = ServerNotifyManager(this, configManager)
        initializeRuntime()
        serverNotifyManager.started()
        logCommandExecutor()
        val command = HuHoBotCommand(this)
        getCommand("huhobot")?.apply {
            setExecutor(command)
            tabCompleter = command
        } ?: log_error("无法注册 /huhobot 命令，请检查 plugin.yml")
        server.pluginManager.registerEvents(GameChat(this), this)
        log_info("HuHoBot Penguin 已加载")
    }

    override fun onDisable() {
        serverNotifyManager.stopping()
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
    override fun readConfigValues(): Map<String, Any?> = configManager.snapshot()
    override fun writeConfigValues(values: Map<String, Any?>): Boolean = configManager.saveValues(values)
    override fun getBotAppId(): String = configManager.botAppId()
    override fun getBotSecret(): String = configManager.botSecret()
    override fun saveBotCredentials(appId: String, secret: String): Boolean =
        configManager.saveBotCredentials(appId, secret)
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
    override fun isAuthenticationEnabled(): Boolean = configManager.isAuthenticationEnabled()
    override fun isGroupMemberEventsEnabled(): Boolean = configManager.groupMemberEvents()

    override fun getFullAmount(): Boolean = configManager.fullForwardingByDefault()
    override fun getCommandList(): Map<String, Boolean> = configManager.commandSwitches()
    override fun getCommandMenuList(): Map<String, Boolean> = configManager.commandMenuSwitches()
    override fun getCommandMenuPriorities(): Map<String, Int> = configManager.commandMenuPriorities()
    override fun showAdminCommandsInMenu(): Boolean = configManager.showAdminCommandsInMenu()
    override fun isAutoAddGroupsEnabled(): Boolean = configManager.isAutoAddGroupsEnabled()
    override fun addGroupOpenId(groupOpenId: String): Boolean = configManager.addGroupOpenId(groupOpenId)
    override fun getCommandBlacklist(): List<String> = configManager.commandBlacklist()
    override fun resolveCommandName(command: String): String? = configManager.resolveCommandName(command)
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
