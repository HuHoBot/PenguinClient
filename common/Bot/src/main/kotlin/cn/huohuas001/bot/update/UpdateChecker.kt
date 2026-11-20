package cn.huohuas001.bot.update

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.tools.Cancelable
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture

/** Shared, read-only release checking. No downloads or installation are performed. */
object UpdateChecker {
    const val PROJECT = "https://github.com/HuHoBot/PenguinClient"
    const val RELEASES = "$PROJECT/releases"
    const val DEFAULT_SOURCE = "https://api.github.com/repos/HuHoBot/PenguinClient/releases"
    const val CACHE_MILLIS = 10 * 60 * 1000L
    private const val MAX_RESPONSE_BYTES = 1024 * 1024
    private const val REQUEST_MILLIS = 20_000L
    private const val SOCKET_TIMEOUT_MILLIS = 5_000
    private val lock = Any()
    private val runtimes = IdentityHashMap<HuHoBot, Runtime>()

    private data class Settings(val current: String, val enabled: Boolean, val sources: String)
    private class Runtime {
        var settings: Settings? = null
        var state: UpdateState? = null
        var flight: Flight? = null
        var startupScheduled = false
        var noticeSent = false
        var startupTask: Cancelable? = null
    }
    private class Flight(val settings: Settings, val initial: UpdateState) {
        val result = CompletableFuture<UpdateState>()
        @Volatile var connection: HttpURLConnection? = null

        fun cancel(state: UpdateState) {
            result.complete(state)
            connection?.disconnect()
        }
    }

    /** Schedule at most one startup check and one console new-version notice per runtime. */
    fun start(plugin: HuHoBot) {
        val runtime = synchronized(lock) {
            val runtime = runtimes.getOrPut(plugin) { Runtime() }
            if (runtime.startupScheduled) return
            runtime.startupScheduled = true
            runtime
        }
        try {
            val task = plugin.submitAsync {
                val state = check(plugin, false, runtime)
                val announce = synchronized(lock) {
                    if (runtimes[plugin] !== runtime || runtime.noticeSent || !state.enabled || !state.outdated) false
                    else {
                        runtime.noticeSent = true
                        true
                    }
                }
                if (announce) {
                    plugin.log_info("发现新版本 ${state.latest}（当前 ${state.current}），请手动更新：${state.releaseUrl ?: RELEASES}")
                } else if (state.enabled && state.error != null && synchronized(lock) { runtimes[plugin] === runtime }) {
                    plugin.log_warning("更新检查未完成：${state.error}")
                }
            }
            synchronized(lock) {
                if (runtimes[plugin] === runtime) runtime.startupTask = task else task.cancel()
            }
        } catch (_: Exception) {
            synchronized(lock) {
                if (runtimes[plugin] === runtime) runtime.startupScheduled = false
            }
            plugin.log_warning("无法调度更新检查；可稍后通过版本命令检查")
        }
    }

    /** Blocking API: callers requiring a refresh must use an async/HTTP worker, not the server thread. */
    fun check(plugin: HuHoBot, force: Boolean = false): UpdateState = check(plugin, force, null)

    /** Returns the last state, including stale data or explicit in-flight state; never starts HTTP. */
    fun cachedState(plugin: HuHoBot): UpdateState? {
        val settings = settings(plugin)
        return synchronized(lock) {
            val runtime = runtimes.getOrPut(plugin) { Runtime() }
            synchronizeSettings(runtime, settings)
            runtime.flight?.initial ?: runtime.state
        }
    }

    fun isFresh(state: UpdateState): Boolean {
        if (!state.enabled) return true
        val age = System.currentTimeMillis() - state.checkedAt
        return !state.checking && state.checked && age >= 0 && age < CACHE_MILLIS
    }

    fun stop(plugin: HuHoBot) {
        val runtime = synchronized(lock) { runtimes.remove(plugin) } ?: return
        runtime.startupTask?.cancel()
        runtime.flight?.cancel(stopped(runtime.settings?.current ?: ""))
    }

    fun reset() {
        val old = synchronized(lock) {
            val old = runtimes.values.toList()
            runtimes.clear()
            old
        }
        for (runtime in old) {
            runtime.startupTask?.cancel()
            runtime.flight?.cancel(stopped(runtime.settings?.current ?: ""))
        }
    }

    private fun check(plugin: HuHoBot, force: Boolean, expected: Runtime?): UpdateState {
        val settings = settings(plugin)
        val runtime: Runtime
        val flight: Flight
        val ownsFlight: Boolean
        synchronized(lock) {
            if (expected != null && runtimes[plugin] !== expected) return stopped(settings.current)
            runtime = runtimes.getOrPut(plugin) { Runtime() }
            synchronizeSettings(runtime, settings)
            if (!settings.enabled) return runtime.state!!
            val previous = runtime.state
            val active = runtime.flight
            if (active != null) {
                flight = active
                ownsFlight = false
            } else {
                if (!force && previous != null && isFresh(previous)) return previous
                flight = Flight(settings, UpdateState(
                    current = settings.current,
                    latest = previous?.latest,
                    outdated = previous?.outdated ?: false,
                    checkedAt = previous?.checkedAt ?: 0,
                    releaseUrl = previous?.releaseUrl,
                    checking = true
                ))
                runtime.flight = flight
                ownsFlight = true
            }
        }
        if (!ownsFlight) return flight.result.join()
        val state = performCheck(flight)
        if (flight.result.isDone) return flight.result.join()
        val currentSettings = settings(plugin)
        synchronized(lock) {
            if (runtimes[plugin] === runtime) synchronizeSettings(runtime, currentSettings)
            if (runtimes[plugin] === runtime && runtime.flight === flight) {
                runtime.state = state
                runtime.flight = null
                flight.result.complete(state)
            }
        }
        // A reset/configuration change completes this future with cancellation, not a stale result.
        return flight.result.join()
    }

