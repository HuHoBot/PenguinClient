package cn.huohuas001.bot

import cn.huohuas001.bot.events.commands.RegisteredCommand
import cn.huohuas001.bot.provider.BotShared
import cn.huohuas001.bot.state.GroupCommandConfig
import io.github.kloping.qqbot.http.PanelBase
import io.github.kloping.qqbot.http.data.PanelRequest
import io.github.kloping.qqbot.http.data.PanelResult
import java.io.Closeable
import java.io.IOException
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupPanelSyncTest {
    @Test
    fun `moving a group to an override removes its old association and clearing restores inheritance`() {
        Fixture().use { fixture ->
            fixture.sync(listOf("a", "b"))
            val oldId = fixture.api.panels.keys.single()
            GroupCommandConfig.replace("b", mapOf("commands" to mapOf("在线" to mapOf("enable" to false))))
            fixture.sync(listOf("a", "b"))
            assertFalse(oldId in fixture.api.panels)
            assertEquals(listOf("在线", "版本"), fixture.api.commandsFor("a"))
            assertEquals(listOf("版本"), fixture.api.commandsFor("b"))
            assertEquals(1, fixture.api.panels.values.count { "b" in it.targets })
            GroupCommandConfig.initialize(fixture.directory)
            assertFalse(GroupCommandConfig.enabled(fixture.plugin, "b", "在线"))
            GroupCommandConfig.replace("b", emptyMap())
            fixture.sync(listOf("a", "b"))
            assertEquals(1, fixture.api.panels.size)
            assertEquals(setOf("a", "b"), fixture.api.panels.values.single().targets.toSet())
            assertEquals(listOf("在线", "版本"), fixture.api.commandsFor("b"))
        }
    }

    @Test
    fun `a failed recreation does not persist the deleted panel ID and the next sync recovers`() {
        Fixture().use { fixture ->
            fixture.sync(listOf("a", "b"))
            fixture.api.failNextCreate = true
            fixture.sync(listOf("a"))
            assertTrue(fixture.api.panels.isEmpty())
            fixture.sync(listOf("a"))
            assertEquals(setOf("a"), fixture.api.panels.values.single().targets.toSet())
            assertEquals(listOf("在线", "版本"), fixture.api.commandsFor("a"))
        }
    }

    @Test
    fun `profile target creation is chunked at twenty groups without losing associations`() {
        Fixture().use { fixture ->
            val groups = (1..45).map { "group-$it" }
            fixture.sync(groups)
            assertEquals(3, fixture.api.panels.size)
            assertTrue(fixture.api.panels.values.all { it.targets.size <= 20 })
            assertEquals(groups.toSet(), fixture.api.panels.values.flatMap { it.targets }.toSet())
            fixture.sync(groups.reversed())
            assertEquals(3, fixture.api.created)
        }
    }

    @Test
    fun `menu hiding does not disable execution and removing all groups removes owned panels`() {
        Fixture().use { fixture ->
            GroupCommandConfig.replace("a", mapOf("commands" to mapOf("在线" to mapOf("pushMenu" to false))))
            fixture.sync(listOf("a"))
            assertTrue(GroupCommandConfig.enabled(fixture.plugin, "a", "在线"))
            assertEquals(listOf("版本"), fixture.api.commandsFor("a"))
            fixture.sync(emptyList())
            assertTrue(fixture.api.panels.isEmpty())
        }
    }

    private class Fixture : Closeable {
        val directory = Files.createTempDirectory("huhobot-group-panel-").toFile()
        val api = RecordingPanelApi()
        val plugin: HuHoBot = Proxy.newProxyInstance(HuHoBot::class.java.classLoader, arrayOf(HuHoBot::class.java)) { proxy, method, args ->
            when (method.name) {
                "getConfigFile" -> directory.resolve("config.yml")
                "getCustomCommands" -> emptyList<Any>()
                "getCommandList", "getCommandMenuList" -> mapOf("在线" to true, "版本" to true)
                "isCommandEnabled" -> GroupCommandConfig.enabled(proxy as HuHoBot, args!![0] as String, args[1] as String)
                "log_info", "log_warning", "log_error" -> Unit
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "GroupPanelFixture"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as HuHoBot

        init { GroupCommandConfig.initialize(directory); BotShared.setInstance(plugin) }

        fun sync(groups: List<String>) = MenuManager.syncGroupPanels(
            api, groups, listOf(RegisteredCommand("在线", "查询在线"), RegisteredCommand("版本", "查询版本"))
        )

        override fun close() {
            BotShared.stopRuntime(plugin)
            GroupCommandConfig.initialize(null)
            directory.deleteRecursively()
        }
    }

    private data class AttachedPanel(val targets: List<String>, val commands: List<String>)

    /** Mirrors the SDK contract: content updates leave target associations unchanged. */
    private class RecordingPanelApi : PanelBase() {
        val panels = linkedMapOf<String, AttachedPanel>()
        var created = 0
        var failNextCreate = false

        override fun create(request: PanelRequest): PanelResult {
            if (failNextCreate) { failNextCreate = false; throw IOException("temporary create failure") }
            require(request.groupOpenIds.size <= 20)
            val id = "panel-${++created}"
            panels[id] = AttachedPanel(request.groupOpenIds.toList(), request.panel.items.map { it.name })
            return PanelResult().apply { panelId = id }
        }

        override fun update(panelId: String, request: PanelRequest): PanelResult {
            val previous = panels[panelId] ?: throw IOException("panel missing")
            panels[panelId] = previous.copy(commands = request.panel.items.map { it.name })
            return PanelResult().apply { this.panelId = panelId }
        }

        override fun delete(panelId: String) {
            if (panels.remove(panelId) == null) throw IOException("panel missing")
        }

        fun commandsFor(group: String): List<String> = panels.values.single { group in it.targets }.commands
    }
}
