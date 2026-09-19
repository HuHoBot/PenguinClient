package cn.huohuas001.bot.qr

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 扫码绑定成功后拿到的机器人凭据。
 *
 * @property appId QQ 开放平台 AppID，对应配置 `bot.app-id`
 * @property appSecret QQ 开放平台 Secret，对应配置 `bot.secret`
 * @property userOpenid 扫码用户的 OpenID，仅用于日志/后续白名单扩展，可能为空
 */
data class QrCredentials(
    val appId: String,
    val appSecret: String,
    val userOpenid: String? = null
)

/**
 * 扫码绑定任务。
 *
 * @property taskId 服务端返回的任务 ID
 * @property key 本地生成的 AES-256 密钥（base64），必须与 taskId 配对使用；
 *               服务端不回传明文 Secret，只回传用它加密后的密文。
 */
data class QrBindTask(val taskId: String, val key: String)

/** 扫码状态机，取值对齐 QQ 开放平台 `poll_bind_result` 的 `status`。 */
enum class QrBindStatus(val code: Int) {
    /** 未知状态，继续轮询。 */
    NONE(0),

    /** 等待扫码 / 已扫码待确认，继续轮询。 */
    PENDING(1),

    /** 扫码并确认成功，可以解密 AppSecret。 */
    COMPLETED(2),

    /** 二维码已过期，需要重新创建任务并刷新二维码。 */
    EXPIRED(3);

    companion object {
        fun from(code: Int): QrBindStatus = entries.firstOrNull { it.code == code } ?: NONE
    }
}

/** 单次轮询结果。 */
data class QrPollResult(
    val status: QrBindStatus,
    val appId: String,
    val encryptedSecret: String,
    val userOpenid: String?
)

/** 扫码流程中出现的协议/网络错误。 */
class QrLoginException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * QQ 机器人扫码绑定的纯协议实现，行为对齐 `@tencent-connect/qqbot-connector`。
 *
 * 流程：创建绑定任务 → 生成二维码 URL → 轮询结果 → AES-256-GCM 解密 AppSecret。
 * 协议细节见 `flowDocs/QR_LOGIN_FLOW.md` 与 `flowDocs/QR_LOGIN_KOTLIN.md`。
 *
 * 本对象只做「一次请求 = 一次函数调用」，轮询循环与重试由 [QrLoginRunner] 负责。
 */
object QqBotQrLogin {
    /** 单次 HTTP 请求超时，对齐 JS SDK 的 10s。 */
    const val HTTP_TIMEOUT_MS = 10_000L

    /** 轮询间隔，对齐 JS SDK 写死的 2s。 */
    const val POLL_INTERVAL_MS = 2_000L

    /** 二维码页面上展示的接入方标识，不参与鉴权。 */
    const val DEFAULT_SOURCE = "openclaw"

    /** 二维码域名：即使 API 切到测试环境，二维码也始终指向线上域名。 */
    private const val HOST = "q.qq.com"
    private const val CREATE_URL = "https://$HOST/lite/create_bind_task"
    private const val POLL_URL = "https://$HOST/lite/poll_bind_result"

    private val random = SecureRandom()

    /**
     * 与 JS `encodeURIComponent` 等价的编码：`URLEncoder` 是表单编码，
     * 需要把 `+` 与少数几个未转义字符补回来。
     */
    fun encodeUriComponent(value: String): String = URLEncoder.encode(value, "UTF-8")
        .replace("+", "%20")
        .replace("%21", "!")
        .replace("%27", "'")
        .replace("%28", "(")
        .replace("%29", ")")
        .replace("%7E", "~")

    /** 生成扫码 URL（本地拼串，无网络请求）。 */
    fun connectUrl(taskId: String, source: String = DEFAULT_SOURCE): String =
        "https://$HOST/qqbot/openclaw/connect.html" +
            "?task_id=${encodeUriComponent(taskId)}" +
            "&source=${encodeUriComponent(source)}" +
            "&_wv=2"

    /**
     * ① `POST /lite/create_bind_task`
     *
     * 本地生成 32 字节随机密钥，服务端返回 task_id；密钥不会离开本机。
     */
    @Throws(QrLoginException::class)
    fun createBindTask(httpTimeoutMs: Long = HTTP_TIMEOUT_MS): QrBindTask {
        val keyBase64 = Base64.getEncoder().encodeToString(ByteArray(32).also(random::nextBytes))
        val payload = JsonObject().apply { addProperty("key", keyBase64) }.toString()

        val data = postForData(CREATE_URL, payload, httpTimeoutMs, "create_bind_task")
        val taskId = data.stringOrEmpty("task_id")
        if (taskId.isEmpty()) {
            throw QrLoginException("create_bind_task: missing task_id")
        }
        return QrBindTask(taskId = taskId, key = keyBase64)
    }

