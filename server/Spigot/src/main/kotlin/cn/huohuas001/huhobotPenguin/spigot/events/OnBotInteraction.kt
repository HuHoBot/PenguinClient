package cn.huohuas001.huhobotPenguin.spigot.events

import cn.huohuas001.huhobotPenguin.adapter.api.InteractionPack
import io.github.kloping.qqbot.entities.ex.Keyboard
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/** 收到 QQ 互动事件（消息按钮 / 快捷菜单回调）时触发的 Bukkit 事件。 */
class OnBotInteraction(
    /** 互动事件快照。 */
    val interaction: InteractionPack,
    private val respondAction: (Int) -> Boolean,
    private val replyTextAction: (String) -> String?,
    private val replyMarkdownAction: (String, Keyboard?) -> String?
) : Event(), Cancellable {
    constructor(interaction: InteractionPack) :
        this(interaction, { false }, { null }, { _, _ -> null })

    /** Java/Kotlin 插件使用的快照别名。 */
    val pack: InteractionPack
        get() = interaction

    /** 被点击按钮的 id。 */
    val buttonId: String?
        get() = interaction.buttonId

    /** 被点击按钮的 data。 */
    val buttonData: String?
        get() = interaction.buttonData

    private var cancelled = false
    private var responded = false

    override fun isCancelled(): Boolean = cancelled

    override fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }

    /** 本次互动是否已经响应过（无论响应是否提交成功）。 */
    fun isResponded(): Boolean = responded

    /**
     * 响应本次互动。
     *
     * QQ 要求在收到互动事件后 5 秒内响应，否则客户端会提示操作失败；
     * 插件没有调用本方法时，框架会统一回执 0（成功）。
     *
     * @param code 0 成功，1 操作失败，2 操作频繁，3 重复操作，4 没有权限，5 仅管理员操作
     * @return 是否成功提交响应；重复响应同一互动时返回 false
     */
    fun respond(code: Int): Boolean {
        if (responded) return false
        responded = true
        return respondAction(code)
    }

    /** 回复按钮所在群的普通文本，成功返回新消息 ID，失败返回 null。 */
    fun replyText(text: String): String? = replyTextAction(text)

    /** [replyText] 的布尔便捷写法，仅表示是否发送成功。 */
    fun reply(text: String): Boolean = replyText(text) != null

    /** 回复按钮所在群的 Markdown，成功返回新消息 ID，失败返回 null。 */
    fun replyMarkdown(markdown: String): String? = replyMarkdownAction(markdown, null)

    fun replyMarkdown(markdown: String, keyboard: Keyboard?): String? =
        replyMarkdownAction(markdown, keyboard)

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
