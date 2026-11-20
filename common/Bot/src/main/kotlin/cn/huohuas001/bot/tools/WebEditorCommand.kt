package cn.huohuas001.bot.tools

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.web.WebEditorClient

/** Permission checks belong to each platform command; pairing is never exposed as a QQ command. */
object WebEditorCommand {
    fun handle(plugin: HuHoBot, args: Array<out String>, reply: (String) -> Unit): Boolean {
        if (!args.firstOrNull().equals("webui", ignoreCase = true)) return false
        when {
            args.size == 1 -> WebEditorClient.open(plugin, reply)
            args.size == 2 && args[1].equals("close", ignoreCase = true) -> WebEditorClient.revoke(plugin, reply)
            args.size == 2 -> WebEditorClient.authorize(plugin, args[1], reply)
            else -> reply("用法: /huhobot webui [一次性配对码|close]")
        }
        return true
    }
}
