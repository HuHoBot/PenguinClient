package cn.huohuas001.bot.provider

import cn.huohuas001.bot.HuHoBot

object BotShared {
    private lateinit var plugin: HuHoBot
    @Volatile var runtimeGeneration: Long = 0
        private set
    @Volatile private var runtimeActive = false
    //var customCommandMap: MutableMap<String, CustomCommandDetail> = ConcurrentHashMap()

    @Synchronized
    fun setInstance(instance: HuHoBot){
        plugin = instance
        runtimeGeneration++
        runtimeActive = true
    }

    fun getPlugin(): HuHoBot {
        return plugin
    }

    fun isRuntimeActive(instance: HuHoBot, generation: Long = runtimeGeneration): Boolean =
        runtimeActive && plugin === instance && runtimeGeneration == generation

    @Synchronized
    fun nextRuntimeGeneration(instance: HuHoBot): Long {
        if (!runtimeActive || plugin !== instance) return -1
        return ++runtimeGeneration
    }

    @Synchronized
    fun stopRuntime(instance: HuHoBot) {
        if (::plugin.isInitialized && plugin === instance) {
            runtimeActive = false
            runtimeGeneration++
        }
    }
}