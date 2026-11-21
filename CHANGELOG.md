# HuHoBot Penguin v1.5.0

- feat(group): 接入 QQ 群管理接口：群基本信息、机器人群内状态、入群申请列表与审批、群禁言查询/设置、群成员列表与详情、批量移除成员、群黑名单、入群自动审批策略列表
- feat(group): 新增群成员加入/退出与入群申请平台事件（`OnBotGroupMemberAdd` / `OnBotGroupMemberRemove` / `OnBotJoinRequest`），入群申请事件支持直接 `approve()` / `decline()`
- feat(group): 新增 `features.group-member-events` 配置项，控制是否订阅 `GROUP_MEMBER_EVENT`（默认关闭，需机器人具备群管理权限）
- feat(bot): QClient/HuHoBot 暴露统一的群管理方法，各平台适配器（Spigot / Allay / Nukkit / BungeeCord / Velocity）自动具备
- feat(webui): 新增 WebUI 控制面板
- feat(group): 新群消息自动收录到 `bot.groups` 并缓存群名称；可在 `groups/<群 OpenID>/config.yml` 覆盖群级命令开关、面板展示、优先级和管理员命令可见性
- feat(commands): 新增 `/帮助` Markdown 命令目录与自定义命令 `{name}` / `{nickname}` QQ 昵称变量
- feat(commands): 指令面板按群优先级和命令名字母排序，单面板最多推送 20 条；遵循 QQ 机器人最多创建 20 个面板的全局限制
- feat(security): 在统一命令入口拦截黑名单命令，覆盖 QQ 命令、自定义模板展开结果、WebUI 控制台及 `execute ... run` 内层命令；不会拦截其他插件或脚本的直接执行
