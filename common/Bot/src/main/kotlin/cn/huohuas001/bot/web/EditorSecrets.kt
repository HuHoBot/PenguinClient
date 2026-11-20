package cn.huohuas001.bot.web

import cn.huohuas001.bot.provider.ManagementConfig

/** Never expose stored secrets; the reserved sentinel means keep the current server value. */
object EditorSecrets {
    const val UNCHANGED = "__HUHOBOT_SECRET_UNCHANGED__"
    private val secretKeys = setOf("secret", "password", "api-key", "apikey", "token", "access-token", "private-key")

    fun redact(values: Map<String, Any?>): Map<String, Any?> = values.mapValues { (key, value) ->
        when {
            isSecret(key) && value != null && value.toString().isNotEmpty() -> UNCHANGED
            value is Map<*, *> -> redact(ManagementConfig.stringMap(value))
            value is List<*> -> value.map { if (it is Map<*, *>) redact(ManagementConfig.stringMap(it)) else it }
            else -> value
        }
    }

    fun changes(values: Map<String, Any?>): Map<String, Any?> = values.entries
        .filterNot { (key, value) -> isSecret(key) && value == UNCHANGED }
        .associate { (key, value) -> key to when (value) {
            is Map<*, *> -> changes(ManagementConfig.stringMap(value))
            else -> value
        } }

    private fun isSecret(key: String): Boolean = key.lowercase().replace('_', '-') in secretKeys
}
