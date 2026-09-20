package cn.huohuas001.bot.provider

import io.github.kloping.qqbot.api.event.InterActionEvent
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.entities.ex.Keyboard

interface MessageProvider {
    fun broadcastMessage(msg: String)

    /** QQ 群消息收到后的平台回调，返回 true 表示阻止后续全量聊天转发。 */
    fun onBotReceivedGroupMessage(event: GroupMessageEvent, messageSequence: Int): Boolean = false

    /** 命中平台注册的自定义命令时回调，返回 true 表示阻止后续全量聊天转发。 */
    fun onBotCommand(event: GroupMessageEvent, messageSequence: Int): Boolean = false

    /**
     * QQ 互动事件（消息按钮 / 快捷菜单回调）触发时回调。
     *
     * @return true 表示事件已被插件取消
     */
    fun onBotInteractionCreate(event: InterActionEvent): Boolean = false

    /** 向配置中的所有 QQ 群发送普通文本。 */
    fun sendText(text: String)

    /** 向配置中的所有 QQ 群发送自定义 Markdown，可选附带消息键盘。 */
    fun sendMarkdown(markdownContent: String, keyboard: Keyboard? = null)

    /**
     * 回复指定的 QQ 群消息，发送普通文本。
     *
     * @return 发送成功时为 QQ 返回的新消息 ID，失败为 null。
     */
    fun replyText(event: GroupMessageEvent, text: String): String?

    /**
     * 回复指定的 QQ 群消息，发送自定义 Markdown，可选附带消息键盘。
     *
     * @return 发送成功时为 QQ 返回的新消息 ID，失败为 null。
     */
    fun replyMarkdown(
        event: GroupMessageEvent,
        markdownContent: String,
        keyboard: Keyboard? = null
    ): String?

    /** 回复指定的 QQ 群消息，同时发送文本和网络图片。 */
    fun replyWithImg(
        event: GroupMessageEvent,
        text: String,
        imgUrl: String
    ): Boolean

    /**
     * 撤回指定的 QQ 群消息。
     *
     * 发送超过 2 分钟的消息不可撤回；机器人为群管理员时可撤回群成员的消息。
     *
     * @return 是否撤回成功
     */
    fun recallMessage(groupOpenId: String, messageId: String): Boolean
}
