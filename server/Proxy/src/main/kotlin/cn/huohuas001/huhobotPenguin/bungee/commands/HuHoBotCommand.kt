package cn.huohuas001.huhobotPenguin.bungee.commands

import cn.huohuas001.huhobotPenguin.bungee.HuHoBotBungee
import net.md_5.bungee.api.CommandSender
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.plugin.Command

class HuHoBotCommand(private val plugin: HuHoBotBungee) : Command("huhobot", "huhobot.command", "hb") {
    override fun execute(sender: CommandSender, args: Array<String>) {
        when (args.firstOrNull()?.lowercase()) {
            "reload" -> { plugin.reloadPluginConfig(); sender.sendMessage(TextComponent("已重载配置文件。")) }
            "info" -> sender.sendMessage(TextComponent("平台: ${plugin.getPlatform()}\n版本: ${plugin.getPluginVersion()}"))
            else -> { sender.sendMessage(TextComponent("/huhobot reload - 重载配置文件")); sender.sendMessage(TextComponent("/huhobot info - 查看适配器信息")) }
        }
    }
}
