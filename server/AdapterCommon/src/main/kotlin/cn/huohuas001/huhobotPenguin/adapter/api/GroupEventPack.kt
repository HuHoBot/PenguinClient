package cn.huohuas001.huhobotPenguin.adapter.api

import io.github.kloping.qqbot.api.v2.GroupJoinRequestEvent
import io.github.kloping.qqbot.api.v2.GroupMemberAddEvent
import io.github.kloping.qqbot.api.v2.GroupMemberEvent
import io.github.kloping.qqbot.api.v2.GroupMemberRemoveEvent

/**
 * QQ 群成员进退群事件的跨平台不可变快照。
 *
 * 平台事件只暴露该稳定结构，不直接暴露 QQ SDK 的可变事件对象。
 *
 * @param groupOpenId 群 OpenID
 * @param groupId     群 ID，SDK 未提供时为 null
 * @param joined      true 表示成员加入，false 表示成员退出或被移出
 * @param memberOpenId 发生加入或退出的群成员 OpenID
 * @param userOpenId  成员的用户 OpenID（跨应用统一标识，可能为空）
 * @param timestamp   事件时间戳，Unix 秒
 */
data class GroupMemberPack(
    val groupOpenId: String,
    val groupId: String?,
    val joined: Boolean,
    val memberOpenId: String?,
    val userOpenId: String?,
    val timestamp: Long?
)

/**
 * QQ 入群申请事件的跨平台不可变快照。
 *
 * 需要 `GROUP_MEMBER_EVENT`（`1 << 24`）Intent，且机器人为群管理员时才会触发。
 *
 * @param groupOpenId           群 OpenID
 * @param groupId               群 ID，SDK 未提供时为 null
 * @param joinRequestId         申请 ID，审批时需要回传
 * @param memberOpenId          申请人 OpenID
 * @param unionOpenId           申请人在应用/开放平台下的统一标识
 * @param username              申请人昵称
 * @param applyAt               申请时间，RFC 3339 格式
 * @param applySource           申请来源：self_apply 主动申请 / invited 被邀请
 * @param invitedBy             邀请人 OpenID，仅被邀请场景提供
 * @param riskTips              安全提示语
 * @param bot                   申请人是否为机器人账号
 * @param verifyMethod          入群验证方式：verify_message / admin_review_qa
 * @param verifyMessage         验证消息内容
 * @param answers               验证答案列表（问答验证取全部答案，普通验证取验证消息）
 * @param autoApprovedStrategyId 自动审批通过的策略 ID，非自动审批场景为空
 */
data class JoinRequestPack(
    val groupOpenId: String,
    val groupId: String?,
    val joinRequestId: String?,
    val memberOpenId: String?,
    val unionOpenId: String?,
    val username: String?,
    val applyAt: String?,
    val applySource: String?,
    val invitedBy: String?,
    val riskTips: String?,
    val bot: Boolean?,
    val verifyMethod: String?,
    val verifyMessage: String?,
    val answers: List<String>,
    val autoApprovedStrategyId: String?
)

/** 将 QQ SDK 群成员加入事件转换为供平台事件和第三方插件使用的稳定快照。 */
fun GroupMemberAddEvent.toGroupMemberPack(): GroupMemberPack =
    buildGroupMemberPack(this, joined = true)

/** 将 QQ SDK 群成员退出事件转换为供平台事件和第三方插件使用的稳定快照。 */
fun GroupMemberRemoveEvent.toGroupMemberPack(): GroupMemberPack =
    buildGroupMemberPack(this, joined = false)

private fun buildGroupMemberPack(
    event: GroupMemberEvent,
    joined: Boolean
): GroupMemberPack = GroupMemberPack(
    groupOpenId = event.groupOpenId.orEmpty(),
    groupId = event.groupId,
    joined = joined,
    memberOpenId = event.memberOpenid,
    userOpenId = event.userOpenid,
    timestamp = event.timestamp
)

/** 将 QQ SDK 入群申请事件转换为供平台事件和第三方插件使用的稳定快照。 */
fun GroupJoinRequestEvent.toJoinRequestPack(): JoinRequestPack {
    val request = joinRequest
    val verifyInfo = request?.verifyInfo
    return JoinRequestPack(
        groupOpenId = groupOpenId.orEmpty(),
        groupId = groupId,
        joinRequestId = request?.joinRequestId,
        memberOpenId = request?.memberOpenid,
        unionOpenId = request?.unionOpenid,
        username = request?.username,
        applyAt = request?.applyAt,
        applySource = request?.applySource,
        invitedBy = request?.invitedBy,
        riskTips = request?.riskTips,
        bot = request?.bot,
        verifyMethod = verifyInfo?.method,
        verifyMessage = verifyInfo?.verifyMessage,
        answers = request?.answers.orEmpty(),
        autoApprovedStrategyId = request?.autoApproved?.strategyId
    )
}
