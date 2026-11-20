package cn.huohuas001.bot.update

/** A completed attempt may carry [error]; an in-flight request is always [checking]. */
data class UpdateState(
    val current: String,
    val latest: String? = null,
    val outdated: Boolean = false,
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val checkedAt: Long = 0,
    val releaseUrl: String? = null,
    val error: String? = null,
    val checking: Boolean = false
)
