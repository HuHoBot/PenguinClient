package cn.huohuas001.huhobotPenguin.bungee.events

import cn.huohuas001.huhobotPenguin.adapter.api.JoinRequestPack
import net.md_5.bungee.api.plugin.Cancellable
import net.md_5.bungee.api.plugin.Event

/**
 * QQ 用户申请加群时触发的 BungeeCord 事件。
 *
 * 需要配置项 `features.group-member-events` 打开并订阅 `GROUP_MEMBER_EVENT`，
 * 且机器人为群管理员时才会触发。可通过 [approve] / [decline] 直接调用入群申请审批接口。
 */
class OnBotJoinRequest(
    /** 入群申请快照。 */
    val request: JoinRequestPack,
    private val approveAction: (String?) -> Boolean,
    private val declineAction: (String?, String?, Boolean) -> Boolean
) : Event(), Cancellable {
    constructor(request: JoinRequestPack) : this(request, { false }, { _, _, _ -> false })

    /** Java/Kotlin 插件使用的快照别名。 */
    val pack: JoinRequestPack
        get() = request

    private var cancelled = false
    private var responded = false

    override fun isCancelled(): Boolean = cancelled

    override fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }

    /** 本条申请是否已经处理过（无论审批是否提交成功）。 */
    fun isResponded(): Boolean = responded

    /** 通过该入群申请。 */
    fun approve(): Boolean = respond { approveAction(request.joinRequestId) }

    /** 拒绝该入群申请。 */
    fun decline(): Boolean = decline(null, false)

    /** 拒绝该入群申请并附带拒绝理由。 */
    fun decline(rejectReason: String?): Boolean = decline(rejectReason, false)

    /**
     * 拒绝该入群申请。
     *
     * @param rejectReason 拒绝理由，可空
     * @param addToMemberBlacklist 是否同时加入群黑名单
     * @return 是否成功提交；重复处理同一申请时返回 false
     */
    fun decline(rejectReason: String?, addToMemberBlacklist: Boolean): Boolean =
        respond { declineAction(request.joinRequestId, rejectReason, addToMemberBlacklist) }

    private fun respond(action: () -> Boolean): Boolean {
        if (responded) return false
        responded = true
        return action()
    }
}
