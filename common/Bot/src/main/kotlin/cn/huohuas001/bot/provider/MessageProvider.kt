package cn.huohuas001.bot.provider

import io.github.kloping.qqbot.entities.ex.Keyboard

interface MessageProvider {
    fun broadcastMessage(msg: String)

    /** 向配置中的所有 QQ 群发送自定义 Markdown，可选附带消息键盘。 */
    fun sendMarkdown(markdownContent: String, keyboard: Keyboard? = null)
}