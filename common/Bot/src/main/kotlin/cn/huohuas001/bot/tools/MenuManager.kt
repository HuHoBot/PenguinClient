package cn.huohuas001.bot

import cn.huohuas001.bot.events.commands.RegisteredCommand
import cn.huohuas001.bot.provider.BotShared
import io.github.kloping.qqbot.Starter
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

    private data class PanelState(
        val panelId: String?,
        val fingerprint: String?
    )

    @Volatile
    private var activePanelId: String? = null

    /**
     * 首次同步时复用已有群面板，没有已有面板才创建；后续同步始终更新同一个 panelId。
     * 面板同步失败只记录日志，不影响 QQ 客户端启动。
     */
    @Synchronized
    fun syncGroupPanels(
        starter: Starter,
        groupOpenIds: List<String>,
        commands: Collection<RegisteredCommand>
    ) {
        val plugin = BotShared.getPlugin()
        val groups = groupOpenIds.map(String::trim).filter(String::isNotEmpty).distinct()
        if (groups.isEmpty()) return

        val items = commands
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
            .map { PanelItem(it.command, it.describe, it.onlyAdmin) }
            .toList()
        if (items.isEmpty()) {
            plugin.log_warning("没有已注册的 QQ 命令，跳过指令面板同步")
            return
        }

        try {
            val panelBase = starter.bot.panelBase
            val request = PanelRequest(
                PANEL_SCOPE,
                "specific",
                groups,
                PanelDefinition(PANEL_REMARK, items)
            )
            val fingerprint = fingerprint(groups, items)
            val persistedState = loadState(plugin)

            val panelId = activePanelId ?: persistedState.panelId
            if (panelId != null && persistedState.panelId == panelId && persistedState.fingerprint == fingerprint) {
                activePanelId = panelId
                plugin.log_info("指令面板内容未变化，跳过同步 (panel_id=$panelId)")
                return
            }

            val resolvedPanelId = panelId
                ?: panelBase.list(PANEL_SCOPE, 50).records.orEmpty()
                    .firstNotNullOfOrNull { it.panelId?.trim()?.takeIf(String::isNotEmpty) }

            val syncedPanelId = if (resolvedPanelId == null) {
                panelBase.create(request).panelId?.trim()?.takeIf(String::isNotEmpty)
            } else {
                panelBase.update(resolvedPanelId, request)
                resolvedPanelId
            }
            activePanelId = syncedPanelId
            if (syncedPanelId != null) {
                saveState(plugin, PanelState(syncedPanelId, fingerprint))
            }
            plugin.log_info(
                "指令面板已同步 (panel_id=${syncedPanelId ?: "unknown"}, commands=${items.size})"
            )
        } catch (error: Exception) {
            BotShared.getPlugin().log_error("指令面板同步失败: ${error.message}")
        }
    }

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

    private fun loadState(plugin: cn.huohuas001.bot.HuHoBot): PanelState {
        val file = stateFile(plugin) ?: return PanelState(null, null)
        if (!file.isFile) return PanelState(null, null)

        return try {
            val properties = Properties()
            file.inputStream().use(properties::load)
            PanelState(
                panelId = properties.getProperty("panel-id")?.trim()?.takeIf(String::isNotEmpty),
                fingerprint = properties.getProperty("fingerprint")?.trim()?.takeIf(String::isNotEmpty)
            )
        } catch (error: Exception) {
            plugin.log_warning("读取 QQ 指令面板状态失败: ${error.message}")
            PanelState(null, null)
        }
    }

    private fun saveState(plugin: cn.huohuas001.bot.HuHoBot, state: PanelState) {
        val file = stateFile(plugin) ?: return
        try {
            val parent = file.parentFile ?: return
            if (!parent.exists() && !parent.mkdirs()) {
                plugin.log_warning("无法创建 QQ 指令面板状态目录: ${parent.path}")
                return
            }

            val properties = Properties()
            properties.setProperty("panel-id", state.panelId.orEmpty())
            properties.setProperty("fingerprint", state.fingerprint.orEmpty())
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
