package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.HuHoBot
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 指令处理基类
 *
 * 子类中通过 [Commands] 注解声明指令处理方法,收到群消息后调用 [handleMessage] 分发:
 * 1. 去掉消息中的 @提及(支持 `<@id>` 与 `<@!id>` 格式)
 * 2. 去掉前导 `/` 后优先匹配所有内置指令
 * 3. 所有内置指令均未命中时，再将 `/test` 按自定义命令 key 路由到 `执行` 或 `管理员执行`
 *
 * 方法签名约定(按需取前几个参数,顺序固定):
 * ```
 * @Commands("发信息", "发送消息到游戏")
 * fun sendGameMessage(api: HuHoBot, message: GroupMessageEvent, params: String?)
 * ```
 * 方法可为普通函数或 `suspend` 函数。
 */
abstract class BaseCommand {
    private data class CommandHandler(
        val metadata: RegisteredCommand,
        val method: Method
    )

    // 指令名 -> 指令元数据及处理方法
    private val commandMap = mutableMapOf<String, CommandHandler>()

    init {
        // 反射扫描本类所有带 @Commands 注解的 public 方法
        for (method in this.javaClass.methods) {
            val annotation = method.getAnnotation(Commands::class.java) ?: continue
            val command = annotation.command.trim()
            require(command.isNotEmpty()) { "指令方法 ${method.name} 的 command 不能为空" }
            commandMap[command] = CommandHandler(
                metadata = RegisteredCommand(
                    command = command,
                    describe = annotation.describe.trim(),
                    onlyAdmin = annotation.onlyAdmin
                ),
                method = method
            )
        }
    }

    /** 返回本处理器实际扫描并注册成功的指令元数据。 */
    fun registeredCommands(): List<RegisteredCommand> = commandMap.values
        .map { it.metadata }
        .sortedBy { it.command }

    /**
     * 处理群消息
     *
     * @return true 表示消息被某个指令消费;false 表示没有匹配的指令
     */
    fun handleMessage(
        plugin: HuHoBot,
        event: GroupMessageEvent,
        allowCustomFallback: Boolean = true
    ): Boolean {
        val content = event.rawMessage.content ?: return false
        // 去掉 @提及(支持 <@id> 和 <@!id> 格式),保留前导 / 以便识别自定义命令快捷方式。
        val mentionStripped = Regex("<@!?[^>]+>").replace(content, "").trim()
        val isSlashCommand = mentionStripped.startsWith("/")
        val cleaned = if (isSlashCommand) mentionStripped.removePrefix("/").trimStart() else mentionStripped
        // 按指令名长度降序匹配,避免短指令抢先(如 "发" 抢走 "发信息")
        for (command in commandMap.keys.sortedByDescending { it.length }) {
            if (cleaned == command || cleaned.startsWith("$command ")) {
                if (plugin.getCommandList()[command] == false) {
                    event.sendMessage("此命令已被管理员关闭")
                    return true
                }
                val params = cleaned.removePrefix(command).trim()
                invokeMethod(plugin, event, commandMap[command]!!.method, params)
                return true
            }
        }

        if (isSlashCommand && allowCustomFallback) {
            return handleCustomCommandShortcut(plugin, event, mentionStripped)
        }
        return false
    }

    /**
     * 将 `/test` 这种快捷写法转发到现有的 `执行 test` 或 `管理员执行 test` 处理器。
     *
     * 管理员命令由 AdministrationCommands 自己执行权限校验；当前处理器没有对应
     * 的目标处理器时返回 false，让 GroupMessageHandler 继续尝试下一个处理器。
     */
    private fun handleCustomCommandShortcut(
        plugin: HuHoBot,
        event: GroupMessageEvent,
        content: String
    ): Boolean {
        val invocation = content.removePrefix("/").trim()
        val key = invocation.split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty()
        val customCommand = CustomCommandRegistry.find(key)
        if (customCommand == null) {
            event.sendMessage("未找到该命令")
            return true
        }

        val targetCommand = if (customCommand.permission > 0) "管理员执行" else "执行"
        val handler = commandMap[targetCommand] ?: return false
        if (plugin.getCommandList()[targetCommand] == false) {
            event.sendMessage("此命令已被管理员关闭")
            return true
        }

        invokeMethod(plugin, event, handler.method, invocation)
        return true
    }

    /**
     * 通过反射调用指令方法,自动适配方法参数数量与 suspend 特性
     */
    private fun invokeMethod(plugin: HuHoBot, event: GroupMessageEvent, method: Method, params: String) {
        val isSuspend = method.parameterTypes.lastOrNull() == Continuation::class.java
        // 构造真实参数(不含 Continuation),按签名约定最多支持 (api, message, params)
        val argCount = method.parameterCount - if (isSuspend) 1 else 0
        val args: Array<Any?> = when (argCount) {
            3 -> arrayOf(plugin, event, params)
            2 -> arrayOf(event, params)
            1 -> arrayOf(event)
            0 -> emptyArray()
            else -> {
                plugin.log_error("指令方法 ${method.name} 参数数量(${method.parameterCount})不受支持,最多支持 (api, message, params)")
                return
            }
        }

        try {
            if (isSuspend) {
                // suspend 函数在 JVM 层面表现为多一个 Continuation 参数,手动传入即可驱动
                val continuation = object : Continuation<Any?> {
                    override val context: CoroutineContext = EmptyCoroutineContext
                    override fun resumeWith(result: Result<Any?>) {
                        result.exceptionOrNull()?.let {
                            plugin.log_error("指令方法 ${method.name} 执行异常: $it")
                        }
                    }
                }
                method.invoke(this, *args, continuation)
            } else {
                method.invoke(this, *args)
            }
        } catch (e: InvocationTargetException) {
            plugin.log_error("指令方法 ${method.name} 执行异常: ${e.targetException}")
        } catch (e: Exception) {
            plugin.log_error("指令方法 ${method.name} 调用失败: $e")
        }
    }
}
