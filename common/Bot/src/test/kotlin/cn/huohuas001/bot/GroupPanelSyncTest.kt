package cn.huohuas001.bot

import cn.huohuas001.bot.events.commands.RegisteredCommand
import cn.huohuas001.bot.provider.BotShared
import cn.huohuas001.bot.state.GroupCommandConfig
import io.github.kloping.qqbot.http.PanelBase
import io.github.kloping.qqbot.http.data.PanelItem
import io.github.kloping.qqbot.http.data.PanelRequest
import io.github.kloping.qqbot.http.data.PanelResult
import io.github.kloping.qqbot.http.data.PanelListResult
import io.github.kloping.qqbot.http.data.PanelRecord
import java.io.Closeable
import java.io.IOException
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupPanelSyncTest {
    @Test
    fun `group config inherits omitted values and rejects unknown fields and priorities`() {
        Fixture().use { fixture ->
            val effective = GroupCommandConfig.effectiveValues(fixture.plugin, "a")
            val saved = GroupCommandConfig.overridesFor(fixture.plugin, "a", mapOf(
                "commands" to mapOf("在线" to mapOf("priority" to 7))
            ))

            assertEquals(mapOf("在线" to mapOf("priority" to 7)), saved["commands"])
            GroupCommandConfig.replace("a", saved)
            assertEquals(7, GroupCommandConfig.priority(fixture.plugin, "a", "在线"))
            assertEquals(0, GroupCommandConfig.priority(fixture.plugin, "a", "版本"))
            assertTrue(effective.containsKey("commands"))
            assertFailsWith<IllegalArgumentException> {
                GroupCommandConfig.overridesFor(fixture.plugin, "a", mapOf(
                    "commands" to mapOf("不存在" to mapOf("enable" to false))
                ))
            }
            assertFailsWith<IllegalArgumentException> {
                GroupCommandConfig.overridesFor(fixture.plugin, "a", mapOf(
                    "commands" to mapOf("在线" to mapOf("priority" to Double.POSITIVE_INFINITY))
                ))
            }
        }
    }

    @Test
    fun `prioritizes public commands and keeps admin execution gate when displayed`() {
        Fixture().use { fixture ->
            val builtIns = listOf(
                RegisteredCommand("管理员执行", "管理员入口", onlyAdmin = true),
                RegisteredCommand("执行", "公开入口"),
                RegisteredCommand("版本", "版本")
            )
            GroupCommandConfig.replace("a", mapOf(
                "commands" to mapOf(
                    "管理员执行" to mapOf("priority" to 1),
                    "执行" to mapOf("priority" to 1),
                    "版本" to mapOf("priority" to 0)
                ),
                "command-panel" to mapOf("show-admin-commands" to true)
            ))
            fixture.sync("a", builtIns)

            val items = fixture.api.itemsFor("a")
            assertEquals(listOf("版本", "执行", "管理员执行"), items.map { it.name })
            assertTrue(items.last().isOnlyAdmin)

            GroupCommandConfig.replace("a", mapOf("command-panel" to mapOf("show-admin-commands" to false)))
            fixture.sync("a", builtIns)
            assertTrue(fixture.api.itemsFor("a").none { it.name == "管理员执行" })
            assertTrue(GroupCommandConfig.enabled(fixture.plugin, "a", "管理员执行"))
        }
    }

    @Test
    fun `panel commands use priority then alphabetic order before the twenty item cap`() {
        Fixture().use { fixture ->
            val commands = (1..23).map { index ->
                RegisteredCommand("cmd%02d".format(index), "Command $index", onlyAdmin = index == 1)
            }

            fixture.sync("a", commands)

            val items = fixture.api.itemsFor("a")
            assertEquals(commands.sortedBy { it.command }.take(20).map { it.command }, items.map { it.name })
            assertTrue(items.first().isOnlyAdmin)
            assertEquals(20, items.size)
        }
    }

    @Test
    fun `panel creation respects the twenty panel quota across every scope`() {
        Fixture().use { fixture ->
            fixture.api.foreignPanelsByScope.putAll(mapOf("c2c" to 5, "group" to 4, "channel" to 5, "dm" to 5))
            GroupCommandConfig.replace("b", mapOf("commands" to mapOf("在线" to mapOf("pushMenu" to false))))

            fixture.sync(listOf("a", "b"))

            assertEquals(listOf("c2c", "group", "channel", "dm"), fixture.api.listedScopes)
            assertEquals(1, fixture.api.panels.size)
            assertEquals(listOf("a"), fixture.api.panels.values.single().targets)
            assertEquals(20, fixture.api.foreignPanelsByScope.values.sum() + fixture.api.panels.size)
        }
    }

    @Test
    fun `a failed panel quota query preserves the current panel`() {
        Fixture().use { fixture ->
            fixture.sync(listOf("a"))
            val oldCommands = fixture.api.commandsFor("a")
            fixture.api.failNextList = true

            fixture.sync("a", listOf(
                RegisteredCommand("在线", "查询在线"),
                RegisteredCommand("版本", "查询版本"),
                RegisteredCommand("新命令", "新命令")
            ))

            assertEquals(1, fixture.api.panels.size)
            assertEquals(oldCommands, fixture.api.commandsFor("a"))
            assertEquals(1, fixture.api.created)
        }
    }

    @Test
    fun `moving a group to an override removes its old association and clearing restores inheritance`() {
        Fixture().use { fixture ->
            fixture.sync(listOf("a", "b"))
            val oldId = fixture.api.panels.keys.single()
            GroupCommandConfig.replace("b", mapOf("commands" to mapOf("在线" to mapOf("enable" to false))))
            fixture.sync(listOf("a", "b"))
            assertFalse(oldId in fixture.api.panels)
            assertEquals(listOf("版本", "在线"), fixture.api.commandsFor("a"))
            assertEquals(listOf("版本"), fixture.api.commandsFor("b"))
            assertEquals(1, fixture.api.panels.values.count { "b" in it.targets })
            GroupCommandConfig.initialize(fixture.directory)
            assertFalse(GroupCommandConfig.enabled(fixture.plugin, "b", "在线"))
            GroupCommandConfig.replace("b", emptyMap())
            fixture.sync(listOf("a", "b"))
            assertEquals(1, fixture.api.panels.size)
            assertEquals(setOf("a", "b"), fixture.api.panels.values.single().targets.toSet())
            assertEquals(listOf("版本", "在线"), fixture.api.commandsFor("b"))
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
            assertEquals(listOf("版本", "在线"), fixture.api.commandsFor("a"))
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
                "getCommandList" -> mapOf("在线" to true, "版本" to true)
                "getCommandMenuList" -> mapOf("在线" to true, "版本" to true)
                "getCommandMenuPriorities" -> mapOf("版本" to 0)
                "showAdminCommandsInMenu" -> true
                "isCommandEnabled" -> GroupCommandConfig.enabled(proxy as HuHoBot, args!![0] as String, args[1] as String)
                "log_info", "log_warning", "log_error" -> Unit
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "GroupPanelFixture"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as HuHoBot

        init { GroupCommandConfig.initialize(directory); BotShared.setInstance(plugin) }

        fun sync(groups: List<String>) = sync(groups, listOf(RegisteredCommand("在线", "查询在线"), RegisteredCommand("版本", "查询版本")))

        fun sync(groups: List<String>, commands: List<RegisteredCommand>) = MenuManager.syncGroupPanels(
            api, groups, commands
        )

        fun sync(group: String, commands: List<RegisteredCommand>) = sync(listOf(group), commands)

        override fun close() {
            BotShared.stopRuntime(plugin)
            GroupCommandConfig.initialize(null)
            directory.deleteRecursively()
        }
    }

    private data class AttachedPanel(val targets: List<String>, val items: List<PanelItem>)

    private class RecordingPanelApi : PanelBase() {
        val panels = linkedMapOf<String, AttachedPanel>()
        val foreignPanelsByScope = mutableMapOf<String, Int>()
        var failNextList = false
        val listedScopes = mutableListOf<String>()
        var created = 0
        var failNextCreate = false

        override fun list(scope: String, limit: Int): PanelListResult {
            listedScopes += scope
            if (failNextList) {
                failNextList = false
                throw IOException("temporary list failure")
            }
            val count = (foreignPanelsByScope[scope] ?: 0) + if (scope == "group") panels.size else 0
            return PanelListResult().apply {
                records = (0 until count).map { index ->
                    PanelRecord().apply { panelId = "$scope-panel-$index" }
                }
            }
        }

        override fun create(request: PanelRequest): PanelResult {
            if (failNextCreate) { failNextCreate = false; throw IOException("temporary create failure") }
            require(request.groupOpenIds.size <= 20)
            val id = "panel-${++created}"
            panels[id] = AttachedPanel(request.groupOpenIds.toList(), request.panel.items.toList())
            return PanelResult().apply { panelId = id }
        }

        override fun update(panelId: String, request: PanelRequest): PanelResult {
            val previous = panels[panelId] ?: throw IOException("panel missing")
            panels[panelId] = previous.copy(items = request.panel.items.toList())
            return PanelResult().apply { this.panelId = panelId }
        }

        override fun delete(panelId: String) {
            if (panels.remove(panelId) == null) throw IOException("panel missing")
        }

        fun commandsFor(group: String): List<String> = itemsFor(group).map { it.name }
        fun itemsFor(group: String): List<PanelItem> = panels.values.single { group in it.targets }.items
    }
}
