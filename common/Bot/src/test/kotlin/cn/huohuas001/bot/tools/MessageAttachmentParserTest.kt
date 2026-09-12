package cn.huohuas001.bot.tools

import kotlin.test.Test
import kotlin.test.assertEquals

class MessageAttachmentParserTest {
    @Test
    fun `parses voice with recognized text`() {
        assertEquals(
            "收到[语音:你好，世界]",
            MessageAttachmentParser.parse("收到[voice:message.amr|你好，世界]")
        )
    }

    @Test
    fun `parses voice without recognized text`() {
        assertEquals("[语音]", MessageAttachmentParser.parse("[voice:message.amr]"))
        assertEquals("[语音]", MessageAttachmentParser.parse("[voice:message.amr|]"))
    }

    @Test
    fun `parses image video and file`() {
        assertEquals(
            "[图片][视频][文件:archive.zip]",
            MessageAttachmentParser.parse(
                "[pic:https://example.com/a.png][video:video.mp4][file:archive.zip]"
            )
        )
    }

    @Test
    fun `parses multiple attachment tags mixed with text`() {
        assertEquals(
            "图片：[图片]，语音：[语音:测试]，文件：[文件:测试.txt]",
            MessageAttachmentParser.parse(
                "图片：[pic:a.png]，语音：[voice:a.amr|测试]，文件：[file:测试.txt]"
            )
        )
    }
}
