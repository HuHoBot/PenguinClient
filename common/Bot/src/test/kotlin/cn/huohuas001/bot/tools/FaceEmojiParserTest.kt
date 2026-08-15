package cn.huohuas001.bot.tools

import kotlin.test.Test
import kotlin.test.assertEquals

class FaceEmojiParserTest {
    @Test
    fun `parses system face`() {
        assertEquals(
            "哈哈[表情:擦汗]",
            FaceEmojiParser.parse(
                "哈哈<faceType=1,faceId=\"97\",ext=\"eyJ0ZXh0Ijoi5pOm5rGXIn0=\">"
            )
        )
    }

    @Test
    fun `parses multiple faces`() {
        assertEquals(
            "[表情:擦汗]测试[表情:微笑]",
            FaceEmojiParser.parse(
                "<faceType=1,faceId=\"97\",ext=\"\">测试<faceType=1,faceId=\"14\",ext=\"\">"
            )
        )
    }

    @Test
    fun `keeps malformed faces unchanged`() {
        assertEquals(
            "<faceType=1,faceId=\"not-a-number\",ext=\"\">",
            FaceEmojiParser.parse("<faceType=1,faceId=\"not-a-number\",ext=\"\">")
        )
    }

    @Test
    fun `uses generic text for unknown system face`() {
        assertEquals(
            "[表情]",
            FaceEmojiParser.parse("<faceType=1,faceId=\"999999\",ext=\"\">")
        )
    }

    @Test
    fun `formats emoji and animated face types`() {
        assertEquals(
            "[EMOJI]",
            FaceEmojiParser.parse("<faceType=2,faceId=\"128513\",ext=\"\">")
        )
        assertEquals(
            "[动画表情]",
            FaceEmojiParser.parse("<faceType=6,faceId=\"123\",ext=\"\">")
        )
        assertEquals(
            "[表情]",
            FaceEmojiParser.parse("<faceType=99,faceId=\"123\",ext=\"\">")
        )
    }
}
