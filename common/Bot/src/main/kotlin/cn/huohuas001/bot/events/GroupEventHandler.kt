package cn.huohuas001.bot.events

import cn.huohuas001.bot.HuHoBot
import io.github.kloping.qqbot.api.v2.GroupJoinRequestEvent
import io.github.kloping.qqbot.api.v2.GroupMemberAddEvent
import io.github.kloping.qqbot.api.v2.GroupMemberRemoveEvent
import io.github.kloping.qqbot.impl.ListenerHost

/**
 * QQ 群成员进退群与入群申请事件监听入口。
 *
 * 这三个事件对应 QQ 开放平台的 `GROUP_MEMBER_ADD`、`GROUP_MEMBER_REMOVE` 与
 * `GROUP_JOIN_REQUEST`，都依赖 `GROUP_MEMBER_EVENT`（`1 << 24`）Intent。
 * 适配器只有在配置项 `features.group-member-events` 打开时才会订阅该 Intent，
 * 因此关闭时不会收到任何回调；入群申请事件还要求机器人为群管理员。
 *
 * 收到事件后交给当前平台触发插件事件，由插件决定是否处理（审批等操作可通过
 * [cn.huohuas001.bot.QClient] 的群管理接口完成）。
 */
class GroupEventHandler(
    private val plugin: HuHoBot
) : ListenerHost() {

    /** QQ 群成员加入时触发。 */
    @EventReceiver
    fun onGroupMemberAdd(event: GroupMemberAddEvent) {
        dispatch("群成员加入") { plugin.onBotGroupMemberAdd(event) }
    }

    /** QQ 群成员退出或被移出时触发。 */
    @EventReceiver
    fun onGroupMemberRemove(event: GroupMemberRemoveEvent) {
        dispatch("群成员退出") { plugin.onBotGroupMemberRemove(event) }
    }

    /** QQ 用户申请加群时触发。 */
    @EventReceiver
    fun onGroupJoinRequest(event: GroupJoinRequestEvent) {
        dispatch("入群申请") { plugin.onBotGroupJoinRequest(event) }
    }

    private inline fun dispatch(name: String, action: () -> Boolean) {
        try {
            action()
        } catch (error: Exception) {
            plugin.log_error("${name}事件处理异常: ${error.message}")
        }
    }
}
