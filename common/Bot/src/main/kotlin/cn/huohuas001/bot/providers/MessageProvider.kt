package cn.huohuas001.bot.providers

interface MessageProvider {
    fun broadcastMessage(msg: String)
}