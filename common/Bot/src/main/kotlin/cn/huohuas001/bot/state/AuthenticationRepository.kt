package cn.huohuas001.bot.state

import java.util.concurrent.ConcurrentHashMap

/** 保存各群 OpenID 与 QQ 号的认证绑定。 */
class AuthenticationRepository internal constructor(
    private val persist: () -> Unit
) {
    private val qqByGroup = ConcurrentHashMap<String, MutableMap<String, String>>()

    fun getBoundQq(groupId: String, userId: String): String? =
        qqByGroup[groupId]?.get(userId)

    fun contains(groupId: String, userId: String): Boolean =
        getBoundQq(groupId, userId) != null

    fun authenticate(groupId: String, userId: String, qq: String): Boolean {
        val users = qqByGroup.computeIfAbsent(groupId) { ConcurrentHashMap() }
        val changed = users[userId] != qq
        users[userId] = qq
        if (changed) persist()
        return changed
    }

    fun revoke(groupId: String, userId: String): Boolean {
        val changed = qqByGroup[groupId]?.remove(userId) != null
        if (qqByGroup[groupId].isNullOrEmpty()) qqByGroup.remove(groupId)
        if (changed) persist()
        return changed
    }

    internal fun replaceAll(values: Map<String, Map<String, String>>) {
        qqByGroup.clear()
        values.forEach { (groupId, users) ->
            qqByGroup[groupId] = ConcurrentHashMap<String, String>().apply { putAll(users) }
        }
    }

    internal fun snapshot(): Map<String, Map<String, String>> =
        qqByGroup.mapValues { (_, users) -> users.toMap() }
}
