package cn.huohuas001.bot.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseVersionTest {
    private fun version(value: String): ReleaseVersion = requireNotNull(ReleaseVersion.parse(value))

    @Test
    fun `compares dotted numeric versions and ignores prefix and trailing zeroes`() {
        assertTrue(version("1.10.0") > version("1.9.99"))
        assertEquals(0, version("V1.2.3.0.0").compareTo(version("v1.2.3")))
        assertEquals(0, version("01.002.0003+build.2").compareTo(version("1.2.3+other")))
        assertTrue(version("1.2.3.1") > version("1.2.3"))
    }

    @Test
    fun `does not overflow release or prerelease numeric components`() {
        val huge = "9".repeat(100)
        assertTrue(version("1.$huge.0") > version("1.2147483648.0"))
        assertTrue(version("$huge.0.0") > version("2147483648.0.0"))
        assertTrue(version("1.2.3-rc.$huge") > version("1.2.3-rc.2147483648"))
    }

    @Test
    fun `follows semantic prerelease precedence`() {
        val ordered = listOf(
            "1.2.3-alpha", "1.2.3-alpha.1", "1.2.3-alpha.beta", "1.2.3-beta",
            "1.2.3-beta.2", "1.2.3-beta.11", "1.2.3-rc.1", "1.2.3"
        )
        for (pair in ordered.zipWithNext()) assertTrue(version(pair.first) < version(pair.second))
        assertTrue(version("1.2.3-9") < version("1.2.3-rc"))
        assertFalse(version("1.2.3-SNAPSHOT").stable)
    }

    @Test
    fun `rejects malformed and incomplete versions`() {
        for (value in listOf("", "latest", "1", "1.2", "1.2.x", "v1.2.3.abc", "1.2.3-", "1.2.3+", "1.2.3-rc..1", "1.2.3-01", "-1.2.3", "1.2.3\nmalicious")) {
            assertNull(ReleaseVersion.parse(value), value)
        }
    }

    @Test
    fun `chooses newest stable github release without trusting ordering`() {
        val result = ReleaseParser.parse("""
            [
              {"tag_name":"v1.9.0","draft":false,"prerelease":false},
              {"tag_name":"v9.0.0","draft":true,"prerelease":false},
              {"tag_name":"v8.0.0","draft":false,"prerelease":true},
              {"tag_name":"v7.0.0-rc.1","draft":false,"prerelease":false},
              {"tag_name":"v1.10.0","draft":false,"prerelease":false,"html_url":"https://github.com/HuHoBot/PenguinClient/releases/tag/v1.10.0"},
              {"tag_name":"bad-version","draft":false,"prerelease":false},
              {"tag_name":"v1.8.0","draft":false,"prerelease":false}
            ]
        """.trimIndent())
        assertEquals("v1.10.0", result.latest)
        assertEquals("${UpdateChecker.RELEASES}/tag/v1.10.0", result.releaseUrl)
    }

    @Test
    fun `parses custom plain and json version documents`() {
        for (key in listOf("latest", "version", "tag", "tag_name")) {
            assertEquals("V1.10.0", ReleaseParser.parse("{\"$key\":\"V1.10.0\"}").latest)
        }
        assertEquals("1.10.0", ReleaseParser.parse("  1.10.0\n").latest)
        assertEquals("v1.10.0", ReleaseParser.parse("\"v1.10.0\"").latest)
        assertEquals(UpdateChecker.RELEASES, ReleaseParser.parse("{\"latest\":\"1.10.0\"}").releaseUrl)
        assertEquals("https://example.org/release", ReleaseParser.parse("{\"version\":\"1.10.0\",\"releaseUrl\":\"https://example.org/release\"}").releaseUrl)
    }

    @Test
    fun `does not claim invalid or prerelease responses are latest`() {
        for (body in listOf(
            "", "not a version", "1.2", "{}", "[]", "{", "null", "{\"latest\":123}",
            "{\"latest\":\"\$VERSION\"}", "{\"latest\":\"1.2.3-rc.1\"}",
            "{\"version\":\"1.2.3\",\"draft\":true}",
            "[{\"tag_name\":\"1.2.3\",\"prerelease\":\"false\"}]",
            "{\"version\":\"1.2.3\",\"html_url\":\"javascript:alert(1)\"}",
            "{\"version\":\"1.2.3\",\"url\":123}"
        )) assertFailsWith<UpdateFailure>(body) { ReleaseParser.parse(body) }
    }

    @Test
    fun `allows only valid uncredentialed http sources and release links`() {
        assertTrue(ReleaseParser.validHttpUrl("https://api.github.com/repos/HuHoBot/PenguinClient/releases"))
        assertTrue(ReleaseParser.validHttpUrl("http://127.0.0.1:5678/latest.json"))
        for (url in listOf("file:///tmp/latest", "https://", "https://user:secret@example.org/latest", "https://example.org:0/latest", "https://example.org:65536/latest", "https://example.org/latest#token", "https://example.org/\n")) {
            assertFalse(ReleaseParser.validHttpUrl(url), url)
        }
    }
}
