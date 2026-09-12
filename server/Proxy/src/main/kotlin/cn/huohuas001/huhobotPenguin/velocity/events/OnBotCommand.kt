package cn.huohuas001.huhobotPenguin.velocity.events

import cn.huohuas001.huhobotPenguin.adapter.api.MsgPack
import io.github.kloping.qqbot.entities.ex.Keyboard

/** 命中 HuHoBot 自定义命令时触发的 Velocity 事件。 */
class OnBotCommand(
    val msgPack: MsgPack,
    private val replyTextAction: (String) -> Boolean,
    private val replyMarkdownAction: (String, Keyboard?) -> Boolean
) {
    constructor(msgPack: MsgPack) : this(msgPack, { false }, { _, _ -> false })

    val message: MsgPack
        get() = msgPack

    private var cancelled = false

    fun isCancelled(): Boolean = cancelled

    fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }

    fun reply(text: String): Boolean = replyTextAction(text)
    fun replyText(text: String): Boolean = reply(text)
    fun replyMarkdown(markdown: String): Boolean = replyMarkdownAction(markdown, null)
    fun replyMarkdown(markdown: String, keyboard: Keyboard?): Boolean =
        replyMarkdownAction(markdown, keyboard)
}
