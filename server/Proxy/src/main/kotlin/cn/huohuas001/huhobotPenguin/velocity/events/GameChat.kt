package cn.huohuas001.huhobotPenguin.velocity.events

import cn.huohuas001.bot.QClient
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.player.PlayerChatEvent

class GameChat {
    @Subscribe
    fun onPlayerChat(event: PlayerChatEvent) {
        QClient.broadcastGameMessage(event.player.username, event.message)
    }
}
