package cn.huohuas001.bot.datapack

internal data class StoredCommandSettings(
    val administrators: Map<String, Set<String>> = emptyMap(),
    val authenticatedUsers: Map<String, Map<String, String>> = emptyMap(),
    val administratorModes: Map<String, AdministratorAccessMode> = emptyMap(),
    val fullForwarding: Map<String, Boolean> = emptyMap(),
    val motdBlocked: Map<String, Boolean> = emptyMap()
)