package cn.huohuas001.bot.state

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.provider.ManagementConfig
import com.google.gson.JsonParser
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/** Group overrides affect command execution and panels, never administrator authorization. */
object GroupCommandConfig {
    @Volatile private var overrides: Map<String, Map<String, Any?>> = emptyMap()
    @Volatile private var groupsDirectory: File? = null
    private val yaml = Yaml(SafeConstructor(LoaderOptions()))
    private val yamlWriter = Yaml(DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        isPrettyFlow = true
    })

    @Synchronized
    fun initialize(directory: File?) {
        val root = directory?.resolve("groups")
        val loaded = linkedMapOf<String, Map<String, Any?>>()
        directory?.resolve("group-command-overrides.json")?.takeIf(File::isFile)?.let(::readLegacy)
            ?.forEach { (group, values) -> loaded[group] = values }
        root?.listFiles()?.filter(File::isDirectory)?.forEach { groupDir ->
            val config = groupDir.resolve("config.yml")
            if (!config.isFile) return@forEach
            val values = readConfig(config)
            val group = values["group-open-id"] as? String
                ?: throw IllegalArgumentException("群配置缺少 group-open-id: ${config.path}")
            require(groupDirectory(root, group) == groupDir) { "群配置目录与 group-open-id 不匹配: ${config.path}" }
            loaded[group] = values
        }
        groupsDirectory = root
        overrides = loaded
    }

    private fun groupDirectory(root: File?, groupId: String): File? {
        root ?: return null
        val safeId = if (groupId.matches(Regex("[A-Za-z0-9._-]+")) &&
            groupId != "." && groupId != ".." && !groupId.startsWith("encoded-")) {
            groupId
        } else {
            "encoded-" + Base64.getUrlEncoder().withoutPadding().encodeToString(groupId.toByteArray(Charsets.UTF_8))
        }
        return root.resolve(safeId)
    }

    private fun groupDirectory(groupId: String): File? = groupDirectory(groupsDirectory, groupId)

    fun ensureGroup(groupId: String) {
        if (groupId.isBlank() || overrides.containsKey(groupId)) return
        synchronized(this) {
            if (!overrides.containsKey(groupId)) replace(groupId, emptyMap())
        }
    }

    fun enabled(plugin: HuHoBot, groupId: String, command: String): Boolean =
        commandSettings(groupId, command)["enable"] as? Boolean ?: (plugin.getCommandList()[command] != false)

    fun pushMenu(plugin: HuHoBot, groupId: String, command: String, default: Boolean = true): Boolean =
        commandSettings(groupId, command)["pushMenu"] as? Boolean ?: plugin.getCommandMenuList()[command] ?: default

    fun priority(plugin: HuHoBot, groupId: String, command: String): Int {
        val override = commandSettings(groupId, command)["priority"] as? Number
        return override?.toInt() ?: plugin.getCommandMenuPriorities()[command] ?: 100
    }

    fun showAdminCommands(plugin: HuHoBot, groupId: String): Boolean =
        ((overrides[groupId]?.get("command-panel") as? Map<*, *>)?.get("show-admin-commands") as? Boolean)
            ?: plugin.showAdminCommandsInMenu()

    fun snapshot(): Map<String, Map<String, Any?>> = overrides

    fun effectiveValues(plugin: HuHoBot, groupId: String): Map<String, Any?> {
        val names = linkedSetOf<String>()
        names.addAll(plugin.getCommandList().keys)
        names.addAll(plugin.getCommandMenuList().keys)
        names.addAll(plugin.getCommandMenuPriorities().keys)
        val custom = plugin.getCustomCommands().associateBy { it.key }
        names.addAll(custom.keys)
        names.addAll((overrides[groupId]?.get("commands") as? Map<*, *>)?.keys?.filterIsInstance<String>().orEmpty())
        return mapOf(
            "commands" to names.associateWith { name -> mapOf(
                "enable" to enabled(plugin, groupId, name),
                "pushMenu" to pushMenu(plugin, groupId, name, custom[name]?.pushMenu ?: true),
                "priority" to priority(plugin, groupId, name)
            ) },
            "command-panel" to mapOf("show-admin-commands" to showAdminCommands(plugin, groupId))
        )
    }
    fun overridesFor(plugin: HuHoBot, groupId: String, effective: Map<String, Any?>): Map<String, Any?> {
        require(effective.keys.all { it == "commands" || it == "command-panel" }) {
            "按群只允许覆盖命令与面板配置"
        }
        val suppliedCommands = effective["commands"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val globalCommands = plugin.getCommandList()
        val globalMenus = plugin.getCommandMenuList()
        val globalPriorities = plugin.getCommandMenuPriorities()
        val customDefaults = plugin.getCustomCommands().associate { it.key to it.pushMenu }
        val validCommands = (globalCommands.keys + globalMenus.keys + globalPriorities.keys + customDefaults.keys).toSet()
        val commands = linkedMapOf<String, Map<String, Any>>()
        for ((rawName, rawSettings) in suppliedCommands) {
            require(rawName is String && rawName.isNotBlank() && rawSettings is Map<*, *>) {
                "commands 格式不正确"
            }
            val name = rawName
            require(name in validCommands) { "未知命令: $name" }
            require(rawSettings.keys.all { it == "enable" || it == "pushMenu" || it == "priority" }) {
                "群命令只允许 enable / pushMenu / priority"
            }
            val settings = linkedMapOf<String, Any>()
            if (rawSettings.containsKey("enable")) {
                val enabled = rawSettings["enable"]
                require(enabled is Boolean) { "enable 必须为布尔值" }
                if (enabled != (globalCommands[name] != false)) settings["enable"] = enabled
            }
            if (rawSettings.containsKey("pushMenu")) {
                val pushMenu = rawSettings["pushMenu"]
                require(pushMenu is Boolean) { "pushMenu 必须为布尔值" }
                if (pushMenu != (globalMenus[name] ?: customDefaults[name] ?: true)) settings["pushMenu"] = pushMenu
            }
            if (rawSettings.containsKey("priority")) {
                val priority = rawSettings["priority"]
                require(priority is Number && priority.toDouble() in 0.0..999.0 && priority.toDouble() == priority.toInt().toDouble()) {
                    "priority 必须为 0–999 整数"
                }
                val configured = globalPriorities[name] ?: 100
                if (priority.toInt() != configured) settings["priority"] = priority.toInt()
            }
            if (settings.isNotEmpty()) commands[name] = settings
        }
        val suppliedPanel = effective["command-panel"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        require(suppliedPanel.keys.all { it == "show-admin-commands" }) {
            "command-panel 只允许 show-admin-commands"
        }
        val showAdmin = suppliedPanel["show-admin-commands"]
        if (suppliedPanel.containsKey("show-admin-commands")) require(showAdmin is Boolean) {
            "show-admin-commands 必须为布尔值"
        }
        val panel = if (showAdmin != null && showAdmin != plugin.showAdminCommandsInMenu()) {
            mapOf("show-admin-commands" to showAdmin)
        } else emptyMap()
        return buildMap {
            if (commands.isNotEmpty()) put("commands", commands)
            if (panel.isNotEmpty()) put("command-panel", panel)
        }
    }
    /** Replace the group's overrides; omitted settings inherit global values. */
    @Synchronized
    fun replace(groupId: String, values: Map<String, Any?>): Boolean {
        require(groupId.isNotBlank()) { "群 OpenID 不能为空" }
        require(values.keys.all { it == "commands" || it == "command-panel" }) {
            "按群只允许覆盖命令与面板配置"
        }
        val commands = parseCommands(values["commands"] ?: emptyMap<String, Any?>())
        val panel = parsePanel(values["command-panel"] ?: emptyMap<String, Any?>())
        val normalized = linkedMapOf<String, Any?>()
        if (commands.isNotEmpty()) normalized["commands"] = commands
        if (panel.isNotEmpty()) normalized["command-panel"] = panel

        val updated = overrides.toMutableMap().apply { put(groupId, normalized) }
        val directory = groupDirectory(groupId) ?: throw IllegalStateException("无法确定群配置目录")
        val target = directory.resolve("config.yml")
        Files.createDirectories(directory.toPath())
        val temporary = Files.createTempFile(directory.toPath(), "config.", ".tmp")
        try {
            val persisted = linkedMapOf<String, Any?>("group-open-id" to groupId).apply { putAll(normalized) }
            Files.newBufferedWriter(temporary, Charsets.UTF_8).use { yamlWriter.dump(persisted, it) }
            try {
                Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            overrides = updated
            return true
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun commandSettings(groupId: String, command: String): Map<*, *> =
        ((overrides[groupId]?.get("commands") as? Map<*, *>)?.get(command) as? Map<*, *>).orEmpty()

    private fun parseCommands(raw: Any?): Map<String, Map<String, Any>> {
        require(raw is Map<*, *>) { "commands 必须为对象" }
        return raw.entries.associate { (rawName, rawSettings) ->
            require(rawName is String && rawName.isNotBlank()) { "命令名不能为空" }
            require(rawSettings is Map<*, *>) { "命令配置必须为对象" }
            rawName to rawSettings.entries.associate { (rawKey, value) ->
                require(rawKey is String && rawKey in setOf("enable", "pushMenu", "priority")) {
                    "群命令只允许 enable / pushMenu / priority"
                }
                when (rawKey) {
                    "enable", "pushMenu" -> {
                        require(value is Boolean) { "$rawKey 必须为布尔值" }
                        rawKey to value
                    }
                    else -> {
                        require(value is Number && value.toDouble() == value.toInt().toDouble() && value.toInt() in 0..999) {
                            "priority 必须是 0–999 的整数"
                        }
                        rawKey to value.toInt()
                    }
                }
            }
        }.filterValues { it.isNotEmpty() }
    }

    private fun parsePanel(raw: Any?): Map<String, Any> {
        require(raw is Map<*, *>) { "command-panel 必须为对象" }
        return raw.entries.associate { (key, value) ->
            require(key == "show-admin-commands" && value is Boolean) {
                "command-panel 只允许 show-admin-commands 布尔值"
            }
            key.toString() to value
        }
    }

    private fun readConfig(file: File): Map<String, Any?> {
        val root = yaml.load<Any?>(file.readText(Charsets.UTF_8)) as? Map<*, *> ?: emptyMap<Any?, Any?>()
        return root.entries.associate { (key, value) -> key.toString() to normalize(value) }
    }

    private fun normalize(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associate { (key, child) -> key.toString() to normalize(child) }
        is List<*> -> value.map(::normalize)
        else -> value
    }

    private fun readLegacy(file: File): Map<String, Map<String, Any?>> {
        val root = JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonObject
        return root.entrySet().associate { (groupId, rawCommands) ->
            require(rawCommands.isJsonObject) { "旧群命令配置格式不正确" }
            groupId to mapOf("commands" to rawCommands.asJsonObject.entrySet().associate { (name, rawSettings) ->
                require(rawSettings.isJsonObject) { "旧群命令开关格式不正确" }
                name to rawSettings.asJsonObject.entrySet().associate { (key, value) ->
                    require(key in setOf("enable", "pushMenu") && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) {
                        "旧群命令开关必须为 enable / pushMenu 布尔值"
                    }
                    key to value.asBoolean
                }
            })
        }
    }

}
