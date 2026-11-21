package cn.huohuas001.bot.web

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.QClient
import cn.huohuas001.bot.provider.BotShared
import cn.huohuas001.bot.provider.ManagementConfig
import cn.huohuas001.bot.qr.QrLoginManager
import cn.huohuas001.bot.update.UpdateChecker
import cn.huohuas001.bot.update.UpdateState
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ServerHandshake
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Outbound editor connections; there is no HTTP listener on the Minecraft server. */
object WebEditorClient {
    @Volatile private var runtime: Runtime? = null
    private val channelPattern = Regex("[A-Za-z0-9_-]{16,128}")
    private val codePattern = Regex("[A-Z2-7]{8}")
    private val keyPattern = Regex("[A-Za-z0-9_-]{43}")
    private val random = SecureRandom()

    @Synchronized
    fun initialize(plugin: HuHoBot) {
        runtime?.close()
        runtime = Runtime(plugin)
    }

    @Synchronized
    fun stop(plugin: HuHoBot) {
        val current = runtime ?: return
        if (current.plugin !== plugin) return
        runtime = null
        current.close()
    }

    fun open(plugin: HuHoBot, reply: (String) -> Unit) {
        val current = runtime
        if (current == null || current.plugin !== plugin || !BotShared.isRuntimeActive(plugin)) {
            reply("插件运行时尚未启动")
            return
        }
        if (!plugin.isWebUiEnabled()) { reply("独立 WebUI 已禁用"); return }
        val existing = current.session
        if (existing != null && existing.alive()) {
            reply("已有管理通道：${existing.editorUrl}\n网页显示一次性验证码后执行 /huhobot webui <验证码>；/huhobot webui close 可撤销通道")
            return
        }
        if (!current.creating.compareAndSet(false, true)) { reply("正在建立管理通道，请稍候"); return }
        val metadata = mapOf("name" to plugin.getServerName(), "platform" to plugin.getPlatform(), "version" to plugin.getPluginVersion())
        reply("正在连接独立 WebUI 中心…")
        current.executor.execute {
            try {
                val website = URI(plugin.getWebUiUrl().trim().trimEnd('/'))
                ManagementConfig.validate(ManagementConfig.withDefaults(mapOf("webui" to mapOf("url" to website.toString()))))
                val response = current.createChannel(website, metadata)
                val id = response["channelId"] as? String ?: error("中心未返回通道 ID")
                val token = response["serverToken"] as? String ?: error("中心未返回通道凭据")
                val link = response["editorUrl"] as? String ?: error("中心未返回编辑链接")
                val expiresAt = (response["expiresAt"] as? Number)?.toLong() ?: error("中心未返回通道有效期")
                require(channelPattern.matches(id) && token.length in 32..256 && expiresAt > System.currentTimeMillis()) { "中心通道响应无效" }
                val editor = URI(link)
                require(editor.scheme == website.scheme && editor.host == website.host && editor.port == website.port &&
                    editor.path == "/editor/$id" && editor.rawQuery == null && editor.userInfo == null) { "中心返回了不同站点的编辑链接" }
                val fragment = editor.rawFragment ?: error("编辑链接缺少一次性入场密钥")
                require(fragment.startsWith("key=") && keyPattern.matches(fragment.removePrefix("key="))) { "编辑链接入场密钥格式无效" }
                // This independent key exists only in the plugin and URL fragment, never at the relay.
                val cipherKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
                val privateLink = "$link&cipher=$cipherKey"
                val session = Session(current, website, id, token, privateLink, expiresAt, EditorCipher(cipherKey), reply)
                synchronized(WebEditorClient) {
                    if (runtime !== current || !BotShared.isRuntimeActive(plugin)) return@execute
                    current.session?.close("新的配对通道已建立")
                    current.session = session
                }
                session.start()
            } catch (error: Exception) {
                current.reply(reply, "建立管理通道失败：${error.message?.take(180) ?: error.javaClass.simpleName}")
            } finally {
                current.creating.set(false)
            }
        }
    }

    fun authorize(plugin: HuHoBot, code: String, reply: (String) -> Unit) {
        val session = runtime?.takeIf { it.plugin === plugin }?.session
        if (session == null || !session.alive()) { reply("请先执行 /huhobot webui 创建配对通道"); return }
        val normalized = code.uppercase()
        if (!codePattern.matches(normalized)) { reply("配对码应为网页显示的 8 位字母/数字"); return }
        session.authorize(normalized, reply)
    }

    fun revoke(plugin: HuHoBot, reply: (String) -> Unit) {
        val current = runtime?.takeIf { it.plugin === plugin }
        val session = current?.session
        if (session == null) { reply("没有活动的管理通道"); return }
        session.close("管理员已撤销配对")
        current.session = null
        reply("管理通道已关闭，旧配对码与网页授权已失效")
    }

