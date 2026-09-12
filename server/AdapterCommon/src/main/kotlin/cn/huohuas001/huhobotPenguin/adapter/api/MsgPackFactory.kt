package cn.huohuas001.huhobotPenguin.adapter.api

import io.github.kloping.qqbot.api.v2.GroupMessageEvent

/** 将 QQ SDK 群消息转换为供平台事件和第三方插件使用的稳定快照。 */
fun GroupMessageEvent.toMsgPack(messageSequence: Int): MsgPack {
    val rawMessage = rawMessage
    val sender = sender
    return MsgPack(
        messageId = rawMessage.id.orEmpty(),
        groupOpenId = groupOpenId ?: groupId,
        groupId = groupId,
        sender = MsgPack.Sender(
            id = sender?.id,
            openId = sender?.openid,
            username = sender?.username ?: "unknown",
            role = sender?.role
        ),
        content = rawMessage.content.orEmpty(),
        rawContent = rawMessage.toString0(),
        timestamp = rawMessage.timestamp,
        messageSequence = messageSequence,
        mentions = mentions.orEmpty().map { mention ->
            MsgPack.Mention(
                id = mention.id,
                openId = mention.openid,
                username = mention.username ?: "unknown",
                role = mention.role
            )
        },
        attachments = rawMessage.attachments.orEmpty().map { attachment ->
            MsgPack.Attachment(
                id = attachment.id,
                filename = attachment.filename,
                url = attachment.url,
                contentType = attachment.content_type,
                size = attachment.size,
                width = attachment.width,
                height = attachment.height,
                asrReferText = attachment.asr_refer_text
            )
        }
    )
}

/** 返回带自定义命令键和参数的消息快照。 */
fun MsgPack.withCommand(rawInvocation: String): MsgPack {
    val invocation = rawInvocation
        .replace(Regex("<@!?[^>]+>"), "")
        .trim()
        .removePrefix("/")
        .trim()
    val parts = invocation.split(Regex("\\s+"), limit = 2)
    return copy(
        commandKey = parts.firstOrNull()?.takeIf(String::isNotBlank),
        commandArguments = parts.getOrNull(1).orEmpty()
    )
}