    private fun settings(plugin: HuHoBot): Settings {
        val enabled = plugin.isUpdateCheckEnabled()
        return Settings(plugin.getPluginVersion(), enabled, if (enabled) plugin.getUpdateCheckUrls().trim() else "")
    }

    private fun synchronizeSettings(runtime: Runtime, settings: Settings) {
        if (runtime.settings == settings) return
        val state = if (settings.enabled) null else UpdateState(current = settings.current, enabled = false)
        runtime.flight?.cancel(state ?: UpdateState(current = settings.current, error = "更新检查配置已改变，请重新检查"))
        runtime.flight = null
        runtime.settings = settings
        runtime.state = state
    }

    private fun performCheck(flight: Flight): UpdateState {
        val settings = flight.settings
        val currentVersion = ReleaseVersion.parse(settings.current)
        var failure = "没有可用的更新源"
        if (currentVersion == null) {
            return failed(settings.current, "当前插件版本号无法比较")
        }
        val sources = if (settings.sources.isBlank()) listOf(DEFAULT_SOURCE)
        else settings.sources.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        for ((index, source) in sources.withIndex()) {
            if (flight.result.isDone) return flight.result.join()
            try {
                if (!ReleaseParser.validHttpUrl(source)) throw UpdateFailure("更新源必须是无凭据的 HTTP(S) URL")
                val metadata = ReleaseParser.parse(fetch(source, flight))
                return UpdateState(
                    current = settings.current,
                    latest = metadata.latest,
                    outdated = metadata.version > currentVersion,
                    checked = true,
                    checkedAt = System.currentTimeMillis(),
                    releaseUrl = metadata.releaseUrl
                )
            } catch (error: UpdateFailure) {
                failure = "更新源 ${index + 1}：${error.message}"
            } catch (_: SocketTimeoutException) {
                failure = "更新源 ${index + 1}：连接或读取超时"
            } catch (_: IOException) {
                failure = "更新源 ${index + 1}：无法读取更新信息"
            } catch (_: Exception) {
                failure = "更新源 ${index + 1}：更新检查失败"
            }
        }
        return failed(settings.current, failure)
    }

    private fun fetch(source: String, flight: Flight): String {
        val deadline = System.nanoTime() + REQUEST_MILLIS * 1_000_000
        var target = source
        for (redirect in 0..3) {
            if (flight.result.isDone) throw UpdateFailure("更新检查已停止")
            val connection = URL(target).openConnection() as HttpURLConnection
            flight.connection = connection
            try {
                if (flight.result.isDone) throw UpdateFailure("更新检查已停止")
                connection.instanceFollowRedirects = false
                connection.connectTimeout = remainingTimeout(deadline)
                connection.readTimeout = remainingTimeout(deadline)
                connection.setRequestProperty("User-Agent", "PenguinClient-update-checker")
                connection.setRequestProperty("Accept", "application/vnd.github+json, application/json, text/plain")
                connection.setRequestProperty("Accept-Encoding", "identity")
                val status = connection.responseCode
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    if (redirect == 3) throw UpdateFailure("更新源重定向次数过多")
                    val location = connection.getHeaderField("Location") ?: throw UpdateFailure("更新源重定向无效")
                    val next = URI(target).resolve(location).toString()
                    if (!ReleaseParser.validHttpUrl(next)) throw UpdateFailure("更新源重定向链接无效")
                    if (target.startsWith("https:", true) && !next.startsWith("https:", true)) {
                        throw UpdateFailure("更新源重定向不能降低 HTTPS 安全性")
                    }
                    target = next
                    continue
                }
                if (status !in 200..299) throw UpdateFailure("HTTP $status")
                if (connection.contentLengthLong > MAX_RESPONSE_BYTES) throw UpdateFailure("更新源响应超过大小限制")
                return connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        if (flight.result.isDone) throw UpdateFailure("更新检查已停止")
                        connection.readTimeout = remainingTimeout(deadline)
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() > MAX_RESPONSE_BYTES - count) throw UpdateFailure("更新源响应超过大小限制")
                        output.write(buffer, 0, count)
                    }
                    output.toString("UTF-8")
                }
            } finally {
                connection.disconnect()
                if (flight.connection === connection) flight.connection = null
            }
        }
        throw UpdateFailure("更新源重定向次数过多")
    }

    private fun remainingTimeout(deadline: Long): Int {
        val remaining = (deadline - System.nanoTime()) / 1_000_000
        if (remaining <= 0) throw SocketTimeoutException()
        return minOf(remaining, SOCKET_TIMEOUT_MILLIS.toLong()).toInt().coerceAtLeast(1)
    }

    private fun failed(current: String, message: String) = UpdateState(
        current = current, checked = true, checkedAt = System.currentTimeMillis(), error = message
    )

    private fun stopped(current: String) = UpdateState(current = current, error = "更新检查已停止")
}
