package cn.huohuas001.bot

import cn.huohuas001.bot.providers.*

interface HuHoBot: LoggerProvider, ConfigProvider, CommandProvider, SchedulerProvider, MessageProvider{

}