package cn.huohuas001.huhobotPenguin.adapter.config

import cn.huohuas001.bot.provider.AdminMode
import cn.huohuas001.bot.provider.ChatFormat
import cn.huohuas001.bot.provider.CustomCommandDetail
import cn.huohuas001.bot.provider.Motd
import cn.huohuas001.bot.provider.PlayerEventFormat
import cn.huohuas001.bot.provider.WhiteList
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.InputStream

/**
 * Allay、Nukkit 与代理端共享的轻量 YAML 配置读取器。
 *
 * 平台适配器保持同一套配置键；首次启动复制带注释的默认配置。已有配置会定点补充
 * 需要显式展示的新开关，其他缺失项仍由强类型 getter 的默认值兜底。
 */
class YamlConfig(
    val file: File,
    private val defaultPort: Int,
    private val logger: (String) -> Unit
) {
    @Volatile
    private var values: Map<String, Any?> = emptyMap()

    fun initialize(defaultConfig: () -> InputStream?) {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            val input = defaultConfig()
                ?: throw IllegalStateException("找不到默认配置资源 config.yml")
            input.use { source -> file.outputStream().use(source::copyTo) }
        }
        reload()
        ensureAuthenticationOption()
        ensureAlwaysForwardPlayerEventsOption()
        ensureGroupMemberEventsOption()
        ensureConfigVersion()
    }

    @Synchronized
    fun reload() {
        val yaml = Yaml(DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        })
        val loaded = file.inputStream().buffered().use { input -> yaml.load<Any?>(input) }
        values = normalizeMap(loaded as? Map<*, *> ?: emptyMap<Any?, Any?>())
    }

    /** 补充新增配置项，不重写用户已有配置和注释。 */
    private fun ensureAuthenticationOption() =
        ensureSectionOption(
            section = "features",
            key = "enable-auth",
            comment = "是否启用 QQ 头像认证功能。",
            defaultValue = "true"
        )

    /** 为旧配置补充进退服事件的强制转发开关。 */
    private fun ensureAlwaysForwardPlayerEventsOption() =
        ensureSectionOption(
            section = "player-events",
            key = "always-forward",
            comment = "是否忽略平台的隐藏、取消或登录状态判断，始终转发进退服事件。",
            defaultValue = "false"
        )

    /** 为旧配置补充群成员事件订阅开关。 */
    private fun ensureGroupMemberEventsOption() =
        ensureSectionOption(
            section = "features",
            key = "group-member-events",
            comment = "是否订阅群成员进退群与入群申请事件（GROUP_MEMBER_EVENT），需要 QQ 机器人具备群管理权限。",
            defaultValue = "false"
        )

    /**
     * 在指定顶层段的开头补充一个新的配置项，不重写用户已有配置和注释。
     *
     * 顶层段不存在时会追加到文件末尾（必要时补一个空行）。
     */
    private fun ensureSectionOption(
        section: String,
        key: String,
        comment: String,
        defaultValue: String
    ) {
        if (node("$section.$key") != null) return

        val original = file.readText(Charsets.UTF_8)
        val newline = if (original.contains("\r\n")) "\r\n" else "\n"
        val lines = original.split(Regex("\\r?\\n")).toMutableList()
        val sectionIndex = lines.indexOfFirst { it.trim() == "$section:" }
        if (sectionIndex >= 0) {
            lines.add(sectionIndex + 1, "  # $comment")
            lines.add(sectionIndex + 2, "  $key: $defaultValue")
        } else {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.add("$section:")
            lines.add("  # $comment")
            lines.add("  $key: $defaultValue")
        }
        file.writeText(lines.joinToString(newline), Charsets.UTF_8)
        logger("已自动补充配置项: $section.$key=$defaultValue")
        reload()
    }

    /** 更新版本号，同时保留已有配置的结构和注释。 */
    private fun ensureConfigVersion() {
        val original = file.readText(Charsets.UTF_8)
        val versionLine = Regex("(?m)^config-version\\s*:\\s*.*$")
        val updated = if (versionLine.containsMatchIn(original)) {
            original.replaceFirst(versionLine, "config-version: $CURRENT_CONFIG_VERSION")
        } else {
            "config-version: $CURRENT_CONFIG_VERSION${if (original.isEmpty()) "" else "\n\n"}$original"
        }
        if (updated == original) return

        file.writeText(updated, Charsets.UTF_8)
        logger("配置文件已升级到版本 $CURRENT_CONFIG_VERSION")
        reload()
    }

    fun botAppId(): String = string("bot.app-id")
    fun botSecret(): String = string("bot.secret")

    /**
     * 扫码绑定成功后写回 `bot.app-id` / `bot.secret`。
     *
     * 采用定点行替换而不是整份 YAML 重写，保留用户已有配置、缩进与注释。
     */
    @Synchronized
    fun saveBotCredentials(appId: String, secret: String): Boolean {
        val original = file.readText(Charsets.UTF_8)
        val newline = if (original.contains("\r\n")) "\r\n" else "\n"
        val lines = original.split(Regex("\\r?\\n")).toMutableList()

        val botIndex = lines.indexOfFirst { it.trim() == "bot:" }
        if (botIndex < 0) {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.add("bot:")
            lines.add("  app-id: ${yamlString(appId)}")
            lines.add("  secret: ${yamlString(secret)}")
        } else {
            val sectionEnd = botSectionEnd(lines, botIndex)
            if (sectionEnd <= botIndex + 1) {
                lines.add(botIndex + 1, "  app-id: ${yamlString(appId)}")
                lines.add(botIndex + 2, "  secret: ${yamlString(secret)}")
            } else {
                val indent = lines.subList(botIndex + 1, sectionEnd)
                    .firstOrNull { it.isNotBlank() }
                    ?.takeWhile { it == ' ' || it == '\t' }
                    ?: "  "
                val block = lines.subList(botIndex + 1, sectionEnd)
                    .filterNot { isKeyLine(it, "app-id") || isKeyLine(it, "secret") }
                    .toMutableList()
                block.add(0, "$indent secret: ${yamlString(secret)}")
                block.add(0, "$indent app-id: ${yamlString(appId)}")

                lines.subList(botIndex + 1, sectionEnd).clear()
                lines.addAll(botIndex + 1, block)
            }
        }

        file.writeText(lines.joinToString(newline), Charsets.UTF_8)
        reload()
        logger("扫码绑定成功，已写入配置文件: bot.app-id / bot.secret")
        return true
    }

    /** 找到 `bot:` 块的结束位置（下一个顶层键）；块内空行不计入结束。 */
    private fun botSectionEnd(lines: List<String>, botIndex: Int): Int {
        for (index in botIndex + 1 until lines.size) {
            val line = lines[index]
            if (line.isBlank()) continue
            if (!line.startsWith(" ") && !line.startsWith("\t")) return index
        }
        return lines.size
    }

    private fun isKeyLine(line: String, key: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed == "$key:" || trimmed.startsWith("$key: ")
    }

    /** 统一加双引号，避免 Secret 中的特殊字符破坏 YAML。 */
    private fun yamlString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    fun botName(): String = string("bot.name", "HuHoBot")
    fun serverName(): String = string("serverName", botName())
    fun groupOpenIds(): List<String> = stringList("bot.groups")
    fun suppressQqBotConsoleOutput(): Boolean = boolean("bot.suppress-console-output", true)

    fun redisEnabled(): Boolean = boolean("redis.enabled", false)
    fun redisHost(): String = string("redis.host", "localhost")
    fun redisPort(): Int = integer("redis.port", 6379)
    fun redisPassword(): String? = string("redis.password").takeIf(String::isNotBlank)
    fun redisChannel(): String = string("redis.channel", "HuHoBotChannel")

    fun chatFormat(): ChatFormat = ChatFormat(
        fromGame = string("chat-format.from-game", "[游戏] {message}"),
        fromGroup = string("chat-format.from-group", "[QQ] {name}: {message}"),
        postChat = boolean("chat-format.post-chat", true),
        startWith = string("chat-format.start-with")
    )

    fun playerEventFormat(): PlayerEventFormat = PlayerEventFormat(
        joinEnabled = boolean("player-events.join.enabled", true),
        joinFormat = string("player-events.join.format", "[游戏] {name} 加入了服务器"),
        quitEnabled = boolean("player-events.quit.enabled", true),
        quitFormat = string("player-events.quit.format", "[游戏] {name} 离开了服务器"),
        alwaysForward = boolean("player-events.always-forward", false)
    )

    fun markdownFiles(): Map<String, String> {
        val configured = (node("markdown") as? Map<*, *>)?.entries
            ?.mapNotNull { (key, value) ->
                val name = key?.toString()?.trim().orEmpty()
                if (name.isEmpty() || value == null) null else name to value.toString()
            }
            ?.toMap()
            .orEmpty()
        return mapOf("queryOnline" to "online.md") + configured
    }

    fun motd(): Motd = Motd(
        serverIP = string("motd.server-ip", "127.0.0.1"),
        serverPort = integer("motd.server-port", defaultPort),
        api = string("motd.api"),
        text = string("motd.text"),
        postImg = boolean("motd.post-img", false),
        useMarkdown = boolean("motd.use-markdown", false)
    )

    fun whiteList(): WhiteList = WhiteList(
        addCommand = string("whitelist.add-command", "whitelist add {name}"),
        delCommand = string("whitelist.del-command", "whitelist remove {name}")
    )

    fun filterRegexList(): List<String> = stringList("filter-regex")
    fun adminMode(): AdminMode = AdminMode.from(string("admin.mode", "both")) ?: AdminMode.BOTH
    fun adminOpenIds(): List<String> = stringList("admin.openids")
    fun isAuthenticationEnabled(): Boolean = boolean("features.enable-auth", true)

    /** 是否订阅群成员进退群与入群申请事件（GROUP_MEMBER_EVENT）。 */
    fun groupMemberEvents(): Boolean = boolean("features.group-member-events", false)
    fun fullForwardingByDefault(): Boolean = boolean("features.full-amount", false)

    fun commandSwitches(): Map<String, Boolean> {
        val commands = node("commands") as? Map<*, *> ?: return emptyMap()
        return commands.entries.associate { (key, value) ->
            val settings = value as? Map<*, *>
            key.toString() to booleanValue(settings?.get("enable") ?: value, true)
        }
    }

    fun commandMenuSwitches(): Map<String, Boolean> {
        val commands = node("commands") as? Map<*, *> ?: return emptyMap()
        return commands.entries.associate { (key, value) ->
            val commandName = key.toString()
            val default = commandName !in COMMANDS_HIDDEN_FROM_MENU
            val settings = value as? Map<*, *>
            commandName to if (settings == null) default else booleanValue(settings["pushMenu"], default)
        }
    }

    fun auditBaseUrl(): String? = string("audit.base-url").takeIf(String::isNotBlank)
    fun auditApiKey(): String? = string("audit.api-key").takeIf(String::isNotBlank)
    fun auditModel(): String? = string("audit.model").takeIf(String::isNotBlank)

    fun customCommands(): List<CustomCommandDetail> {
        val entries = node("custom-commands") as? List<*> ?: return emptyList()
        return entries.mapNotNull { raw ->
            val map = raw as? Map<*, *> ?: return@mapNotNull null
            val key = map["key"]?.toString()?.trim().orEmpty()
            val command = map["command"]?.toString()?.trim().orEmpty()
            val permission = map["permission"]?.toString()?.toIntOrNull() ?: 0
            val pushMenu = booleanValue(map["pushMenu"], true)
            if (key.isBlank() || command.isBlank()) {
                logger("忽略缺少 key 或 command 的自定义命令配置: $map")
                null
            } else {
                CustomCommandDetail(key, command, permission, pushMenu)
            }
        }
    }

    private fun string(path: String, default: String = ""): String = node(path)?.toString() ?: default

    private fun boolean(path: String, default: Boolean): Boolean = booleanValue(node(path), default)

    private fun booleanValue(value: Any?, default: Boolean): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.toBooleanStrictOrNull() ?: default
        else -> default
    }

    private fun integer(path: String, default: Int): Int = when (val value = node(path)) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: default
        else -> default
    }

    private fun stringList(path: String): List<String> =
        (node(path) as? Iterable<*>)?.mapNotNull { it?.toString() } ?: emptyList()

    private fun node(path: String): Any? {
        var current: Any? = values
        for (part in path.split('.')) {
            current = (current as? Map<*, *>)?.get(part) ?: return null
        }
        return current
    }

    private fun normalizeMap(source: Map<*, *>): Map<String, Any?> = source.entries.associate { (key, value) ->
        key.toString() to when (value) {
            is Map<*, *> -> normalizeMap(value)
            is List<*> -> value.map { item -> if (item is Map<*, *>) normalizeMap(item) else item }
            else -> value
        }
    }

    private companion object {
        const val CURRENT_CONFIG_VERSION = 9
        val COMMANDS_HIDDEN_FROM_MENU = setOf("blockMotd", "unblockMotd")
    }
}
