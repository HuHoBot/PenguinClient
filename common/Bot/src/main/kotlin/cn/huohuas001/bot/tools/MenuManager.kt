package cn.huohuas001.bot

import cn.huohuas001.bot.events.commands.RegisteredCommand
import cn.huohuas001.bot.provider.BotShared
import cn.huohuas001.bot.state.GroupCommandConfig
import io.github.kloping.qqbot.http.PanelBase
import io.github.kloping.qqbot.http.data.PanelDefinition
import io.github.kloping.qqbot.http.data.PanelItem
import io.github.kloping.qqbot.http.data.PanelRequest
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

/**
 * 自动把已注册的 @Commands 同步为 QQ 群指令面板。
 *
 * 面板内容来自实际注册到 [cn.huohuas001.bot.events.GroupMessageHandler] 的处理器，
 * 不再维护一份容易过期的硬编码命令列表。
 */
object MenuManager {
    private const val PANEL_SCOPE = "group"
    private const val PANEL_REMARK = "HuHoBot Penguin 指令面板"
    private const val PANEL_STATE_FILE = "qq-panel-state.properties"
    private const val PANEL_MAX_ITEMS = 20
    private val EXCLUDED_PANEL_COMMANDS = setOf("blockMotd", "unblockMotd")

    private data class PanelState(
        val panelId: String?,
        val fingerprint: String?
    )

    private const val LEGACY_PANEL_KEY = "legacy"

    /**
     * 相同有效开关共用一个面板，不同群分别同步；恢复继承时清理不再使用的自有面板。
     * 只复用本插件持久化的 panelId，不接管其他面板。
     */
    @Synchronized
    fun syncGroupPanels(
        panelBase: PanelBase,
        groupOpenIds: List<String>,
        builtInCommands: Collection<RegisteredCommand>,
        customCommands: Collection<RegisteredCommand> = emptyList()
    ) {
        val plugin = BotShared.getPlugin()
        val groups = groupOpenIds.map(String::trim).filter(String::isNotEmpty).distinct().sorted()
        val customDefaults = plugin.getCustomCommands().associate { it.key to it.pushMenu }
        val desired = linkedMapOf<String, Pair<MutableList<String>, List<PanelItem>>>()
        for (group in groups) {
            val commands = panelCommandsForGroup(plugin, group, builtInCommands, customCommands, customDefaults)
            if (commands.size > PANEL_MAX_ITEMS) plugin.log_warning("群 $group 的面板超过 20 项，部分命令不会推送")
            val items = commands.take(PANEL_MAX_ITEMS).map { PanelItem(it.command, it.describe, it.onlyAdmin) }
            if (items.isEmpty()) continue
            val key = fingerprint(emptyList(), items)
            desired.getOrPut(key) { mutableListOf<String>() to items }.first.add(group)
        }
        val requests = linkedMapOf<String, Pair<List<String>, List<PanelItem>>>()
        desired.forEach { (key, bucket) ->
            bucket.first.chunked(20).forEachIndexed { index, targets ->
                requests["$key.$index"] = targets to bucket.second
            }
        }
        val remaining = try {
            loadState(plugin).toMutableMap()
        } catch (error: Exception) {
            plugin.log_error("无法读取 QQ 面板状态，取消同步以避免接管或丢失面板: ${error.message}")
            return
        }
        val synchronizedPanels = linkedMapOf<String, PanelState>()
        for ((key, bucket) in requests) {
            val spareKey = remaining.keys.firstOrNull { it !in requests }
            val previous = remaining.remove(key) ?: spareKey?.let { remaining.remove(it) }
            val panelGroups = bucket.first
            val items = bucket.second
            val contentFingerprint = fingerprint(panelGroups, items)
            var retainedState = previous
            try {
                val existingId = previous?.panelId
                val panelId = if (existingId != null && previous?.fingerprint == contentFingerprint) {
                    existingId
                } else {
                    val request = PanelRequest(PANEL_SCOPE, "specific", panelGroups, PanelDefinition(PANEL_REMARK, items))
                    if (existingId == null) {
                        panelBase.create(request).panelId?.trim()?.takeIf(String::isNotEmpty)
                            ?: throw IllegalStateException("QQ 未返回 panel_id")
                    } else {
                        // PUT changes content only; group associations require recreation.
                        panelBase.delete(existingId)
                        retainedState = null
                        panelBase.create(request).panelId?.trim()?.takeIf(String::isNotEmpty)
                            ?: throw IllegalStateException("QQ 未返回 panel_id")
                    }
                }
                synchronizedPanels[key] = PanelState(panelId, contentFingerprint)
                plugin.log_info("指令面板已同步 (panel_id=$panelId, groups=${panelGroups.size}, commands=${items.size})")
            } catch (error: Exception) {
                if (retainedState != null) synchronizedPanels[key] = retainedState
                plugin.log_error("群指令面板同步失败: ${error.message}")
            }
        }
        for ((key, state) in remaining) {
            try {
                state.panelId?.let(panelBase::delete)
            } catch (error: Exception) {
                synchronizedPanels[key] = state
                plugin.log_warning("清理过期指令面板失败: ${error.message}")
            }
        }
        saveState(plugin, synchronizedPanels)
    }

