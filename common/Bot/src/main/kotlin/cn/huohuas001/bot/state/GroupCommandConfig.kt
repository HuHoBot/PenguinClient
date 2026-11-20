package cn.huohuas001.bot.state

import cn.huohuas001.bot.HuHoBot
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Group overrides affect execution and panels, never administrator authorization. */
object GroupCommandConfig {
    @Volatile private var overrides: Map<String, Map<String, Map<String, Boolean>>> = emptyMap()
    private var file: File? = null
    private val gson = Gson()
    private val switches = setOf("enable", "pushMenu")

    @Synchronized
    fun initialize(directory: File?) {
        val target = directory?.resolve("group-command-overrides.json")
        val loaded = if (target?.isFile == true) {
            val root = JsonParser.parseString(target.readText(Charsets.UTF_8))
            require(root.isJsonObject) { "群命令配置必须为 JSON 对象" }
            root.asJsonObject.entrySet().associate { (group, commands) ->
                require(commands.isJsonObject) { "群命令配置格式不正确" }
                group to commands.asJsonObject.entrySet().associate { (name, settings) ->
                    require(settings.isJsonObject) { "群命令开关格式不正确" }
                    name to settings.asJsonObject.entrySet().associate { (key, value) ->
                        require(key in switches && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) {
                            "群命令开关必须为 enable / pushMenu 布尔值"
                        }
                        key to value.asBoolean
                    }
                }
            }
        } else emptyMap()
        file = target
        overrides = loaded
    }

    fun enabled(plugin: HuHoBot, groupId: String, command: String): Boolean =
        overrides[groupId]?.get(command)?.get("enable") ?: (plugin.getCommandList()[command] != false)

    fun pushMenu(plugin: HuHoBot, groupId: String, command: String, default: Boolean = true): Boolean =
        overrides[groupId]?.get(command)?.get("pushMenu") ?: plugin.getCommandMenuList()[command] ?: default

    fun effectiveValues(plugin: HuHoBot, groupId: String): Map<String, Any?> {
        val names = linkedSetOf<String>()
        names.addAll(plugin.getCommandList().keys)
        names.addAll(plugin.getCommandMenuList().keys)
        names.addAll(plugin.getCustomCommands().map { it.key })
        names.addAll(overrides[groupId].orEmpty().keys)
        val custom = plugin.getCustomCommands().associateBy { it.key }
        return mapOf("commands" to names.associateWith { name ->
            mapOf("enable" to enabled(plugin, groupId, name),
                "pushMenu" to pushMenu(plugin, groupId, name, custom[name]?.pushMenu ?: true))
        })
    }

    /** Replace the group's overrides; empty values restore global inheritance. */
    @Synchronized
    fun replace(groupId: String, values: Map<String, Any?>): Boolean {
        require(groupId.isNotBlank()) { "群 OpenID 不能为空" }
        require(values.keys.all { it == "commands" }) { "按群只允许覆盖命令与面板开关" }
        val raw = values["commands"] ?: emptyMap<String, Any?>()
        require(raw is Map<*, *>) { "commands 必须为对象" }
        val commands = raw.entries.associate { (name, rawSettings) ->
            require(name is String && name.isNotBlank()) { "命令名不能为空" }
            require(rawSettings is Map<*, *>) { "命令开关必须为对象" }
            name to rawSettings.entries.associate { (key, value) ->
                require(key is String && key in switches && value is Boolean) { "命令开关必须为布尔值" }
                key to value
            }
        }.filterValues { it.isNotEmpty() }
        val updated = overrides.toMutableMap()
        if (commands.isEmpty()) updated.remove(groupId) else updated[groupId] = commands
        val target = file ?: throw IllegalStateException("无法确定群命令配置目录")
        val directory = target.absoluteFile.parentFile
        Files.createDirectories(directory.toPath())
        val temporary = Files.createTempFile(directory.toPath(), "group-command-overrides.", ".tmp")
        try {
            Files.newBufferedWriter(temporary, Charsets.UTF_8).use { gson.toJson(updated, it) }
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
}
