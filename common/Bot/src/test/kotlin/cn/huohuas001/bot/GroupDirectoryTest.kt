package cn.huohuas001.bot

import cn.huohuas001.bot.state.GroupDirectory
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class GroupDirectoryTest {
    @Test
    fun `persists observed group names and reloads them`() {
        val directory = Files.createTempDirectory("huhobot-group-directory-").toFile()
        try {
            GroupDirectory.initialize(directory)
            GroupDirectory.put("group-open-id", "测试群")
            assertEquals("测试群", GroupDirectory.snapshot()["group-open-id"])

            GroupDirectory.initialize(directory)
            assertEquals(mapOf("group-open-id" to "测试群"), GroupDirectory.snapshot())
        } finally {
            GroupDirectory.initialize(null)
            directory.deleteRecursively()
        }
    }
}
