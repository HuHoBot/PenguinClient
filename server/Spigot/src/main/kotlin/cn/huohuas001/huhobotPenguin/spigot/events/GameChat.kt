package cn.huohuas001.huhobotPenguin.spigot.events

import cn.huohuas001.bot.QClient
import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

class GameChat(private val plugin: HuHoBotSpigot) : Listener {
    @EventHandler(ignoreCancelled = true)
    fun onChat(event: AsyncPlayerChatEvent) {
        QClient.broadcastGameMessage(event.player.name, event.message)
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        if (!plugin.getPlayerEventFormat().alwaysForward && event.joinMessage == null) return
        QClient.broadcastPlayerJoin(event.player.name)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        if (!plugin.getPlayerEventFormat().alwaysForward && event.quitMessage == null) return
        QClient.broadcastPlayerQuit(event.player.name)
    }
}
