package cn.huohuas001.huhobot.spigot

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.QClient
import cn.huohuas001.bot.provider.*
import cn.huohuas001.bot.tools.Cancelable
import cn.huohuas001.huhobot.spigot.commands.BukkitConsoleSender
import cn.huohuas001.huhobot.spigot.commands.CommandOutputAppender
import cn.huohuas001.huhobot.spigot.commands.HybridCommandExecutor
import cn.huohuas001.huhobot.spigot.events.GameChat
import cn.huohuas001.huhobot.spigot.manager.ConfigManager
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.concurrent.CompletableFuture

class HuHoBotSpigot : JavaPlugin(), HuHoBot {
    private lateinit var configManager: ConfigManager
    private var commandExecutorFactory: () -> HExecution = { BukkitConsoleSender(this) }

    override fun onEnable() {
        configManager = ConfigManager(this)
        configManager.initialize()
        initializeRuntime()
        selectCommandExecutor()
        server.pluginManager.registerEvents(GameChat(), this)
        launchQqClient()
        log_info("HuHoBot Penguin 已加载")
    }

    override fun onDisable() {
        QClient.shutdown()
        CommandOutputAppender.removeInstance()
    }

    private fun launchQqClient() {
        val appId = configManager.botAppId()
        val secret = configManager.botSecret()
        if (appId.isBlank() || secret.isBlank()) {
            log_warning("未配置 bot.app-id 或 bot.secret，QQ 机器人未启动")
            return
        }

        server.scheduler.runTaskAsynchronously(this, Runnable {
            try {
                QClient.launchClient(appId, secret)
            } catch (error: Exception) {
                log_error("QQ 机器人启动失败: ${error.message}")
            }
        })
    }

    private fun selectCommandExecutor() {
        val useHybridExecutor = configManager.commandSender().equals("Hybrid", ignoreCase = true)
        commandExecutorFactory = if (useHybridExecutor) {
            log_info("已启用混合控制台命令执行器")
            createHybridCommandExecutorFactory()
        } else {
            log_info("已启用模拟控制台命令执行器")
            createConsoleCommandExecutorFactory()
        }
    }

    private fun createHybridCommandExecutorFactory(): () -> HExecution =
        { HybridCommandExecutor(this) }

    private fun createConsoleCommandExecutorFactory(): () -> HExecution =
        { BukkitConsoleSender(this) }

    override fun dispatchCommand(command: String): CompletableFuture<HExecution> =
        commandExecutorFactory().execute(command.removePrefix("/"))

    override fun broadcastMessage(msg: String) {
        server.scheduler.runTask(this, Runnable { Bukkit.broadcastMessage(msg) })
    }

    override fun submit(task: Runnable): Cancelable =
        HuHoBotTask(server.scheduler.runTask(this, task))

    override fun submitLater(delay: Long, task: Runnable): Cancelable =
        HuHoBotTask(server.scheduler.runTaskLater(this, task, delay))

    override fun submitTimer(delay: Long, period: Long, task: Runnable): Cancelable =
        HuHoBotTask(server.scheduler.runTaskTimer(this, task, delay, period))

    override fun getConfigFile(): File = configManager.configFile
    override fun getChatFormat(): ChatFormat = configManager.chatFormat()
    override fun getMotd(): Motd = configManager.motd()
    override fun getWhiteList(): WhiteList = configManager.whiteList()
    override fun getFilterRegexList(): List<String> = configManager.filterRegexList()
    override fun getAdminMode(): AdminMode = configManager.adminMode()
    override fun getAdminList(): List<String> = configManager.adminOpenIds()
    override fun getGroupOpenIdList(): List<String> = configManager.groupOpenIds()
    override fun getFullAmount(): Boolean = configManager.fullForwardingByDefault()
    override fun getCommandList(): Map<String, Boolean> = configManager.commandSwitches()
    override fun getAuditBaseUrl(): String? = configManager.auditBaseUrl()
    override fun getAuditApiKey(): String? = configManager.auditApiKey()
    override fun getAuditModel(): String? = configManager.auditModel()
    override fun getCustomCommands(): List<CustomCommandDetail> = configManager.customCommands()
    override fun getBotName(): String = configManager.botName()
    override fun getPlatform(): String = "Spigot"
    override fun getPluginVersion(): String = description.version

    override fun log_info(msg: String) = logger.info(msg)
    override fun log_warning(msg: String) = logger.warning(msg)
    override fun log_error(msg: String) = logger.severe(msg)
}
