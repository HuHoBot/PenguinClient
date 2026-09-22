# HuHoBot Penguin v1.5.0

- feat(group): 接入 QQ 群管理接口：群基本信息、机器人群内状态、入群申请列表与审批、群禁言查询/设置、群成员列表与详情、批量移除成员、群黑名单、入群自动审批策略列表
- feat(group): 新增群成员加入/退出与入群申请平台事件（`OnBotGroupMemberAdd` / `OnBotGroupMemberRemove` / `OnBotJoinRequest`），入群申请事件支持直接 `approve()` / `decline()`
- feat(group): 新增 `features.group-member-events` 配置项，控制是否订阅 `GROUP_MEMBER_EVENT`（默认关闭，需机器人具备群管理权限）
- feat(bot): QClient/HuHoBot 暴露统一的群管理方法，各平台适配器（Spigot / Allay / Nukkit / BungeeCord / Velocity）自动具备

## v1.4.0

- feat(bot): 添加QQ机器人扫码绑定功能
