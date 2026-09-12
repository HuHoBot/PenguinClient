package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.service.AvatarAuthenticationService
import cn.huohuas001.bot.state.CommandRepositories
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import java.util.Locale

/** QQ 头像认证命令。 */
class AuthenticationCommands : CommandSupport() {

    @Commands("认证", "使用 QQ 头像相似度认证或管理员手动认证")
    fun authenticate(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!plugin.isAuthenticationEnabled()) {
            event.sendMessage("认证功能未启用")
            return
        }

        val arguments = splitArguments(params)
        when (arguments.size) {
            0 -> queryStatus(plugin, event)
            1 -> selfAuthenticate(plugin, event, arguments[0])
            2 -> manualAuthenticate(plugin, event, arguments[0], arguments[1])
            else -> event.sendMessage(
                "参数不正确\n" +
                        "自助认证：/认证 <QQ号>\n" +
                        "管理员手动认证：/认证 <QQ号> <OpenId>"
            )
        }
    }

    @Commands("解除认证", "解除用户 QQ 认证", onlyAdmin = true)
    fun removeAuthentication(plugin: HuHoBot, event: GroupMessageEvent, params: String) {
        if (!plugin.isAuthenticationEnabled()) {
            event.sendMessage("认证功能未启用")
            return
        }
        if (!requireAdmin(plugin, event)) return

        val targetOpenId = splitArguments(params).firstOrNull()
        if (targetOpenId.isNullOrBlank()) {
            event.sendMessage("请输入要解除认证的 OpenId")
            return
        }
        if (!isValidOpenId(targetOpenId)) {
            event.sendMessage("OpenId 输入有误")
            return
        }

        val removed = CommandRepositories.authentication.revoke(groupId(event), targetOpenId)
        if (removed) {
            reply(plugin, event, "✅ 解除认证成功！已为${targetOpenId}解除绑定 QQ 账号")
        } else {
            reply(plugin, event, "❌ 解除认证失败！请检查输入的 OpenId 是否正确")
        }
    }

    private fun queryStatus(plugin: HuHoBot, event: GroupMessageEvent) {
        val openId = userId(event)
        val qq = CommandRepositories.authentication.getBoundQq(groupId(event), openId)
        if (qq != null) {
            reply(
                plugin,
                event,
                "您已绑定 QQ:$qq\n如需解除请联系机器人管理员使用\"/解除认证 $openId\"以解除认证"
            )
        } else {
            reply(plugin, event, "您暂未绑定 QQ，请使用\"/认证 <QQ号>\"进行绑定，例如\"/认证 123456789\"")
        }
    }

    private fun selfAuthenticate(plugin: HuHoBot, event: GroupMessageEvent, qq: String) {
        if (!isValidQq(qq)) {
            event.sendMessage("认证失败，请检查输入的 QQ 号是否正确（QQ号应为 5-12 位数字）")
            return
        }

        val groupId = groupId(event)
        val openId = userId(event)
        val existing = CommandRepositories.authentication.getBoundQq(groupId, openId)
        if (existing != null) {
            reply(plugin, event, "您已绑定 QQ:$existing")
            return
        }

        event.sendMessage("正在进行 QQ 头像认证，请稍候...")
        plugin.submitAsync {
            val result = AvatarAuthenticationService.compare(plugin.getBotAppId(), qq, openId)
            when {
                result.code != 0 -> event.sendMessage(
                    "图像比对失败：错误 (${result.code})：${result.message}\n" +
                            "管理员可手动使用\"/认证 $qq $openId\"进行人工确认"
                )

                result.similarity >= AvatarAuthenticationService.MIN_SIMILARITY -> {
                    val similarity = formatPercent(result.similarity)
                    CommandRepositories.authentication.authenticate(groupId, openId, qq)
                    event.sendMessage(
                        "✅ 认证通过！相似度：$similarity%\n" +
                                "绑定信息：\nOpenId:$openId\nQQ账号:$qq\n" +
                                "如绑定有误，请管理员输入\"/解除认证 $openId\""
                    )
                }

                else -> {
                    val similarity = formatPercent(result.similarity)
                    val required = formatPercent(AvatarAuthenticationService.MIN_SIMILARITY)
                    event.sendMessage(
                        "❌ 认证失败，当前匹配度：$similarity%（需≥$required%）\n" +
                                "管理员可手动使用\"/认证 $qq $openId\"进行人工确认"
                    )
                }
            }
        }
    }

    private fun manualAuthenticate(
        plugin: HuHoBot,
        event: GroupMessageEvent,
        qq: String,
        targetOpenId: String
    ) {
        if (!requireAdmin(plugin, event)) return
        if (!isValidQq(qq)) {
            event.sendMessage("QQ号输入有误（QQ号应为 5-12 位数字）")
            return
        }
        if (!isValidOpenId(targetOpenId)) {
            event.sendMessage("OpenId 输入有误")
            return
        }

        CommandRepositories.authentication.authenticate(groupId(event), targetOpenId, qq)
        reply(plugin, event, "✅ 认证通过！已为${targetOpenId}绑定为 QQ 账号:$qq")
    }

    private fun formatPercent(value: Double): String = String.format(Locale.ROOT, "%.2f", value * 100)

    private fun splitArguments(params: String): List<String> = params.trim()
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)
        .map { it.trim('"').removePrefix("<@").removeSuffix(">") }

    private fun isValidQq(value: String): Boolean = value.matches(Regex("\\d{5,12}"))

    private fun isValidOpenId(value: String): Boolean =
        value.matches(Regex("^(?=.*[A-F])(?=.*[0-9])[A-F0-9]{32}$"))
}
