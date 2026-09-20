package cn.huohuas001.bot.events

import cn.huohuas001.bot.HuHoBot
import io.github.kloping.qqbot.api.event.InterActionEvent
import io.github.kloping.qqbot.impl.ListenerHost

/**
 * QQ 互动事件监听入口。
 *
 * 收到 QQ 开放平台的 `INTERACTION_CREATE`（消息按钮 / 快捷菜单回调）后，
 * 交给当前平台触发插件事件，由插件决定如何响应互动并回复消息。
 */
class InteractionHandler(
    private val plugin: HuHoBot
) : ListenerHost() {

    /** QQ 互动事件创建时触发。 */
    @EventReceiver
    fun onInteractionCreate(event: InterActionEvent) {
        try {
            plugin.onBotInteractionCreate(event)
        } catch (error: Exception) {
            plugin.log_error("互动事件处理异常: ${error.message}")
        }
    }
}
