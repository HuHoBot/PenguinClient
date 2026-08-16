package cn.huohuas001.bot.service

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** `/motd` 命令的参数校验、接口请求及结果格式化。 */
class MotdService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    data class QueryParams(val address: String, val platform: String)

    data class Result(
        val platform: String,
        val imageUrl: String,
        val motd: String,
        val delay: String,
        val protocol: String,
        val version: String,
        val players: String,
        val levelName: String,
        val gameMode: String
    ) {
        fun toPlainText(): String = buildString {
            append("\nMC ").append(if (platform == "Bedrock") "基岩版" else platform).append("服务器状态查询\n")
            append("⭕️状态: 在线\n")
            append("描述: ").append(motd).append('\n')
            append("延迟: ").append(delay).append(" ms\n")
            append("协议版本: ").append(protocol).append('\n')
            append("游戏版本: ").append(version).append('\n')
            append("在线人数: ").append(players).append('\n')
            append("地图名称: ").append(levelName).append('\n')
            append("默认模式: ").append(gameMode)
        }

        fun toMarkdown(
            template: String,
            textFilter: (String) -> String = { it }
        ): String {
            val values = mapOf(
                "platform" to platform,
                "motd_img_url" to imageUrl,
                "motd" to singleLine(textFilter(motd)),
                "delay" to delay,
                "protocal" to protocol,
                "version" to version,
                "player" to players,
                "levelname" to singleLine(textFilter(levelName)),
                "gamemode" to gameMode
            )
            return MARKDOWN_PLACEHOLDER_PATTERN.replace(template) { match ->
                values[match.groupValues[1]] ?: match.value
            }
        }

        private fun singleLine(value: String): String = value
            .replace("\r\n", ZERO_WIDTH_SPACE)
            .replace("\r", ZERO_WIDTH_SPACE)
            .replace("\n", ZERO_WIDTH_SPACE)
    }

    fun query(
        params: QueryParams,
        apiTemplate: String,
        defaultImageUrl: String
    ): Result? {
        val requestUrl = apiTemplate
            .replace("{SERVERHOST}", params.address)
            .replace("{PLATFORM}", params.platform)
        val request = Request.Builder().url(requestUrl).get().build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val json = response.body?.string()?.takeIf(String::isNotBlank) ?: return null
            return parseResponse(json, defaultImageUrl)
        }
    }

    companion object {
        private val ADDRESS_PATTERN = Regex(
            "^((?:[a-zA-Z0-9][-\\w]*\\.)+[a-zA-Z]{2,63}|(?:\\d{1,3}\\.){3}\\d{1,3})(?::(\\d{1,5}))?$"
        )
        private val COLOR_CODE_PATTERN = Regex("§.")
        private val WHITESPACE_PATTERN = Regex("\\s+")
        private val MARKDOWN_PLACEHOLDER_PATTERN = Regex("\\{\\{\\.([A-Za-z_][A-Za-z0-9_]*)}}")
        private const val ZERO_WIDTH_SPACE = "\u200B"

        fun parseParams(params: String?): QueryParams? {
            val values = params.orEmpty().trim()
                .takeIf(String::isNotEmpty)
                ?.split(WHITESPACE_PATTERN)
                ?: return null
            return when (values.size) {
                1 -> QueryParams(values[0], "auto")
                2 -> QueryParams(values[0], values[1])
                else -> null
            }
        }

        fun isValidAddress(address: String): Boolean {
            val match = ADDRESS_PATTERN.matchEntire(address) ?: return false
            val port = match.groupValues.getOrNull(2).orEmpty()
            return port.isEmpty() || port.toIntOrNull()?.let { it in 1..65535 } == true
        }

        fun parseResponse(json: String, defaultImageUrl: String): Result? {
            val root = try {
                JsonParser.parseString(json).asJsonObject
            } catch (_: Exception) {
                return null
            }
            val serverData = root.objectOrNull("serverData") ?: return null
            if (!serverData.string("status", "offline").equals("online", ignoreCase = true)) return null

            val platform = serverData.string("type", "")
            if (platform != "Java" && platform != "Bedrock") return null

            val players = serverData.objectOrNull("players")
            val motd = cleanMotd(serverData.string("pureMotd", "").replace('.', '·'))
            val levelName = if (platform == "Bedrock") {
                serverData.string("levelname", "world").replace('.', '·')
            } else {
                "不可用"
            }
            val gameMode = if (platform == "Bedrock") {
                serverData.string("gamemode", "Unknown")
            } else {
                "不可用"
            }

            return Result(
                platform = platform,
                imageUrl = root.string("screenshotUrl", defaultImageUrl).ifBlank { defaultImageUrl },
                motd = motd,
                delay = serverData.string("delay", "-1"),
                protocol = serverData.string("protocol", "-1"),
                version = serverData.string("version", "0.0.0"),
                players = "${players?.string("online", "-1") ?: "-1"}/${players?.string("max", "-1") ?: "-1"}",
                levelName = levelName,
                gameMode = gameMode
            )
        }

        private fun cleanMotd(value: String): String = WHITESPACE_PATTERN.replace(
            COLOR_CODE_PATTERN.replace(value, "").trim(),
            " "
        )

        private fun JsonObject.objectOrNull(name: String): JsonObject? = try {
            getAsJsonObject(name)
        } catch (_: Exception) {
            null
        }

        private fun JsonObject.string(name: String, default: String): String = try {
            get(name)?.takeUnless { it.isJsonNull }?.asString ?: default
        } catch (_: Exception) {
            default
        }
    }
}