    internal fun panelCommandsForGroup(
        plugin: HuHoBot,
        groupId: String,
        builtInCommands: Collection<RegisteredCommand>,
        customCommands: Collection<RegisteredCommand>,
        customDefaults: Map<String, Boolean>
    ): List<RegisteredCommand> = eligiblePanelCommands(
        builtInCommands.filter { plugin.isCommandEnabled(groupId, it.command) &&
            GroupCommandConfig.pushMenu(plugin, groupId, it.command) },
        customCommands.filter { plugin.isCommandEnabled(groupId, it.command) &&
            GroupCommandConfig.pushMenu(plugin, groupId, it.command, customDefaults[it.command] ?: true) &&
            plugin.isCommandEnabled(groupId, if (it.onlyAdmin) "管理员执行" else "执行") }
    )

    /**
     * QQ 群指令面板最多允许 20 项：优先保留内置命令，再用自定义命令补足。
     * blockMotd/unblockMotd 仍可正常执行，但不会出现在面板中。
     */
    internal fun selectPanelCommands(
        builtInCommands: Collection<RegisteredCommand>,
        customCommands: Collection<RegisteredCommand>
    ): List<RegisteredCommand> = eligiblePanelCommands(builtInCommands, customCommands).take(PANEL_MAX_ITEMS)

    internal fun eligiblePanelCommands(
        builtInCommands: Collection<RegisteredCommand>,
        customCommands: Collection<RegisteredCommand>
    ): List<RegisteredCommand> {
        val builtIns = normalizeCommands(builtInCommands)
            .filterNot { it.command in EXCLUDED_PANEL_COMMANDS }
        val builtInNames = builtIns.mapTo(mutableSetOf()) { it.command }
        val customs = normalizeCommands(customCommands)
            .filterNot { it.command in EXCLUDED_PANEL_COMMANDS || it.command in builtInNames }
        return builtIns + customs
    }

    private fun normalizeCommands(commands: Collection<RegisteredCommand>): List<RegisteredCommand> = commands
        .asSequence()
        .map {
            RegisteredCommand(
                command = it.command.trim(),
                describe = it.describe.trim(),
                onlyAdmin = it.onlyAdmin
            )
        }
        .filter { it.command.isNotEmpty() }
        .distinctBy { it.command }
        .sortedBy { it.command }
        .toList()

    private fun fingerprint(groups: List<String>, items: List<PanelItem>): String {
        val canonical = buildString {
            append("scope:").append(PANEL_SCOPE).append('\n')
            append("target:specific\n")
            append("remark:").append(PANEL_REMARK).append('\n')
            groups.map(String::trim).filter(String::isNotEmpty).distinct().sorted().forEach {
                append("group:").append(it.length).append(':').append(it).append('\n')
            }
            items.forEach { item ->
                append("item:").append(item.name.orEmpty().length).append(':').append(item.name.orEmpty())
                    .append('|').append(item.desc.orEmpty().length).append(':').append(item.desc.orEmpty())
                    .append('|').append(item.isOnlyAdmin).append('\n')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun loadState(plugin: HuHoBot): Map<String, PanelState> {
        val file = stateFile(plugin) ?: return emptyMap()
        if (!file.isFile) return emptyMap()
        val properties = Properties()
        file.inputStream().use(properties::load)
        val states = linkedMapOf<String, PanelState>()
        properties.getProperty("panel-id")?.trim()?.takeIf(String::isNotEmpty)?.let {
            states[LEGACY_PANEL_KEY] = PanelState(it, properties.getProperty("fingerprint"))
        }
        properties.stringPropertyNames().filter { it.startsWith("panel.") && it.endsWith(".id") }.forEach { name ->
            val key = name.removePrefix("panel.").removeSuffix(".id")
            val id = properties.getProperty(name)?.trim()?.takeIf(String::isNotEmpty) ?: return@forEach
            states[key] = PanelState(id, properties.getProperty("panel.$key.fingerprint"))
        }
        return states
    }

    private fun saveState(plugin: HuHoBot, states: Map<String, PanelState>) {
        val file = stateFile(plugin) ?: return
        try {
            val parent = file.parentFile ?: return
            if (!parent.exists() && !parent.mkdirs()) {
                plugin.log_warning("无法创建 QQ 指令面板状态目录: ${parent.path}")
                return
            }

            val properties = Properties()
            states.forEach { (key, state) ->
                properties.setProperty("panel.$key.id", state.panelId.orEmpty())
                properties.setProperty("panel.$key.fingerprint", state.fingerprint.orEmpty())
            }
            val temporary = Files.createTempFile(parent.toPath(), "$PANEL_STATE_FILE.", ".tmp").toFile()
            try {
                temporary.outputStream().use { properties.store(it, "HuHoBot QQ panel state") }
                try {
                    Files.move(
                        temporary.toPath(),
                        file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(
                        temporary.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
            } finally {
                temporary.delete()
            }
        } catch (error: Exception) {
            plugin.log_warning("保存 QQ 指令面板状态失败: ${error.message}")
        }
    }

    private fun stateFile(plugin: cn.huohuas001.bot.HuHoBot) =
        plugin.getConfigFile()?.absoluteFile?.parentFile?.resolve(PANEL_STATE_FILE)
}
