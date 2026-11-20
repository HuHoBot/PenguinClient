package cn.huohuas001.bot.update

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI

internal data class ReleaseMetadata(val latest: String, val version: ReleaseVersion, val releaseUrl: String)

internal object ReleaseParser {
    fun parse(body: String): ReleaseMetadata {
        val text = body.trim()
        if (text.isEmpty()) throw UpdateFailure("更新源返回了空内容")
        if (!text.startsWith('{') && !text.startsWith('[') && !text.startsWith('"')) {
            return metadata(text, null)
        }
        val json = try {
            JsonParser.parseString(text)
        } catch (_: Exception) {
            throw UpdateFailure("更新源返回的 JSON 无效")
        }
        return when {
            json.isJsonArray -> {
                var newest: JsonObject? = null
                var newestVersion: ReleaseVersion? = null
                for (element in json.asJsonArray) {
                    if (!element.isJsonObject) continue
                    val release = element.asJsonObject
                    if (!isStableRelease(release)) continue
                    val tag = string(release.get("tag_name")) ?: continue
                    val version = ReleaseVersion.parse(tag) ?: continue
                    if (!version.stable) continue
                    if (newestVersion == null || version > newestVersion) {
                        newest = release
                        newestVersion = version
                    }
                }
                val selected = newest ?: throw UpdateFailure("更新源中没有有效的稳定发行版本")
                metadata(string(selected.get("tag_name"))!!, releaseUrl(selected))
            }
            json.isJsonObject -> {
                val release = json.asJsonObject
                if (!isStableRelease(release)) throw UpdateFailure("更新源不是稳定发行版本")
                val version = listOf("latest", "version", "tag", "tag_name")
                    .firstNotNullOfOrNull { key -> string(release.get(key)) }
                    ?: throw UpdateFailure("更新源缺少版本号")
                metadata(version, releaseUrl(release))
            }
            json.isJsonPrimitive && json.asJsonPrimitive.isString -> metadata(json.asString, null)
            else -> throw UpdateFailure("更新源格式不受支持")
        }
    }

    fun validHttpUrl(value: String): Boolean = try {
        val uri = URI(value)
        (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) &&
            !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port in 1..65535) && value.none { it.isISOControl() }
    } catch (_: Exception) {
        false
    }

    private fun metadata(rawVersion: String, rawUrl: String?): ReleaseMetadata {
        val latest = rawVersion.trim()
        val version = ReleaseVersion.parse(latest) ?: throw UpdateFailure("更新源版本号无效")
        if (!version.stable) throw UpdateFailure("更新源不是稳定发行版本")
        if (rawUrl != null && !validHttpUrl(rawUrl)) throw UpdateFailure("更新源中的发行链接无效")
        return ReleaseMetadata(latest, version, rawUrl ?: UpdateChecker.RELEASES)
    }

    private fun releaseUrl(release: JsonObject): String? {
        for (key in listOf("html_url", "releaseUrl", "release_url", "url")) {
            if (!release.has(key) || release.get(key).isJsonNull) continue
            return string(release.get(key)) ?: throw UpdateFailure("更新源中的发行链接无效")
        }
        return null
    }

    private fun isStableRelease(release: JsonObject): Boolean {
        for (key in listOf("draft", "prerelease")) {
            val flag = release.get(key) ?: continue
            if (!flag.isJsonPrimitive || !flag.asJsonPrimitive.isBoolean || flag.asBoolean) return false
        }
        return true
    }

    private fun string(value: JsonElement?): String? =
        value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}

internal class UpdateFailure(message: String) : Exception(message)
