package cn.huohuas001.bot.state

import cn.huohuas001.bot.datapack.AdministratorAccessMode
import java.util.concurrent.ConcurrentHashMap

/** 保存每个群独立覆盖的管理员模式、全量转发开关和 MOTD 屏蔽状态。 */
class GroupSettingsRepository internal constructor(
    private val persist: () -> Unit
) {
    private val administratorModes = ConcurrentHashMap<String, AdministratorAccessMode>()
    private val fullForwarding = ConcurrentHashMap<String, Boolean>()
    private val motdBlocked = ConcurrentHashMap<String, Boolean>()

    fun administratorMode(
        groupId: String,
        default: AdministratorAccessMode
    ): AdministratorAccessMode = administratorModes[groupId] ?: default

    fun setAdministratorMode(groupId: String, mode: AdministratorAccessMode) {
        if (administratorModes.put(groupId, mode) != mode) persist()
    }

    fun fullForwarding(groupId: String, default: Boolean): Boolean =
        fullForwarding[groupId] ?: default

    fun setFullForwarding(groupId: String, enabled: Boolean) {
        if (fullForwarding.put(groupId, enabled) != enabled) persist()
    }

    fun isMotdBlocked(groupId: String): Boolean = motdBlocked[groupId] == true

    fun setMotdBlocked(groupId: String, blocked: Boolean) {
        val changed = if (blocked) {
            motdBlocked.put(groupId, true) != true
        } else {
            motdBlocked.remove(groupId) != null
        }
        if (changed) persist()
    }

    internal fun replaceAll(
        modes: Map<String, AdministratorAccessMode>,
        forwarding: Map<String, Boolean>,
        blockedMotdGroups: Map<String, Boolean>
    ) {
        administratorModes.clear()
        administratorModes.putAll(modes)
        fullForwarding.clear()
        fullForwarding.putAll(forwarding)
        motdBlocked.clear()
        motdBlocked.putAll(blockedMotdGroups.filterValues { it })
    }

    internal fun administratorModeSnapshot(): Map<String, AdministratorAccessMode> =
        administratorModes.toMap()

    internal fun fullForwardingSnapshot(): Map<String, Boolean> =
        fullForwarding.toMap()

    internal fun motdBlockedSnapshot(): Map<String, Boolean> =
        motdBlocked.toMap()
}
