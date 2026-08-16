package cn.huohuas001.bot

import cn.huohuas001.bot.events.commands.RegisteredCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MenuManagerTest {
    @Test
    fun `prioritizes built in commands and fills remaining slots with custom commands`() {
        val builtIns = (1..18).map {
            RegisteredCommand("builtin%02d".format(it), "内置命令 $it")
        } + listOf(
            RegisteredCommand("blockMotd", "屏蔽 MOTD", onlyAdmin = true),
            RegisteredCommand("unblockMotd", "解除 MOTD 屏蔽", onlyAdmin = true)
        )
        val customs = (1..5).map {
            RegisteredCommand("custom%02d".format(it), "自定义命令", onlyAdmin = it % 2 == 0)
        }

        val selected = MenuManager.selectPanelCommands(builtIns, customs)

        assertEquals(20, selected.size)
        assertEquals((1..18).map { "builtin%02d".format(it) }, selected.take(18).map { it.command })
        assertEquals(listOf("custom01", "custom02"), selected.drop(18).map { it.command })
        assertFalse(selected.any { it.command == "blockMotd" || it.command == "unblockMotd" })
        assertTrue(selected.last().onlyAdmin)
    }

    @Test
    fun `caps panel at twenty built in commands before considering custom commands`() {
        val builtIns = (1..25).map {
            RegisteredCommand("builtin%02d".format(it), "内置命令 $it")
        }
        val customs = listOf(RegisteredCommand("custom", "自定义命令"))

        val selected = MenuManager.selectPanelCommands(builtIns, customs)

        assertEquals(20, selected.size)
        assertTrue(selected.all { it.command.startsWith("builtin") })
        assertFalse(selected.any { it.command == "custom" })
    }

    @Test
    fun `built in command wins when custom command uses the same name`() {
        val builtIn = RegisteredCommand("same", "内置描述", onlyAdmin = false)
        val custom = RegisteredCommand("same", "自定义命令", onlyAdmin = true)

        val selected = MenuManager.selectPanelCommands(listOf(builtIn), listOf(custom))

        assertEquals(listOf(builtIn), selected)
    }

    @Test
    fun `eligible commands retain full count before panel truncation`() {
        val builtIns = (1..18).map {
            RegisteredCommand("builtin%02d".format(it), "内置命令 $it")
        } + listOf(
            RegisteredCommand("blockMotd", "屏蔽 MOTD", onlyAdmin = true),
            RegisteredCommand("unblockMotd", "解除 MOTD 屏蔽", onlyAdmin = true)
        )
        val customs = (1..5).map {
            RegisteredCommand("custom%02d".format(it), "自定义命令")
        } + listOf(
            RegisteredCommand("custom01", "重复的自定义命令"),
            RegisteredCommand("builtin01", "与内置命令重名"),
            RegisteredCommand("blockMotd", "不应推送")
        )

        val eligible = MenuManager.eligiblePanelCommands(builtIns, customs)
        val selected = MenuManager.selectPanelCommands(builtIns, customs)

        assertEquals(23, eligible.size)
        assertEquals(20, selected.size)
        assertEquals(eligible.take(20), selected)
        assertEquals(eligible.size, eligible.map { it.command }.distinct().size)
        assertFalse(eligible.any { it.command == "blockMotd" || it.command == "unblockMotd" })
    }
}
