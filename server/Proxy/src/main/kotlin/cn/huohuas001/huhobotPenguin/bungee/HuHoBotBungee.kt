package cn.huohuas001.huhobotPenguin.bungee

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.QClient
import cn.huohuas001.bot.provider.*
import cn.huohuas001.bot.tools.Cancelable
import cn.huohuas001.huhobotPenguin.adapter.config.YamlConfig
import cn.huohuas001.huhobotPenguin.bungee.commands.HuHoBotCommand
import cn.huohuas001.huhobotPenguin.bungee.events.GameChat
import net.md_5.bungee.api.ProxyServer
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.plugin.Plugin
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class HuHoBotBungee : Plugin(), HuHoBot {
    private lateinit var config: YamlConfig

    override fun onEnable() {
        config = YamlConfig(File(dataFolder, "config.yml"), 25565, ::log_warning)
        config.initialize { javaClass.classLoader.getResourceAsStream("config.yml") }
        proxy.pluginManager.registerCommand(this, HuHoBotCommand(this))
        proxy.pluginManager.registerListener(this, GameChat())
        initializeRuntime()
        log_info("HuHoBotPenguin BungeeCord 已加载")
    }

    override fun onDisable() = shutdownRuntime()

    fun reloadPluginConfig() {
        config.reload()
        reloadRuntimeConfig()
    }

    override fun createCommandExecutor(): HExecution = BungeeExecution(this)

    override fun broadcastMessage(msg: String) {
        proxy.players.forEach { it.sendMessage(TextComponent(msg)) }
    }

    override fun submit(task: Runnable): Cancelable = BungeeCancelable(proxy.scheduler.runAsync(this, task))
    override fun submitLater(delay: Long, task: Runnable): Cancelable =
        BungeeCancelable(proxy.scheduler.schedule(this, task, delay * 50, TimeUnit.MILLISECONDS))
    override fun submitTimer(delay: Long, period: Long, task: Runnable): Cancelable =
        BungeeCancelable(proxy.scheduler.schedule(this, task, delay * 50, period * 50, TimeUnit.MILLISECONDS))

    override fun getConfigFile(): File = config.file
    override fun getBotAppId(): String = config.botAppId()
    override fun getBotSecret(): String = config.botSecret()
    override fun getChatFormat(): ChatFormat = config.chatFormat()
    override fun getMotd(): Motd = config.motd()
    override fun getWhiteList(): WhiteList = config.whiteList()
    override fun getFilterRegexList(): List<String> = config.filterRegexList()
    override fun getAdminMode(): AdminMode = config.adminMode()
    override fun getAdminList(): List<String> = config.adminOpenIds()
    override fun getGroupOpenIdList(): List<String> = config.groupOpenIds()
    override fun shouldSuppressQqBotConsoleOutput(): Boolean = config.suppressQqBotConsoleOutput()
    override fun getFullAmount(): Boolean = config.fullForwardingByDefault()
    override fun getCommandList(): Map<String, Boolean> = config.commandSwitches()
    override fun getAuditBaseUrl(): String? = config.auditBaseUrl()
    override fun getAuditApiKey(): String? = config.auditApiKey()
    override fun getAuditModel(): String? = config.auditModel()
    override fun getCustomCommands(): List<CustomCommandDetail> = config.customCommands()
    override fun getBotName(): String = config.botName()
    override fun getPlatform(): String = "BungeeCord"
    override fun getPluginVersion(): String = description.version
    override fun log_info(msg: String) = logger.info(msg)
    override fun log_warning(msg: String) = logger.warning(msg)
    override fun log_error(msg: String) = logger.severe(msg)
}

private class BungeeExecution(private val plugin: HuHoBotBungee) : HExecution {
    private var result = ""
    override fun getRawString(): String = result
    override fun execute(command: String): CompletableFuture<HExecution> {
        val future = CompletableFuture<HExecution>()
        plugin.submit(Runnable {
            try {
                val handled = ProxyServer.getInstance().pluginManager.dispatchCommand(
                    ProxyServer.getInstance().console, command.removePrefix("/")
                )
                result = if (handled) "命令已执行: $command" else "代理端不存在该命令: $command"
                future.complete(this)
            } catch (error: Throwable) {
                result = "执行命令异常: ${error.message}"
                future.completeExceptionally(error)
            }
        })
        return future
    }
}

private class BungeeCancelable(private val task: net.md_5.bungee.api.scheduler.ScheduledTask) : Cancelable {
    override fun cancel() = task.cancel()
}
