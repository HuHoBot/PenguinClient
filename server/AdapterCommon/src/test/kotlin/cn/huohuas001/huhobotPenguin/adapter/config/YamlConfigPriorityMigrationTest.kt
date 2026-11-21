package cn.huohuas001.huhobotPenguin.adapter.config

import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YamlConfigPriorityMigrationTest {
    @Test
    fun `adds priorities while retaining comments and migrating legacy boolean commands`() {
        val directory = Files.createTempDirectory("huhobot-config-priority-")
        val file = directory.resolve("config.yml").toFile()
        val yaml = """
            commands:
              版本:
                enable: true
                pushMenu: true
              在线: true # keep this comment
              custom:
                enable: false
              "minecraft:tools:run":
                enable: true
                pushMenu: true
            untouched:
              value: retained
        """.trimIndent()
        try {
            file.writeText(yaml)
            val config = YamlConfig(file, 25565) {}
            config.initialize { ByteArrayInputStream(ByteArray(0)) }

            val commands = config.snapshot()["commands"] as Map<*, *>
            val version = commands["版本"] as Map<*, *>
            val online = commands["在线"] as Map<*, *>
            val custom = commands["custom"] as Map<*, *>
            assertEquals(0, version["priority"])
            assertEquals(100, online["priority"])
            assertEquals(true, online["enable"])
            assertEquals(true, online["pushMenu"])
            assertEquals(100, (commands["minecraft:tools:run"] as Map<*, *>)["priority"])
            assertTrue(file.readText().contains("enable: true # keep this comment"))
            val migrated = file.readText()
            config.initialize { error("existing config must not be replaced") }
            assertEquals(migrated, file.readText())
            assertEquals(mapOf("value" to "retained"), config.snapshot()["untouched"])
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
