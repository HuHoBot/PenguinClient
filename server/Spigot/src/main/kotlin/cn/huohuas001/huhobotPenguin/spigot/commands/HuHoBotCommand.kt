package cn.huohuas001.huhobotPenguin.spigot.commands

import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot
import cn.huohuas001.bot.tools.WebEditorCommand
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor

class HuHoBotCommand(private val plugin: HuHoBotSpigot) : TabExecutor {
    override fun onCommand(
        sender: CommandSender,
        _command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        if (!sender.hasPermission("huhobot.command")) {
            sender.sendMessage(ChatColor.RED.toString() + "你没有足够的权限。")
            return true
        }
        if (WebEditorCommand.handle(plugin, args, sender::sendMessage)) return true
        when (args.firstOrNull()?.lowercase()) {
            "reload" -> {
                plugin.reloadPluginConfig()
                sender.sendMessage(ChatColor.GOLD.toString() + "已重载配置文件。")
            }

            "info" -> sender.sendMessage(
                "平台: ${plugin.getPlatform()}\n版本: ${plugin.getPluginVersion()}"
            )

            else -> sendHelp(sender, label)
        }
        return true
    }

    override fun onTabComplete(
        _sender: CommandSender,
        _command: Command,
        _alias: String,
        args: Array<out String>
    ): List<String> {
        if (args.size != 1) return emptyList()
        val prefix = args[0].lowercase()
        return SUBCOMMANDS.filter { it.startsWith(prefix) }
    }

    private fun sendHelp(sender: CommandSender, label: String) {
        sender.sendMessage("/$label reload - 重载配置文件")
        sender.sendMessage("/$label info - 查看适配器信息")
        sender.sendMessage("/$label webui [配对码|close] - 创建、确认或撤销独立网页管理通道")
    }

    private companion object {
        val SUBCOMMANDS = listOf("reload", "info", "webui", "help")
    }
}
