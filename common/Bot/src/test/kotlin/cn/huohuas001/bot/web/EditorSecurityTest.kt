package cn.huohuas001.bot.web

import cn.huohuas001.bot.provider.ManagementConfig
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class EditorSecurityTest {
    @Test fun unchangedSecretsDoNotOverwriteStoredCredentialsButExplicitReplacementAndClearWork() {
        val original = ManagementConfig.withDefaults(mapOf("bot" to mapOf("secret" to "BOT_SECRET", "name" to "original"),
            "audit" to mapOf("api-key" to "AUDIT_SECRET"), "redis" to mapOf("password" to "REDIS_SECRET")))
        val visible = EditorSecrets.redact(original)
        val encoded = EditorJson.encode(visible)
        assertFalse(encoded.contains("\"BOT_SECRET\""))
        assertFalse(encoded.contains("\"AUDIT_SECRET\""))
        assertFalse(encoded.contains("\"REDIS_SECRET\""))
        val restored = ManagementConfig.merge(original, EditorSecrets.changes(visible))
        assertEquals(original, restored)
        val changed = ManagementConfig.merge(original, EditorSecrets.changes(mapOf("bot" to mapOf("secret" to "replacement", "name" to "edited"),
            "audit" to mapOf("api-key" to ""))))
        assertEquals("replacement", ManagementConfig.node(changed, "bot.secret"))
        assertEquals("edited", ManagementConfig.node(changed, "bot.name"))
        assertEquals("", ManagementConfig.node(changed, "audit.api-key"))
        assertEquals("REDIS_SECRET", ManagementConfig.node(changed, "redis.password"))
    }

    @Test fun encryptedRequestsAuthenticateChannelActionAndPayloadAndUseFreshNonces() {
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        val cipher = EditorCipher(key)
        val aad = "channel\nrequest\nid\nconfig.save"
        val payload = mapOf("values" to mapOf("bot" to mapOf("secret" to "PRIVATE_CREDENTIAL")))
        val first = cipher.encrypt(payload, aad)
        val second = cipher.encrypt(payload, aad)
        assertNotEquals(first["iv"], second["iv"])
        assertFalse(EditorJson.encode(first).contains("PRIVATE_CREDENTIAL"))
        assertEquals(payload, cipher.decrypt(first, aad))
        assertFails { cipher.decrypt(first, "other-channel\nrequest\nid\nconfig.save") }
        assertFails { cipher.decrypt(first, "channel\nrequest\nid\ncommand.execute") }
        val ciphertext = first.getValue("ciphertext")
        val tampered = first + ("ciphertext" to ((if (ciphertext[0] == 'A') "B" else "A") + ciphertext.substring(1)))
        assertFails { cipher.decrypt(tampered, aad) }
    }
}
