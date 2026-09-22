package cn.huohuas001.huhobotPenguin.velocity.events

import cn.huohuas001.huhobotPenguin.adapter.api.GroupMemberPack

/**
 * QQ 群成员加入时触发的 Velocity 事件。
 *
 * 需要配置项 `features.group-member-events` 打开并订阅 `GROUP_MEMBER_EVENT` 才会触发。
 */
class OnBotGroupMemberAdd(
    /** 群成员变更快照。 */
    val groupMember: GroupMemberPack
) {
    /** Java/Kotlin 插件使用的快照别名。 */
    val pack: GroupMemberPack
        get() = groupMember

    /** 是否成员加入事件，本事件恒为 true。 */
    val isJoined: Boolean
        get() = groupMember.joined

    private var cancelled = false

    fun isCancelled(): Boolean = cancelled

    fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }
}
