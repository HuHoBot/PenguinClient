package cn.huohuas001.bot

import cn.huohuas001.bot.events.commands.CommandBlacklistGuard
import cn.huohuas001.bot.provider.ConfigProvider
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandBlacklistGuardTest {
    @Test
    fun `blocks namespaced aliases and nested execute run commands`() {
        val provider = provider(listOf("minecraft:teleport"))

        assertTrue(CommandBlacklistGuard.isBlocked(provider, "execute as @a run tp @s 0 0 0"))
        assertTrue(CommandBlacklistGuard.isBlocked(provider, "/minecraft:teleport @s 0 0 0"))
        assertTrue(CommandBlacklistGuard.isBlocked(provider, "minecraft:tp @s 0 0 0"))
        assertFalse(CommandBlacklistGuard.isBlocked(provider, "say hello"))
    }
    @Test
    fun `normalizes built in aliases before comparing blacklist entries`() {
        val provider = provider(listOf("tell"))

        assertTrue(CommandBlacklistGuard.isBlocked(provider, "minecraft:msg @a hello"))
        assertTrue(CommandBlacklistGuard.isBlocked(provider, "/w @a hello"))
        assertFalse(CommandBlacklistGuard.isBlocked(provider, "say hello"))
    }

    @Test
    fun `maps configured plugin aliases to their canonical command`() {
        val provider = provider(listOf("/warp"), mapOf("warp" to "teleport"))

        assertTrue(CommandBlacklistGuard.isBlocked(provider, "teleport spawn"))
        assertTrue(CommandBlacklistGuard.isBlocked(provider, "/warp spawn"))
    }

    private fun provider(blacklist: List<String>, aliases: Map<String, String> = emptyMap()): ConfigProvider =
        Proxy.newProxyInstance(ConfigProvider::class.java.classLoader, arrayOf(ConfigProvider::class.java)) { proxy, method, args ->
            when (method.name) {
                "getCommandBlacklist" -> blacklist
                "resolveCommandName" -> aliases[args!![0] as String]
                "toString" -> "GuardFixture"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                else -> null
            }
        } as ConfigProvider
}