    fun configurationChanged(plugin: HuHoBot) {
        val session = runtime?.takeIf { it.plugin === plugin }?.session ?: return
        if (!plugin.isWebUiEnabled()) { session.close("WebUI 已在服务器配置中禁用"); return }
        session.configurationChanged()
    }

    private class Runtime(val plugin: HuHoBot) {
        val executor = ScheduledThreadPoolExecutor(2) { runnable ->
            Thread(runnable, "HuHoBot-Editor").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        val creating = AtomicBoolean(false)
        @Volatile var session: Session? = null
        @Volatile private var connection: HttpURLConnection? = null

        fun reply(action: (String) -> Unit, message: String) {
            if (!BotShared.isRuntimeActive(plugin)) return
            plugin.submit { if (BotShared.isRuntimeActive(plugin)) action(message) }
        }

        fun createChannel(website: URI, metadata: Map<String, String>): Map<String, Any?> {
            val request = website.resolve("/api/channels").toURL().openConnection() as HttpURLConnection
            connection = request
            try {
                request.requestMethod = "POST"
                request.connectTimeout = 5000
                request.readTimeout = 10000
                request.instanceFollowRedirects = false
                request.doOutput = true
                request.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                request.outputStream.use { it.write(EditorJson.encode(metadata).toByteArray(StandardCharsets.UTF_8)) }
                require(request.responseCode in 200..299) { "管理站点 HTTP ${request.responseCode}" }
                val bytes = request.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 16384) { "中心响应过大" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                return EditorJson.objectValue(String(bytes, StandardCharsets.UTF_8))
            } finally { connection = null; request.disconnect() }
        }

        fun close() {
            session?.close("服务器插件已停止")
            session = null
            connection?.disconnect()
            executor.shutdownNow()
        }
    }

