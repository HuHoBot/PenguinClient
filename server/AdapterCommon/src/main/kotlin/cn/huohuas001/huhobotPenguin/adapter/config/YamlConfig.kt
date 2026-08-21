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
 * 平台适配器保持同一套配置键；首次启动复制带注释的默认配置。已有配置仅在缺少
 * 新增的认证开关时做定点补充，其他缺失项仍由强类型 getter 的默认值兜底。
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
    private fun ensureAuthenticationOption() {
        if (node("features.enable-auth") != null) return

        val original = file.readText(Charsets.UTF_8)
        val newline = if (original.contains("\r\n")) "\r\n" else "\n"
        val lines = original.split(Regex("\\r?\\n")).toMutableList()
        val featuresIndex = lines.indexOfFirst { it.trim() == "features:" }
        if (featuresIndex >= 0) {
            lines.add(featuresIndex + 1, "  # 是否启用 QQ 头像认证功能。")
            lines.add(featuresIndex + 2, "  enable-auth: true")
        } else {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.add("features:")
            lines.add("  # 是否启用 QQ 头像认证功能。")
            lines.add("  enable-auth: true")
        }
        file.writeText(lines.joinToString(newline), Charsets.UTF_8)
        logger("已自动补充配置项: features.enable-auth=true")
        reload()
    }

    fun botAppId(): String = string("bot.app-id")
    fun botSecret(): String = string("bot.secret")
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
        quitFormat = string("player-events.quit.format", "[游戏] {name} 离开了服务器")
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
        val COMMANDS_HIDDEN_FROM_MENU = setOf("blockMotd", "unblockMotd")
    }
}
