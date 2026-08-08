package cn.huohuas001.bot.providers


import java.util.concurrent.CompletableFuture

interface CommandProvider {
    fun sendCommand(command: String): CompletableFuture<HExecution>
}