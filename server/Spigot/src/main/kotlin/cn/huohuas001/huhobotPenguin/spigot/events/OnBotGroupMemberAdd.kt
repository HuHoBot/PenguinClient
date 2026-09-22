package cn.huohuas001.huhobotPenguin.spigot.events

import cn.huohuas001.huhobotPenguin.adapter.api.GroupMemberPack
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * QQ 群成员加入时触发的 Bukkit 事件。
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

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
