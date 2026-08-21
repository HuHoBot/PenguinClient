package cn.huohuas001.huhobotPenguin.bungee

import cn.huohuas001.bot.QClient
import cn.huohuas001.bot.provider.*
import cn.huohuas001.bot.tools.Cancelable
import cn.huohuas001.huhobotPenguin.adapter.api.toMsgPack
import cn.huohuas001.huhobotPenguin.adapter.api.withCommand
import cn.huohuas001.huhobotPenguin.adapter.config.YamlConfig
import cn.huohuas001.huhobotPenguin.bungee.commands.BungeeConsoleSender
import cn.huohuas001.huhobotPenguin.bungee.commands.HuHoBotCommand
import cn.huohuas001.huhobotPenguin.bungee.events.GameChat
import cn.huohuas001.huhobotPenguin.bungee.events.OnBotCommand
import cn.huohuas001.huhobotPenguin.bungee.events.OnBotRecvMsg
import cn.huohuas001.huhobotPenguin.proxy.HuHoBotProxy
import cn.huohuas001.huhobotPenguin.proxy.api.ProxyBotApi
import cn.huohuas001.huhobotPenguin.proxy.redis.RedisManager
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.ex.Keyboard
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.plugin.Plugin
import java.io.File
import java.util.concurrent.TimeUnit

class HuHoBotBungee : Plugin(), HuHoBotProxy {
    private lateinit var config: YamlConfig
    override var redisManager: RedisManager? = null

    override fun onEnable() {
        config = YamlConfig(File(dataFolder, "config.yml"), 25565, ::log_warning)
        config.initialize { javaClass.classLoader.getResourceAsStream("proxy-config.yml") }
        initializeRedis()
        proxy.pluginManager.registerCommand(this, HuHoBotCommand(this))
        proxy.pluginManager.registerListener(this, GameChat())
        initializeRuntime()
        log_info("HuHoBotPenguin BungeeCord 已加载")
    }

    override fun onDisable() {
        redisManager?.disconnect()
        redisManager = null
        shutdownRuntime()
    }

    override fun reloadPluginConfig() {
        config.reload()
        reconnectRedis()
        reloadRuntimeConfig()
    }

    override fun createCommandExecutor(): HExecution = BungeeConsoleSender(this)

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
        proxy.pluginManager.callEvent(botEvent)
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
        proxy.pluginManager.callEvent(botEvent)
        return botEvent.isCancelled
    }

    @JvmOverloads
    fun registerBotCommand(key: String, command: String, permission: Int = 0, pushMenu: Boolean = true): Boolean =
        ProxyBotApi.registerBotCommand(key, command, permission, pushMenu)

    fun unregisterBotCommand(key: String): Boolean = ProxyBotApi.unregisterBotCommand(key)

    fun sendBotText(text: String) = ProxyBotApi.sendBotText(this, text)
    fun sendBotText(groupOpenId: String, text: String): Boolean = ProxyBotApi.sendBotText(groupOpenId, text)

    @JvmOverloads
    fun sendBotMarkdown(markdown: String, keyboard: Keyboard? = null) =
        ProxyBotApi.sendBotMarkdown(this, markdown, keyboard)

    @JvmOverloads
    fun sendBotMarkdown(groupOpenId: String, markdown: String, keyboard: Keyboard? = null): Boolean =
        ProxyBotApi.sendBotMarkdown(groupOpenId, markdown, keyboard)

    override fun broadcastMessage(msg: String) {
        proxy.players.forEach { it.sendMessage(TextComponent(msg)) }
        redisManager?.broadcast(msg)
    }

    private fun initializeRedis() {
        if (!redisEnabled()) {
            log_info("Redis 未启用，带服务器前缀的命令将无法发送到子服务器")
            return
        }
        val manager = RedisManager(this)
        redisManager = manager
        manager.connect(redisHost(), redisPort(), redisPassword())
    }

    override fun reconnectRedis(): Boolean {
        redisManager?.disconnect()
        redisManager = null
        if (!redisEnabled()) return false

        val manager = RedisManager(this)
        redisManager = manager
        manager.connect(redisHost(), redisPort(), redisPassword())
        return manager.isConnected()
    }

    override fun submit(task: Runnable): Cancelable = BungeeCancelable(proxy.scheduler.runAsync(this, task))
    override fun submitLater(delay: Long, task: Runnable): Cancelable =
        BungeeCancelable(proxy.scheduler.schedule(this, task, delay * 50, TimeUnit.MILLISECONDS))

    override fun submitTimer(delay: Long, period: Long, task: Runnable): Cancelable =
        BungeeCancelable(proxy.scheduler.schedule(this, task, delay * 50, period * 50, TimeUnit.MILLISECONDS))

    override fun getOnlineList(): List<String> = proxy.players.map { it.name }.toMutableList()
    override fun redisEnabled(): Boolean = config.redisEnabled()
    override fun redisHost(): String = config.redisHost()
    override fun redisPort(): Int = config.redisPort()
    override fun redisPassword(): String? = config.redisPassword()
    override fun redisChannel(): String = config.redisChannel()
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
    override fun getFullAmount(): Boolean = config.fullForwardingByDefault()
    override fun getCommandList(): Map<String, Boolean> = config.commandSwitches()
    override fun getCommandMenuList(): Map<String, Boolean> = config.commandMenuSwitches()
    override fun getAuditBaseUrl(): String? = config.auditBaseUrl()
    override fun getAuditApiKey(): String? = config.auditApiKey()
    override fun getAuditModel(): String? = config.auditModel()
    override fun getCustomCommands(): List<CustomCommandDetail> = config.customCommands()
    override fun getBotName(): String = config.botName()
    override fun getServerName(): String = config.serverName()
    override fun getPlatform(): String = "BungeeCord"
    override fun getPluginVersion(): String = description.version
    override fun log_info(msg: String) = logger.info(msg)
    override fun log_warning(msg: String) = logger.warning(msg)
    override fun log_error(msg: String) = logger.severe(msg)
}

private class BungeeCancelable(private val task: net.md_5.bungee.api.scheduler.ScheduledTask) : Cancelable {
    override fun cancel() = task.cancel()
}