    /** ② `POST /lite/poll_bind_result` */
    @Throws(QrLoginException::class)
    fun pollBindResult(taskId: String, httpTimeoutMs: Long = HTTP_TIMEOUT_MS): QrPollResult {
        val payload = JsonObject().apply { addProperty("task_id", taskId) }.toString()

        val data = postForData(POLL_URL, payload, httpTimeoutMs, "poll_bind_result")
        return QrPollResult(
            status = QrBindStatus.from(data.intOrZero("status")),
            // bot_appid 历史上出现过数字与字符串两种形态
            appId = data.stringOrEmpty("bot_appid"),
            encryptedSecret = data.stringOrEmpty("bot_encrypt_secret"),
            userOpenid = data.stringOrEmpty("user_openid").takeIf { it.isNotEmpty() }
        )
    }

    /**
     * ③ AES-256-GCM 解密 AppSecret。
     *
     * 密文布局与 JS SDK 一致：`IV(12) || ciphertext || AuthTag(16)`；
     * JCE 约定 `doFinal` 的入参为 `ciphertext || authTag`。
     */
    @Throws(QrLoginException::class)
    fun decryptSecret(encryptedBase64: String, keyBase64: String): String {
        val key = decodeBase64(keyBase64, "key")
        val blob = decodeBase64(encryptedBase64, "bot_encrypt_secret")

        if (key.size != 32) {
            throw QrLoginException("key 必须是 32 字节（AES-256），实际 ${key.size}")
        }
        if (blob.size < GCM_IV_LENGTH + GCM_TAG_LENGTH) {
            throw QrLoginException("密文长度不合法：${blob.size}")
        }

        val iv = blob.copyOfRange(0, GCM_IV_LENGTH)
        val tag = blob.copyOfRange(blob.size - GCM_TAG_LENGTH, blob.size)
        val cipherText = blob.copyOfRange(GCM_IV_LENGTH, blob.size - GCM_TAG_LENGTH)

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, iv)
            )
            String(cipher.doFinal(cipherText + tag), Charsets.UTF_8)
        } catch (error: Exception) {
            throw QrLoginException("解密 AppSecret 失败: ${error.message}", error)
        }
    }

    /** 统一处理 `{ retcode, msg, data }` 信封，返回 `data`。 */
    @Throws(QrLoginException::class)
    private fun postForData(
        url: String,
        bodyJson: String,
        httpTimeoutMs: Long,
        action: String
    ): JsonObject {
        val body = postJson(url, bodyJson, httpTimeoutMs)
        val root = try {
            JsonParser.parseString(body).asJsonObject
        } catch (error: Exception) {
            throw QrLoginException("$action 返回内容不是合法 JSON: ${body.take(200)}", error)
        }

        val retcode = root.intOrZero("retcode")
        if (retcode != 0) {
            val message = root.stringOrEmpty("msg").takeIf { it.isNotEmpty() } ?: "$action failed"
            throw QrLoginException(message)
        }
        return root.getAsJsonObject("data") ?: JsonObject()
    }

    /** 使用 `HttpURLConnection` 发送 JSON POST（本模块按 JDK 8 编译，不能用 java.net.http）。 */
    @Throws(QrLoginException::class)
    private fun postJson(url: String, bodyJson: String, httpTimeoutMs: Long): String {
        val timeout = httpTimeoutMs.coerceIn(1_000L, Int.MAX_VALUE.toLong()).toInt()
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (error: Exception) {
            throw QrLoginException("无法连接 $url: ${error.message}", error)
        }

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            connection.doOutput = true
            connection.useCaches = false
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { output ->
                output.write(bodyJson.toByteArray(Charsets.UTF_8))
            }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (statusCode !in 200..299) {
                throw QrLoginException("HTTP $statusCode from $url")
            }
            return body
        } catch (error: QrLoginException) {
            throw error
        } catch (error: Exception) {
            throw QrLoginException("请求 $url 失败: ${error.message}", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun decodeBase64(value: String, field: String): ByteArray = try {
        Base64.getDecoder().decode(value)
    } catch (error: Exception) {
        throw QrLoginException("$field 不是合法的 base64 内容", error)
    }

    private fun JsonObject.stringOrEmpty(name: String): String {
        val element = get(name) ?: return ""
        if (element.isJsonNull) return ""
        return element.asString.orEmpty()
    }

    private fun JsonObject.intOrZero(name: String): Int {
        val element = get(name) ?: return 0
        if (element.isJsonNull) return 0
        return element.asInt
    }

    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 16
}
