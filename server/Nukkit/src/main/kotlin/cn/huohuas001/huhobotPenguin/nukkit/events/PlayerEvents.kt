package cn.huohuas001.huhobotPenguin.nukkit.events

import cn.huohuas001.bot.QClient
import cn.huohuas001.huhobotPenguin.nukkit.HuHoBotNukkit
import cn.nukkit.event.EventHandler
import cn.nukkit.event.Listener
import cn.nukkit.event.player.PlayerChatEvent

class PlayerEvents(private val plugin: HuHoBotNukkit) : Listener {
    @EventHandler
    fun onChat(event: PlayerChatEvent) {
        QClient.broadcastGameMessage(event.player.name, event.message)
    }
}
