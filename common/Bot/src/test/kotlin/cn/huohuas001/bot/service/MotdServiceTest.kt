package cn.huohuas001.bot.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MotdServiceTest {
    @Test
    fun `validates server address and port`() {
        assertTrue(MotdService.isValidAddress("mc.example.com"))
        assertTrue(MotdService.isValidAddress("mc.example.com:25565"))
        assertTrue(MotdService.isValidAddress("127.0.0.1:19132"))
        assertFalse(MotdService.isValidAddress("localhost"))
        assertFalse(MotdService.isValidAddress("mc.example.com:0"))
        assertFalse(MotdService.isValidAddress("mc.example.com:65536"))
    }

    @Test
    fun `parses command parameters`() {
        assertEquals(
            MotdService.QueryParams("mc.example.com", "auto"),
            MotdService.parseParams("mc.example.com")
        )
        assertEquals(
            MotdService.QueryParams("mc.example.com:19132", "be"),
            MotdService.parseParams("  mc.example.com:19132   be ")
        )
        assertNull(MotdService.parseParams(""))
        assertNull(MotdService.parseParams("a b c"))
    }

    @Test
    fun `converts java response and removes minecraft formatting`() {
        val result = MotdService.parseResponse(
            """
            {
              "screenshotUrl": "https://example.com/motd.png",
              "serverData": {
                "status": "online",
                "type": "Java",
                "pureMotd": "§aHello.   World\n§bServer",
                "delay": 23,
                "protocol": 765,
                "version": "1.20.4",
                "players": {"online": 3, "max": 20}
              }
            }
            """.trimIndent(),
            "https://example.com/default.png"
        )

        requireNotNull(result)
        assertEquals("Java", result.platform)
        assertEquals("Hello· World Server", result.motd)
        assertEquals("3/20", result.players)
        assertEquals("不可用", result.levelName)
        assertEquals("不可用", result.gameMode)
    }

    @Test
    fun `converts bedrock response and rejects offline response`() {
        val result = MotdService.parseResponse(
            """
            {
              "serverData": {
                "status": "online",
                "type": "Bedrock",
                "pureMotd": "Bedrock.Server",
                "delay": 10,
                "protocol": 671,
                "version": "1.21.0",
                "players": {"online": 5, "max": 30},
                "levelname": "My.World",
                "gamemode": "Survival"
              }
            }
            """.trimIndent(),
            "https://example.com/default.png"
        )

        requireNotNull(result)
        assertEquals("https://example.com/default.png", result.imageUrl)
        assertEquals("Bedrock·Server", result.motd)
        assertEquals("My·World", result.levelName)
        assertEquals("Survival", result.gameMode)
        assertNull(
            MotdService.parseResponse(
                """{"serverData":{"status":"offline","type":"Java"}}""",
                "https://example.com/default.png"
            )
        )
    }
    @Test
    fun `renders python compatible motd markdown parameters`() {
        val result = MotdService.Result(
            platform = "Bedrock",
            imageUrl = "https://example.com/motd.png",
            motd = "Hello\nServer",
            delay = "10",
            protocol = "671",
            version = "1.21.0",
            players = "5/30",
            levelName = "My\r\nWorld",
            gameMode = "Survival"
        )
        val template = """
            {{.platform}}|{{.motd_img_url}}|{{.motd}}|{{.delay}}|{{.protocal}}|
            {{.version}}|{{.player}}|{{.levelname}}|{{.gamemode}}|{{.unknown}}
        """.trimIndent()

        val markdown = result.toMarkdown(template) { "已审核:$it" }

        assertEquals(
            "Bedrock|https://example.com/motd.png|已审核:Hello\u200BServer|10|671|\n" +
                    "1.21.0|5/30|已审核:My\u200BWorld|Survival|{{.unknown}}",
            markdown
        )
    }

}
