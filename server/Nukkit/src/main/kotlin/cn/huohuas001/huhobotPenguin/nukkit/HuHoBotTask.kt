package cn.huohuas001.huhobotPenguin.nukkit

import cn.huohuas001.bot.tools.Cancelable
import cn.nukkit.scheduler.TaskHandler

class NukkitTaskCancelable(private val task: TaskHandler) : Cancelable { override fun cancel() = task.cancel() }
