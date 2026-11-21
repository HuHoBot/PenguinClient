package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.provider.ConfigProvider

/** Normalizes server command names before applying the configured deny list. */
object CommandBlacklistGuard {
    private const val MAX_DEPTH = 4

    fun normalize(command: String): String {
        val token = command.trim().split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty()
            .trimStart('/').lowercase()
        if (token.isEmpty()) return ""
        return when (token.substringAfterLast(':').ifEmpty { token }) {
            "msg", "w" -> "tell"
            "tp" -> "teleport"
            "xp" -> "experience"
            else -> token.substringAfterLast(':').ifEmpty { token }
        }
    }

    fun isBlocked(plugin: ConfigProvider, command: String): Boolean {
        val entries = normalizedBlacklist(plugin) ?: return false
        return candidates(plugin, command, 0).any(entries::contains)
    }

    internal fun normalizedBlacklist(plugin: ConfigProvider): Set<String>? {
        val blacklist = plugin.getCommandBlacklist()
        if (blacklist.isEmpty()) return null
        return blacklist.flatMap { entry ->
            val normalized = normalize(entry)
            val canonical = plugin.resolveCommandName(entry)
                ?: plugin.resolveCommandName(entry.trim().trimStart('/'))
            listOfNotNull(
                normalized.takeIf(String::isNotEmpty),
                canonical?.let(::normalize)?.takeIf(String::isNotEmpty)
            )
        }.toSet().takeIf(Set<String>::isNotEmpty)
    }

    internal fun isBlocked(plugin: ConfigProvider, command: String, blocked: Set<String>?): Boolean {
        if (blocked.isNullOrEmpty()) return false
        return candidates(plugin, command, 0).any(blocked::contains)
    }

    private fun candidates(plugin: ConfigProvider, command: String, depth: Int): Set<String> {
        val rawName = command.trim().split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty().trimStart('/')
        val name = normalize(rawName)
        if (name.isEmpty()) return emptySet()
        val result = linkedSetOf(name)
        listOf(rawName, name).forEach { candidate ->
            plugin.resolveCommandName(candidate)?.let { normalize(it).takeIf(String::isNotEmpty)?.let(result::add) }
        }
        if (depth < MAX_DEPTH && name == "execute") {
            val body = command.trim().trimStart('/')
            val nested = Regex("(?i)(?:^|\\s)run\\s+(.+)$").find(body)?.groupValues?.get(1)
            if (nested != null) result.addAll(candidates(plugin, nested, depth + 1))
        }
        return result
    }
}
