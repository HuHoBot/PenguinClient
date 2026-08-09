package cn.huohuas001.huhobotPenguin.velocity.commands

import cn.huohuas001.huhobotPenguin.velocity.HuHoBotVelocity
import com.velocitypowered.api.command.SimpleCommand
import net.kyori.adventure.text.Component

class HuHoBotCommand(private val plugin: HuHoBotVelocity) : SimpleCommand {
    override fun execute(invocation: SimpleCommand.Invocation) {
        val source = invocation.source()
        when (invocation.arguments().firstOrNull()?.lowercase()) {
            "reload" -> { plugin.reloadPluginConfig(); source.sendMessage(Component.text("已重载配置文件。")) }
            "info" -> source.sendMessage(Component.text("平台: ${plugin.getPlatform()}\n版本: ${plugin.getPluginVersion()}"))
            else -> { source.sendMessage(Component.text("/huhobot reload - 重载配置文件")); source.sendMessage(Component.text("/huhobot info - 查看适配器信息")) }
        }
    }
    override fun suggest(invocation: SimpleCommand.Invocation): List<String> = listOf("reload", "info", "help")
    override fun hasPermission(invocation: SimpleCommand.Invocation): Boolean = invocation.source().hasPermission("huhobot.command")
}
