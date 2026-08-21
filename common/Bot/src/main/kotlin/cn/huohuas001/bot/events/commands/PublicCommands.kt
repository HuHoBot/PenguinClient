package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.service.MotdService
import cn.huohuas001.bot.state.CommandRepositories
import io.github.kloping.qqbot.api.v2.GroupMessageEvent

/** 普通群成员可使用的命令。 */
class PublicCommands : CommandSupport() {

    @Commands("查信息", "查询 OpenId")
    fun queryInfo(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (params.isBlank()) {
            reply(
                plugin,
                event,
                "你的OpenId:${userId(event)}\n群的OpenId:${groupId(event)}"
            )
            return
        }

        if (!requireAdmin(plugin, event)) return

        val target = params.trim()
        val status = CommandRepositories.authentication.getBoundQq(groupId(event), target)?.let { "此用户已认证，绑定QQ:$it" }
            ?: "此用户未认证"
        reply(plugin, event, status)
    }

    @Commands("发信息", "发送消息到游戏")
    fun sendGameMessage(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (params.isBlank()) return

        val filtered = plugin.auditText(params)
        if (plugin.getChatFormat().postChat) {
            plugin.broadcastMessage(plugin.formatGroupMessage(userId(event), filtered))
        } else {
            event.sendMessage("群聊转发功能已关闭")
        }
    }

    @Commands("查在线", "查询在线玩家")
    fun queryOnline(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        val onlineList = plugin.getOnlineList()

        val motd = plugin.getMotd()
        val timestampSeconds = System.currentTimeMillis() / 1000
        val imgUrl = motd.api
            .replace("{ip}", motd.serverIP)
            .replace("{port}", motd.serverPort.toString()) + "&${timestampSeconds}"



        if (!motd.useMarkdown) {
            val formattedPlayerList = onlineList.mapIndexed { _, name -> name }.joinToString("\n")
            val formatedText = motd.text
                .replace("{online}", onlineList.count().toString())
                .replace("{players}", formattedPlayerList)
            if (motd.postImg) {
                replyWithImg(plugin, event, formatedText, imgUrl)
            } else {
                reply(plugin, event, formatedText)
            }
            return
        }
        //开启Markdown
        var markdown = plugin.getMarkdown("queryOnline")
        if (markdown == null) {
            event.sendMessage("未找到 Markdown 模板：queryOnline")
            return
        }

        val formattedPlayerList = onlineList.mapIndexed { index, name -> "${index + 1}. **$name**" }.joinToString("\n")

        //替换文本内容
        markdown = markdown
            .replace("{{.server}}", plugin.getServerName())
            .replace("{{.img_url}}", imgUrl)
            .replace("{{.player}}", formattedPlayerList)
            .replace("{{.online_num}}", onlineList.count().toString())

        plugin.replyMarkdown(event, markdown)
    }

    @Commands("在线服务器", "查看已连接服务器")
    fun queryServers(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        reply(plugin, event, "当前已连接服务器：${plugin.getBotName()}")
    }

    @Commands("motd", "查询 Minecraft 服务器状态")
    fun queryMotd(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!plugin.isMotdQueryEnabled()) {
            event.sendMessage("Motd 功能未启用")
            return
        }

        val groupId = groupId(event)
        if (CommandRepositories.groupSettings.isMotdBlocked(groupId) && !isAdmin(plugin, event)) {
            event.sendMessage("本群已屏蔽Motd")
            return
        }

        val queryParams = MotdService.parseParams(params)
        if (queryParams == null) {
            event.sendMessage(MOTD_USAGE_TEXT)
            return
        }

        event.sendMessage("已发起Motd请求，请稍等...")
        if (!MotdService.isValidAddress(queryParams.address)) {
            event.sendMessage("服务器地址参数不正确")
            return
        }

        plugin.submitAsync {
            try {
                val result = MotdService().query(
                    params = queryParams,
                    apiTemplate = plugin.getMotdQueryApi(),
                    defaultImageUrl = plugin.getMotdDefaultImageUrl()
                )
                if (result == null) {
                    event.sendMessage(MOTD_OFFLINE_FAILED_TEXT)
                    return@submitAsync
                }

                val markdownSent = plugin.getMarkdown("motd")?.let { template ->
                    val markdown = result.toMarkdown(template, plugin::auditText)
                    plugin.replyMarkdown(event, markdown)
                } ?: false
                if (markdownSent) return@submitAsync

                plugin.log_warning("MOTD Markdown 发送失败，改用图文消息")
                val text = plugin.auditText(result.toPlainText())
                val imageSent = result.imageUrl.isNotBlank() &&
                        plugin.replyWithImg(event, text, result.imageUrl)
                if (!imageSent) {
                    event.sendMessage(text)
                }
            } catch (error: Exception) {
                plugin.log_error("MOTD 查询失败: ${error.message}")
                event.sendMessage(MOTD_OFFLINE_FAILED_TEXT)
            }
        }
    }

    @Commands("执行", "执行自定义命令")
    fun runCustomCommand(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (params.isBlank()) {
            event.sendMessage("参数不正确")
            return
        }
        executeCustomCommand(plugin, event, params, admin = false)
    }

    companion object {
        private const val MOTD_USAGE_TEXT =
            "Motd参数不正确\n" +
                    "使用方法:/motd <url> <platform>\n" +
                    "url(必填):指定的服务器地址\n" +
                    "platform(选填):<je|be>"

        private const val MOTD_OFFLINE_FAILED_TEXT =
            "❌无法获取服务器状态信息。\n" +
                    "⚠️状态检测为Offline：\n" +
                    "1.服务器没有开启或已经关闭或不允许获取motd\n" +
                    "2.指定的平台错误(je,be,auto)(不填默认auto)\n" +
                    "3.ip或端口输入错误，或者接口维护这个可以问问机器人主人😝"
    }
}
