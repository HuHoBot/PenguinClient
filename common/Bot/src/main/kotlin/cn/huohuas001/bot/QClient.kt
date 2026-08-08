package cn.huohuas001.bot

import cn.huohuas001.bot.commands.BaseCommand
import cn.huohuas001.bot.providers.BotShared
import io.github.kloping.qqbot.Starter
import io.github.kloping.qqbot.api.Intents
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import io.github.kloping.qqbot.impl.ListenerHost
import java.util.concurrent.CopyOnWriteArrayList

object QClient {
    private lateinit var starter: Starter
    private val commands = CopyOnWriteArrayList<BaseCommand>()

    /**
     * 注册指令处理器,收到群消息后会自动分发
     */
    fun registerCommand(command: BaseCommand) {
        commands.add(command)
    }

    fun launchClient(appid: String, secret: String) {
        starter = Starter(appid, "", secret)
        starter.config.code = Intents.PUBLIC_INTENTS.and(Intents.GROUP_INTENTS)
        starter.run()
        starter.registerListenerHost(object : ListenerHost() {
            /**
             * 因为是公域 所以仅当bot被at时才能触发事件
             * @param event
             */
            @EventReceiver
            fun onMessage(event: GroupMessageEvent) {
                val plugin = BotShared.getPlugin()
                for (command in commands) {
                    try {
                        // 返回 true 说明指令已被消费,停止分发
                        if (command.handleMessage(plugin, event)) return
                    } catch (e: Exception) {
                        plugin.log_error("指令处理异常: ${e.message}")
                    }
                }
            }
        })
    }
}
