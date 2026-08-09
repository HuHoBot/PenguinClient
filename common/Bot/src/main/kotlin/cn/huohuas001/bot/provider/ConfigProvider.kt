package cn.huohuas001.bot.provider

import cn.huohuas001.bot.tools.filterTextByRegex
import java.io.File

class ChatFormat(
    val fromGame: String,
    val fromGroup: String,
    val postChat: Boolean,
    /** 游戏消息必须以此前缀开头才会转发；空字符串表示转发全部消息。 */
    val startWith: String
)

class Motd(
    val serverIP: String,
    val serverPort: Int,
    val api: String,
    val text: String,
    val outputOnlineList: Boolean,
    val postImg: Boolean,
    val useMarkdown: Boolean,
    val customMarkdown: Boolean
)

class WhiteList(
    val addCommand: String,
    val delCommand: String
)

class CustomCommandDetail(
    val key: String,
    val command: String,
    val permission: Int
)

/**
 * 管理员模式
 *
 * @property value 配置文件中的原始字符串值
 */
enum class AdminMode(val value: String) {
    /** 仅 QQ 号(见 [ConfigProvider.getAdminList])生效 */
    QQ("qq"),

    /** 仅通过配置文件指定的管理员生效 */
    CONFIG("config"),

    /** 两者皆可 */
    BOTH("both");

    companion object {
        /**
         * 从配置字符串解析管理员模式,忽略大小写,解析失败返回 null
         */
        fun from(value: String?): AdminMode? {
            if (value == null) return null
            return entries.firstOrNull { it.value.equals(value, ignoreCase = true) }
        }
    }
}

interface ConfigProvider {
    /** 是否屏蔽 QQ Bot SDK 直接写入 System.out 的调试输出。 */
    fun shouldSuppressQqBotConsoleOutput(): Boolean = true

    /** OpenAI 兼容审核服务；留空时只执行本地敏感词首检。 */
    fun getAuditBaseUrl(): String? = System.getenv("HUHOBOT_AUDIT_BASE_URL")
    fun getAuditApiKey(): String? = System.getenv("HUHOBOT_AUDIT_API_KEY")
    fun getAuditModel(): String? = System.getenv("HUHOBOT_AUDIT_MODEL")
    fun getSensitiveWords(): List<String> {
        val directories = listOfNotNull(getConfigFile()?.parentFile?.resolve("sensitive-words"), File("sensitive-words"))
        return directories.asSequence().filter { it.isDirectory }.flatMap { dir ->
            (dir.listFiles { file -> file.isFile && file.extension.equals("txt", true) } ?: emptyArray()).asSequence()
        }.flatMap { it.readLines(Charsets.UTF_8).asSequence() }.map { it.trim() }.filter { it.length >= 2 }.distinct().toList()
    }

    fun getChatFormat(): ChatFormat
    fun getMotd(): Motd
    fun getWhiteList(): WhiteList = WhiteList("whitelist add {name}", "whitelist remove {name}")
    fun getConfigFile(): File? {
        return null
    }

    fun getFilterRegexList(): List<String> {
        return emptyList()
    }

    fun filterText(text: String): String {
        return filterTextByRegex(text, getFilterRegexList())
    }

    fun formatGroupMessage(name: String, message: String): String {
        val filtered = filterText(message)
        return getChatFormat().fromGroup
            .replace("{name}", name)
            .replace("{nick}", name)
            .replace("{message}", filtered)
            .replace("{msg}", filtered)
    }

    fun formatGameMessage(name: String, message: String): String {
        val filtered = filterText(message)
        return getChatFormat().fromGame
            .replace("{name}", name)
            .replace("{message}", filtered)
            .replace("{msg}", filtered)
    }

    /**
     * 获取管理员模式，默认为 both（QQ 号与配置文件管理员皆可）
     */
    fun getAdminMode(): AdminMode {
        return AdminMode.BOTH
    }

    /**
     * 获取管理员 QQ 号列表
     */
    fun getAdminList(): List<String> {
        return emptyList()
    }

    /**
     * 获取允许使用机器人的群 OpenID 列表
     */
    fun getGroupOpenIdList(): List<String> {
        return emptyList()
    }

    /**
     * 是否全额(全量)处理,默认为 false
     */
    fun getFullAmount(): Boolean {
        return false
    }

    /**
     * 获取命令开关列表,命令名 -> 是否启用,默认为空
     */
    fun getCommandList(): Map<String, Boolean> {
        return emptyMap()
    }

    fun getBotName(): String
    fun getPlatform(): String
    fun getPluginVersion(): String
    fun getCustomCommands(): List<CustomCommandDetail> = emptyList()
}
