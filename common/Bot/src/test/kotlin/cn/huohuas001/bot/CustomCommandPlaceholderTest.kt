package cn.huohuas001.bot

import cn.huohuas001.bot.events.commands.CustomCommandRegistry
import cn.huohuas001.bot.provider.CustomCommandDetail
import kotlin.test.Test
import kotlin.test.assertEquals

class CustomCommandPlaceholderTest {
    @Test
    fun `nickname placeholders use cached nickname and fall back to open id`() {
        NicknameManager.initialize(null)
        CustomCommandRegistry.replace(listOf(CustomCommandDetail("greet", "say {name}|{nickname}", 0)))
        try {
            NicknameManager.put("qq-open-id", "企鹅昵称")
            assertEquals(
                "say 企鹅昵称|企鹅昵称",
                CustomCommandRegistry.resolve("huhobot run group-id qq-open-id greet").command
            )

            NicknameManager.initialize(null)
            assertEquals(
                "say unknown-open-id|unknown-open-id",
                CustomCommandRegistry.resolve("huhobot run group-id unknown-open-id greet").command
            )
        } finally {
            CustomCommandRegistry.replace(emptyList())
            NicknameManager.initialize(null)
        }
    }
}
