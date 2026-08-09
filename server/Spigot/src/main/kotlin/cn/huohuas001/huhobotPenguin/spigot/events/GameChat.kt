package cn.huohuas001.huhobotPenguin.spigot.events

import cn.huohuas001.bot.QClient
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent

class GameChat : Listener {
    @EventHandler(ignoreCancelled = true)
    fun onChat(event: AsyncPlayerChatEvent) {
        QClient.broadcastGameMessage(event.player.name, event.message)
    }
}
