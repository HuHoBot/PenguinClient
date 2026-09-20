package cn.huohuas001.huhobotPenguin.adapter.api

import io.github.kloping.qqbot.api.event.InterActionEvent

/**
 * QQ 互动事件（消息按钮 / 快捷菜单回调）的跨平台不可变快照。
 *
 * 平台事件只暴露该稳定结构，不直接暴露 QQ SDK 的可变事件对象。
 *
 * @param eventId           平台方事件 ID，同时作为被动消息回复时的 msg_id
 * @param interactionType   11 消息按钮，12 单聊快捷菜单
 * @param scene             事件发生场景：c2c、group、guild
 * @param chatType          0 频道场景，1 群聊场景，2 单聊场景
 * @param groupOpenId       群聊场景的群 openid，其他场景为空串
 * @param groupMemberOpenId 群聊场景中点击按钮的群成员 openid
 * @param userOpenId        单聊场景中点击按钮的用户 openid
 * @param buttonId          被点击按钮的 id
 * @param buttonData        被点击按钮的 data
 * @param featureId         自定义菜单按钮的 id，仅快捷菜单提供
 * @param messageId         被操作的频道消息 id，仅频道场景提供
 * @param messageSequence   被动消息使用的 msg_seq
 * @param timestamp         触发时间，RFC 3339 格式
 */
data class InteractionPack(
    val eventId: String,
    val interactionType: Int?,
    val scene: String?,
    val chatType: Int?,
    val groupOpenId: String,
    val groupMemberOpenId: String?,
    val userOpenId: String?,
    val buttonId: String?,
    val buttonData: String?,
    val featureId: String?,
    val messageId: String?,
    val messageSequence: Int = 1,
    val timestamp: String?
) {
    /** 是否为群聊场景；本项目的回复能力仅在群聊场景可用。 */
    val isGroup: Boolean
        get() = chatType == null || chatType == 1 || scene == "group"
}

/** 将 QQ SDK 互动事件转换为供平台事件和第三方插件使用的稳定快照。 */
fun InterActionEvent.toInteractionPack(): InteractionPack {
    val interaction = interAction
    val resolved = interaction.data?.resolved
    return InteractionPack(
        eventId = interaction.id.orEmpty(),
        interactionType = interaction.type,
        scene = interaction.scene,
        chatType = interaction.chat_type,
        groupOpenId = interaction.group_openid.orEmpty(),
        groupMemberOpenId = interaction.group_member_openid,
        userOpenId = interaction.user_openid,
        buttonId = resolved?.button_id,
        buttonData = resolved?.button_data,
        featureId = resolved?.feature_id,
        messageId = resolved?.message_id,
        messageSequence = interaction.msgSeq ?: 1,
        timestamp = interaction.timestamp
    )
}
