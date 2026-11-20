package cn.huohuas001.bot.provider

/** Shared management defaults and JSON-compatible configuration operations. */
object ManagementConfig {
    val defaults: Map<String, Any?> = linkedMapOf(
        "webui" to linkedMapOf("enabled" to true, "url" to "http://127.0.0.1:8088"),
        "update-check" to linkedMapOf("enabled" to true, "url" to "")
    )
    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "[::1]", "::1")

    fun node(values: Map<String, Any?>, path: String): Any? {
        var value: Any? = values
        for (part in path.split('.')) value = (value as? Map<*, *>)?.get(part) ?: return null
        return value
    }

    fun merge(original: Map<String, Any?>, changes: Map<String, Any?>): Map<String, Any?> {
        val result = original.toMutableMap()
        for ((key, value) in changes) {
            require(key.isNotBlank() && !key.contains('.') && !key.startsWith('@')) { "无效配置键: $key" }
            val previous = result[key]
            result[key] = if (value is Map<*, *> && previous is Map<*, *>) {
                merge(stringMap(previous), stringMap(value))
            } else {
                compatible(previous, value, key)
                value
            }
        }
        return result
    }

    fun withDefaults(values: Map<String, Any?>): Map<String, Any?> = merge(defaults, values)

    fun validate(values: Map<String, Any?>) {
        val editorUrl = node(values, "webui.url") as? String
            ?: throw IllegalArgumentException("webui.url 必须为字符串")
        val editorUri = try { java.net.URI(editorUrl) } catch (_: Exception) {
            throw IllegalArgumentException("webui.url 无效")
        }
        require(editorUri.scheme == "https" || editorUri.scheme == "http" && editorUri.host in LOOPBACK_HOSTS) {
            "webui.url 必须使用 HTTPS（本机开发地址可使用 HTTP）"
        }
        require(!editorUri.host.isNullOrBlank() && editorUri.userInfo == null && editorUri.rawQuery == null &&
            editorUri.rawFragment == null && (editorUri.path.isNullOrEmpty() || editorUri.path == "/")) {
            "webui.url 必须为不带凭据、路径、查询或片段的站点地址"
        }
        require(node(values, "webui.enabled") is Boolean) { "webui.enabled 必须为布尔值" }
        require(node(values, "update-check.enabled") is Boolean) { "update-check.enabled 必须为布尔值" }
        val urls = node(values, "update-check.url") as? String
            ?: throw IllegalArgumentException("update-check.url 必须为字符串")
        urls.split(',').map(String::trim).filter(String::isNotEmpty).forEach { source ->
            val uri = try { java.net.URI(source) } catch (_: Exception) {
                throw IllegalArgumentException("更新数据源 URL 无效")
            }
            require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null) {
                "更新数据源必须为不带凭据的 HTTP(S) URL"
            }
        }
        (values["commands"] as? Map<*, *>)?.forEach { (name, settings) ->
            require(name is String && name.isNotBlank()) { "命令名不能为空" }
            require(settings is Boolean || settings is Map<*, *> && settings.all { (key, value) ->
                key in setOf("enable", "pushMenu") && value is Boolean
            }) { "命令开关必须为布尔值" }
        }
        (values["custom-commands"] as? List<*>)?.forEach { entry ->
            require(entry is Map<*, *> && (entry["key"] as? String)?.isNotBlank() == true &&
                (entry["command"] as? String)?.isNotBlank() == true) { "自定义命令需要 key 和 command" }
            val permission = entry["permission"]
            require(permission == null || permission is Number && permission.toDouble() == permission.toInt().toDouble()) {
                "自定义命令 permission 必须为整数"
            }
            require(entry["pushMenu"] == null || entry["pushMenu"] is Boolean) { "pushMenu 必须为布尔值" }
        }
    }

    fun stringMap(source: Map<*, *>): Map<String, Any?> = source.entries.associate { (key, value) ->
        require(key is String) { "配置键必须为字符串" }
        key to when (value) {
            is Map<*, *> -> stringMap(value)
            is List<*> -> value.map { if (it is Map<*, *>) stringMap(it) else it }
            else -> value
        }
    }

    private fun compatible(previous: Any?, value: Any?, key: String) {
        require(when (previous) {
            is Boolean -> value is Boolean
            is Number -> value is Number
            is String -> value is String
            is List<*> -> value is List<*>
            is Map<*, *> -> value is Map<*, *>
            else -> value == null || value is Boolean || value is Number || value is String || value is List<*> || value is Map<*, *>
        }) { "配置项 $key 的类型不正确" }
    }
}
