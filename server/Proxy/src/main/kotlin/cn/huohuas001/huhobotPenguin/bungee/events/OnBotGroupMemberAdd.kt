package cn.huohuas001.huhobotPenguin.bungee.events

import cn.huohuas001.huhobotPenguin.adapter.api.GroupMemberPack
import net.md_5.bungee.api.plugin.Cancellable
import net.md_5.bungee.api.plugin.Event

/**
 * QQ 群成员加入时触发的 BungeeCord 事件。
 *
 * 需要配置项 `features.group-member-events` 打开并订阅 `GROUP_MEMBER_EVENT` 才会触发。
 */
class OnBotGroupMemberAdd(
    /** 群成员变更快照。 */
    val groupMember: GroupMemberPack
) : Event(), Cancellable {
    /** Java/Kotlin 插件使用的快照别名。 */
    val pack: GroupMemberPack
        get() = groupMember

    /** 是否成员加入事件，本事件恒为 true。 */
    val isJoined: Boolean
        get() = groupMember.joined

    private var cancelled = false

    override fun isCancelled(): Boolean = cancelled

    override fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }
}
