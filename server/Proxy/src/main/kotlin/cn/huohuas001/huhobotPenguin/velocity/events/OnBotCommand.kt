package cn.huohuas001.huhobotPenguin.velocity.events

import cn.huohuas001.huhobotPenguin.adapter.api.MsgPack
import io.github.kloping.qqbot.entities.ex.Keyboard

/** 命中 HuHoBot 自定义命令时触发的 Velocity 事件。 */
class OnBotCommand(
    val msgPack: MsgPack,
    /** 回复文本回调，成功返回新消息 ID，失败返回 null。 */
    private val replyTextAction: (String) -> String?,
    /** 回复 Markdown 回调，成功返回新消息 ID，失败返回 null。 */
    private val replyMarkdownAction: (String, Keyboard?) -> String?
) {
    constructor(msgPack: MsgPack) : this(msgPack, { null }, { _, _ -> null })

    val message: MsgPack
        get() = msgPack

    private var cancelled = false

    fun isCancelled(): Boolean = cancelled

    fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }

    /** 回复触发此事件的 QQ 群消息，成功返回新消息 ID，失败返回 null。 */
    fun replyText(text: String): String? = replyTextAction(text)

    /** [replyText] 的布尔便捷写法，仅表示是否发送成功。 */
    fun reply(text: String): Boolean = replyText(text) != null

    /** 回复触发此事件的 QQ 群消息（Markdown），成功返回新消息 ID，失败返回 null。 */
    fun replyMarkdown(markdown: String): String? = replyMarkdownAction(markdown, null)

    fun replyMarkdown(markdown: String, keyboard: Keyboard?): String? =
        replyMarkdownAction(markdown, keyboard)
}
