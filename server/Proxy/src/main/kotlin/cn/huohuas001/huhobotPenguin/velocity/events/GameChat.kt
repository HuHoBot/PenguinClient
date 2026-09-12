package cn.huohuas001.huhobotPenguin.velocity.events

import cn.huohuas001.bot.QClient
import cn.huohuas001.huhobotPenguin.velocity.HuHoBotVelocity
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PostLoginEvent
import com.velocitypowered.api.event.player.PlayerChatEvent

class GameChat(private val plugin: HuHoBotVelocity) {
    @Subscribe
    fun onPlayerChat(event: PlayerChatEvent) {
        QClient.broadcastGameMessage(event.player.username, event.message)
    }

    @Subscribe
    fun onPlayerJoin(event: PostLoginEvent) {
        QClient.broadcastPlayerJoin(event.player.username)
    }

    @Subscribe
    fun onPlayerQuit(event: DisconnectEvent) {
        if (!plugin.getPlayerEventFormat().alwaysForward &&
            event.loginStatus != DisconnectEvent.LoginStatus.SUCCESSFUL_LOGIN
        ) return
        QClient.broadcastPlayerQuit(event.player.username)
    }
}
