package cn.huohuas001.bot.state

import cn.huohuas001.bot.datapack.StoredCommandSettings
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class HumanReadableStateFileTest {
    @Test
    fun `persists motd blocked groups`() {
        val directory = Files.createTempDirectory("huhobot-command-state-").toFile()
        try {
            val stateFile = HumanReadableStateFile()
            stateFile.initialize(directory)
            stateFile.save(
                StoredCommandSettings(
                    motdBlocked = mapOf("group-a" to true, "group-b" to false)
                )
            )

            val restored = HumanReadableStateFile().initialize(directory)
            assertEquals(mapOf("group-a" to true, "group-b" to false), restored.motdBlocked)
        } finally {
            directory.deleteRecursively()
        }
    }
}
