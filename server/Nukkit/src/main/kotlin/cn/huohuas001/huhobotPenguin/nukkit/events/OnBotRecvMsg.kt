package cn.huohuas001.huhobotPenguin.nukkit.events

import cn.huohuas001.huhobotPenguin.adapter.api.MsgPack
import cn.nukkit.event.Cancellable
import cn.nukkit.event.Event
import cn.nukkit.event.HandlerList
import io.github.kloping.qqbot.entities.ex.Keyboard

/** QQ Bot 收到群消息时触发的 Nukkit 事件。 */
class OnBotRecvMsg(
    val msgPack: MsgPack,
    private val replyTextAction: (String) -> Boolean,
    private val replyMarkdownAction: (String, Keyboard?) -> Boolean
) : Event(), Cancellable {
    constructor(msgPack: MsgPack) : this(msgPack, { false }, { _, _ -> false })

    /** Java/Kotlin 插件使用的消息快照别名。 */
    val message: MsgPack
        get() = msgPack

    /** 回复触发此事件的 QQ 群消息。 */
    fun reply(text: String): Boolean = replyTextAction(text)

    fun replyText(text: String): Boolean = reply(text)

    fun replyMarkdown(markdown: String): Boolean = replyMarkdownAction(markdown, null)

    fun replyMarkdown(markdown: String, keyboard: Keyboard?): Boolean =
        replyMarkdownAction(markdown, keyboard)

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlers(): HandlerList = HANDLERS
    }
}
