package cn.huohuas001.huhobotPenguin.proxy.api

import cn.huohuas001.bot.QClient
import cn.huohuas001.bot.events.commands.CustomCommandRegistry
import cn.huohuas001.bot.provider.CustomCommandDetail
import cn.huohuas001.huhobotPenguin.proxy.HuHoBotProxy
import io.github.kloping.qqbot.entities.ex.Keyboard

/** BungeeCord 与 Velocity 对第三方插件暴露的共享 API 实现。 */
object ProxyBotApi {
    @JvmStatic
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

    @JvmStatic
    fun unregisterBotCommand(key: String): Boolean {
        val removed = CustomCommandRegistry.unregister(key)
        if (removed) QClient.syncGroupPanels()
        return removed
    }

    @JvmStatic
    fun sendBotText(plugin: HuHoBotProxy, text: String) = plugin.sendText(text)

    @JvmStatic
    fun sendBotText(groupOpenId: String, text: String): Boolean =
        QClient.sendText(groupOpenId, text)

    @JvmStatic
    @JvmOverloads
    fun sendBotMarkdown(plugin: HuHoBotProxy, markdown: String, keyboard: Keyboard? = null) =
        plugin.sendMarkdown(markdown, keyboard)

    @JvmStatic
    @JvmOverloads
    fun sendBotMarkdown(
        groupOpenId: String,
        markdown: String,
        keyboard: Keyboard? = null
    ): Boolean = QClient.sendMarkdown(groupOpenId, markdown, keyboard)
}
