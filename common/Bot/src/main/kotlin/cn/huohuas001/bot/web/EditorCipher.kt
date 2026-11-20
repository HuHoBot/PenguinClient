package cn.huohuas001.bot.web

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The random fragment key never goes into relay storage; each message binds its routing metadata. */
internal class EditorCipher(cipherKey: String) {
    private val key: SecretKeySpec
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    init {
        require(cipherKey.matches(Regex("[A-Za-z0-9_-]{43}"))) { "通道密钥格式无效" }
        val bytes = decoder.decode(cipherKey)
        require(bytes.size == 32) { "通道密钥长度无效" }
        key = SecretKeySpec(bytes, "AES")
    }

    fun encrypt(value: Any?, associatedData: String): Map<String, String> {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(associatedData.toByteArray(StandardCharsets.UTF_8))
        val ciphertext = cipher.doFinal(EditorJson.encode(value).toByteArray(StandardCharsets.UTF_8))
        return mapOf("iv" to encoder.encodeToString(iv), "ciphertext" to encoder.encodeToString(ciphertext))
    }

    fun decrypt(encrypted: Map<*, *>, associatedData: String): Map<String, Any?> {
        val ivText = encrypted["iv"] as? String ?: throw IllegalArgumentException("缺少加密随机数")
        val body = encrypted["ciphertext"] as? String ?: throw IllegalArgumentException("缺少加密内容")
        require(ivText.matches(Regex("[A-Za-z0-9_-]{16}")) && body.length in 22..1_000_000 &&
            body.all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' }) { "加密内容格式无效" }
        val iv = decoder.decode(ivText)
        val ciphertext = decoder.decode(body)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(associatedData.toByteArray(StandardCharsets.UTF_8))
        val text = String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        return EditorJson.objectValue(text)
    }
}