    private class Session(
        val owner: Runtime,
        val website: URI,
        val id: String,
        private val token: String,
        val editorUrl: String,
        private val expiresAt: Long,
        private val cipher: EditorCipher,
        private val openingReply: (String) -> Unit
    ) {
        @Volatile private var closed = false
        @Volatile private var ready = false
        @Volatile private var authorized = false
        @Volatile private var socket: WebSocketClient? = null
        private var announced = false
        private var attempts = 0
        private var heartbeat: ScheduledFuture<*>? = null
        private var reconnect: ScheduledFuture<*>? = null
        private var snapshots: ScheduledFuture<*>? = null
        private val refreshing = AtomicBoolean(false)
        private val pairingReplies = ConcurrentHashMap<String, (String) -> Unit>()
        private val results = LinkedHashMap<String, String?>()
        private var outstanding = 0
        private var lastStatus: String? = null
        private var lastQr: String? = null
        private var lastUpdate: String? = null

        fun alive(): Boolean = !closed && System.currentTimeMillis() < expiresAt && owner.session === this && BotShared.isRuntimeActive(owner.plugin)

        private fun isCurrent(client: WebSocketClient): Boolean = alive() && socket === client && client.isOpen
        private fun canManage(client: WebSocketClient): Boolean = isCurrent(client) && ready && authorized

        fun start() {
            snapshots = owner.executor.scheduleAtFixedRate({ refresh() }, 1, 1, TimeUnit.SECONDS)
            connect()
        }

        @Synchronized private fun connect() {
            if (!alive()) { close("管理通道已过期"); return }
            ready = false
            authorized = false
            val uri = URI(if (website.scheme == "https") "wss" else "ws", null, website.host, website.port, "/ws/server/$id", null, null)
            val client = object : WebSocketClient(uri, Draft_6455(emptyList(), 750_000), mapOf("Authorization" to "Bearer $token"), 5000) {
                override fun onOpen(handshake: ServerHandshake) {
                    synchronized(this@Session) {
                        if (!alive() || this@Session.socket !== this) { close(); return }
                        attempts = 0
                        heartbeat?.cancel(false)
                        heartbeat = owner.executor.scheduleAtFixedRate({ sendControl("server.heartbeat", this) }, 0, 2, TimeUnit.SECONDS)
                    }
                }
                override fun onMessage(message: String) {
                    synchronized(this@Session) {
                        if (!isCurrent(this)) return
                        try { receive(this, EditorJson.objectValue(message)) }
                        catch (_: Exception) { owner.plugin.log_warning("拒绝无效或无法解密的 WebUI 通道消息"); close(1008) }
                    }
                }
                override fun onClose(code: Int, reason: String, remote: Boolean) {
                    synchronized(this@Session) {
                        if (this@Session.socket !== this) return
                        ready = false
                        authorized = false
                        this@Session.socket = null
                        heartbeat?.cancel(false)
                        if (alive()) {
                            reconnect?.cancel(false)
                            reconnect = owner.executor.schedule({ this@Session.connect() }, minOf(1L shl minOf(attempts++, 4), 15L), TimeUnit.SECONDS)
                        }
                    }
                }
                override fun onError(error: Exception) {
                    if (this@Session.socket === this && alive()) owner.plugin.log_warning("WebUI 通道连接异常: ${error.javaClass.simpleName}")
                }
            }
            client.isDaemon = true
            socket = client
            client.connect()
        }

        private fun receive(client: WebSocketClient, message: Map<String, Any?>) {
            when (message["type"]) {
                "channel.ready" -> {
                    require(message["channelId"] == id)
                    ready = true
                    if (!announced) {
                        announced = true
                        owner.reply(openingReply, "管理通道已建立：$editorUrl\n网页显示一次性验证码后执行 /huhobot webui <验证码>；/huhobot webui close 可撤销")
                    }
                }
                "channel.authorized" -> {
                    authorized = true
                    ready = true
                    val requestId = message["requestId"] as? String
                    requestId?.let { pairingReplies.remove(it) }?.let { owner.reply(it, "一次性验证码已确认，此网页会话获得临时管理权限") }
                    owner.plugin.submit {
                        if (canManage(client)) {
                            configurationChanged(client)
                            publishSnapshots(client, force = true)
                        }
                    }
                }
                "channel.authorizationFailed" -> {
                    val requestId = message["requestId"] as? String ?: return
                    pairingReplies.remove(requestId)?.let { owner.reply(it, "配对失败：配对码无效、已使用或已过期") }
                }
                "channel.closed" -> close("中心已关闭配对通道", notify = false)
                "request" -> handleRequest(client, message)
                else -> throw IllegalArgumentException("未知通道消息")
            }
        }

        fun authorize(code: String, reply: (String) -> Unit) {
            val client = socket
            if (client == null || !isCurrent(client) || !ready) { reply("管理通道正在重连，请稍后重新输入配对码"); return }
            if (pairingReplies.size >= 4) { reply("正在等待配对确认，请稍候"); return }
            val requestId = UUID.randomUUID().toString()
            pairingReplies[requestId] = reply
            send(mapOf("type" to "server.authorize", "requestId" to requestId, "code" to code), client)
            owner.executor.schedule({ pairingReplies.remove(requestId)?.let { owner.reply(it, "配对确认超时，请查看网页状态；旧码不会自动重复提交") } }, 10, TimeUnit.SECONDS)
        }

        private fun handleRequest(client: WebSocketClient, message: Map<String, Any?>) {
            require(canManage(client)) { "通道尚未授权" }
            val requestId = message["requestId"] as? String ?: error("缺少请求 ID")
            val action = message["action"] as? String ?: error("缺少管理操作")
            require(requestId.length in 1..128 && requestId.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' })
            val encrypted = message["encrypted"] as? Map<*, *> ?: error("管理请求必须端到端加密")
            val payload = cipher.decrypt(encrypted, "$id\nrequest\n$requestId\n$action")
            synchronized(results) {
                if (results.containsKey(requestId)) { results[requestId]?.let { sendEncoded(it, client) }; return }
                require(outstanding < 16) { "管理请求过多" }
                while (results.size >= 1024) {
                    val oldest = results.entries.firstOrNull { it.value != null } ?: break
                    results.remove(oldest.key)
                }
                results[requestId] = null
                outstanding++
            }
            if (action == "update.check") {
                owner.executor.execute {
                    if (!canManage(client)) { complete(client, requestId, false, error = "管理通道已断开，操作未执行"); return@execute }
                    try { complete(client, requestId, true, UpdateChecker.check(owner.plugin, true)) }
                    catch (_: Exception) { complete(client, requestId, false, error = "更新检查失败") }
                }
                return
            }
            owner.plugin.submit {
                if (!canManage(client)) { complete(client, requestId, false, error = "管理通道已断开，操作未执行"); return@submit }
                try {
                    val group = payload["groupId"]?.let { require(it is String); it as String }
                    when (action) {
                        "config.get" -> complete(client, requestId, true, mapOf("values" to owner.plugin.getWebUiConfigValues(group), "groupId" to group))
                        "config.save" -> {
                            val values = payload["values"] as? Map<*, *> ?: error("缺少配置对象")
                            require(owner.plugin.saveWebUiConfigValues(ManagementConfig.stringMap(values), group)) { "保存配置失败" }
                            complete(client, requestId, true, mapOf("saved" to true, "values" to owner.plugin.getWebUiConfigValues(group), "groupId" to group))
                            if (group == null) configurationChanged(client)
                        }
                        "config.reload" -> { owner.plugin.reloadPluginConfig(); complete(client, requestId, true, mapOf("reloaded" to true)); configurationChanged(client) }
                        "command.execute" -> {
                            val command = (payload["command"] as? String)?.trim().orEmpty()
                            require(command.isNotEmpty() && command.length <= 4096) { "命令不能为空或超过 4096 字符" }
                            owner.plugin.sendCommand(command).whenComplete { execution, error ->
                                if (error == null) complete(client, requestId, true, mapOf("output" to execution.getRawString().take(65536)))
                                else complete(client, requestId, false, error = "服务器命令执行失败")
                            }
                        }
                        "qr.start" -> { owner.plugin.startQrLogin(); complete(client, requestId, true, QrLoginManager.snapshot()) }
                        "qr.cancel" -> { QrLoginManager.cancel(); complete(client, requestId, true, QrLoginManager.snapshot()) }
                        else -> complete(client, requestId, false, error = "不支持的管理操作")
                    }
                } catch (error: Exception) { complete(client, requestId, false, error = error.message?.take(180) ?: "管理操作失败") }
            }
        }

        private fun complete(client: WebSocketClient, requestId: String, ok: Boolean, result: Any? = null, error: String? = null) {
            val encoded = EditorJson.encode(mapOf("type" to "server.response", "requestId" to requestId, "ok" to ok,
                "encrypted" to cipher.encrypt(mapOf("result" to result, "error" to error), "$id\nserver.response\n$requestId\n$ok")))
            synchronized(results) { if (results[requestId] == null) outstanding--; results[requestId] = encoded }
            if (canManage(client)) sendEncoded(encoded, client)
        }

        fun configurationChanged() { socket?.let { configurationChanged(it) } }

        private fun configurationChanged(client: WebSocketClient) {
            if (canManage(client)) sendTopic("configuration", owner.plugin.getWebUiConfigValues(), client)
        }

        private fun refresh() {
            if (!alive()) { close("管理通道已过期"); return }
            val client = socket ?: return
            if (!canManage(client) || !refreshing.compareAndSet(false, true)) return
            owner.plugin.submit {
                try { publishSnapshots(client, force = false) }
                finally { refreshing.set(false) }
            }
        }

        private fun publishSnapshots(client: WebSocketClient, force: Boolean) {
            if (!canManage(client)) return
            val plugin = owner.plugin
            val status = mapOf("name" to plugin.getServerName(), "platform" to plugin.getPlatform(), "version" to plugin.getPluginVersion(),
                "connection" to QClient.connectionState(), "online" to plugin.getOnlineList(),
                "groups" to plugin.getGroupOpenIdList(), "groupNames" to plugin.getGroupNames())
            val statusText = EditorJson.encode(status)
            if (force || lastStatus != statusText) { sendTopic("status", status, client); lastStatus = statusText }
            val qr = QrLoginManager.snapshot()
            val qrText = EditorJson.encode(qr)
            if (force || lastQr != qrText) { sendTopic("qr", qr, client); lastQr = qrText }
            val update = UpdateChecker.cachedState(plugin) ?: UpdateState(plugin.getPluginVersion(), enabled = plugin.isUpdateCheckEnabled())
            val updateText = EditorJson.encode(update)
            if (force || lastUpdate != updateText) { sendTopic("update", update, client); lastUpdate = updateText }
        }

        private fun sendTopic(topic: String, value: Any?, client: WebSocketClient) = send(mapOf("type" to "server.data", "topic" to topic,
            "encrypted" to cipher.encrypt(value, "$id\nserver.data\n$topic")), client)
        private fun sendControl(type: String, client: WebSocketClient) = send(mapOf("type" to type), client)
        private fun send(value: Any?, client: WebSocketClient? = socket) = sendEncoded(EditorJson.encode(value), client)
        private fun sendEncoded(encoded: String, client: WebSocketClient? = socket) {
            if (client != null && isCurrent(client)) try { client.send(encoded) } catch (_: Exception) { client.close() }
        }

        @Synchronized fun close(reason: String, notify: Boolean = true) {
            if (closed) return
            if (notify && socket?.isOpen == true) send(mapOf("type" to "server.close", "reason" to reason))
            closed = true
            ready = false
            authorized = false
            reconnect?.cancel(false)
            heartbeat?.cancel(false)
            snapshots?.cancel(false)
            pairingReplies.values.forEach { owner.reply(it, "管理通道已关闭") }
            pairingReplies.clear()
            socket?.close()
        }
    }
}
