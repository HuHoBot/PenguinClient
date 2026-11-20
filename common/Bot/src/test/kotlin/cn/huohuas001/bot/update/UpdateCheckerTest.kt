package cn.huohuas001.bot.update

import cn.huohuas001.bot.HuHoBot
import cn.huohuas001.bot.tools.Cancelable
import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.io.IOException
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckerTest {
    @BeforeTest
    fun prepare() = UpdateChecker.reset()

    @AfterTest
    fun cleanup() = UpdateChecker.reset()

    @Test
    fun `disabled configuration makes no requests and overrides a cached successful result`() {
        LocalSource { "1.10.0" }.use { source ->
            val fixture = PluginFixture(source.url)
            assertTrue(UpdateChecker.check(fixture.plugin).outdated)
            fixture.enabled = false
            val state = UpdateChecker.check(fixture.plugin, true)
            assertFalse(state.enabled)
            assertFalse(state.checked)
            assertNull(state.latest)
            assertEquals(state, UpdateChecker.cachedState(fixture.plugin))
            assertEquals(1, source.requests.get())
            fixture.enabled = true
            assertTrue(UpdateChecker.check(fixture.plugin).outdated)
            assertEquals(2, source.requests.get())
        }
    }

    @Test
    fun `caches success per plugin and force bypasses freshness`() {
        LocalSource { "1.10.0" }.use { source ->
            val first = PluginFixture(source.url)
            val second = PluginFixture(source.url)
            val state = UpdateChecker.check(first.plugin)
            assertTrue(state.checked)
            assertTrue(state.outdated)
            assertEquals("1.9.0", state.current)
            assertTrue(UpdateChecker.isFresh(state))
            assertEquals(state, UpdateChecker.check(first.plugin))
            assertEquals(1, source.requests.get())
            UpdateChecker.check(first.plugin, true)
            UpdateChecker.check(second.plugin)
            assertEquals(3, source.requests.get())
            first.current = "1.11.0"
            assertFalse(UpdateChecker.check(first.plugin).outdated)
            assertEquals(4, source.requests.get())
        }
    }

    @Test
    fun `caches failures without declaring a latest version`() {
        LocalSource { "{\"latest\":\"not-version\"}" }.use { source ->
            val fixture = PluginFixture(source.url)
            val failure = UpdateChecker.check(fixture.plugin)
            assertTrue(failure.checked)
            assertNotNull(failure.error)
            assertNull(failure.latest)
            assertFalse(failure.outdated)
            assertTrue(UpdateChecker.isFresh(failure))
            assertEquals(failure, UpdateChecker.check(fixture.plugin))
            assertEquals(1, source.requests.get())
            UpdateChecker.check(fixture.plugin, true)
            assertEquals(2, source.requests.get())
        }
    }

    @Test
    fun `explicit sources are attempted in order without an implicit third party or default fallback`() {
        LocalSource { "invalid" }.use { bad ->
            LocalSource { "{\"latest\":\"1.10.0\"}" }.use { good ->
                val fixture = PluginFixture(" ${bad.url}, ${good.url} ")
                assertEquals("1.10.0", UpdateChecker.check(fixture.plugin).latest)
                assertEquals(1, bad.requests.get())
                assertEquals(1, good.requests.get())
                fixture.sources = "https://user:VERY_SECRET@example.org/latest"
                val invalid = UpdateChecker.check(fixture.plugin)
                assertNotNull(invalid.error)
                assertFalse(invalid.error.contains("VERY_SECRET"))
                assertEquals(1, bad.requests.get())
                assertEquals(1, good.requests.get())
            }
        }
    }

    @Test
    fun `concurrent requests share a flight and expose truthful pending state`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        LocalSource {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            "1.10.0"
        }.use { source ->
            val fixture = PluginFixture(source.url)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val first = executor.submit<UpdateState> { UpdateChecker.check(fixture.plugin) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val pending = assertNotNull(UpdateChecker.cachedState(fixture.plugin))
                assertTrue(pending.checking)
                assertFalse(pending.checked)
                assertNull(pending.latest)
                assertFalse(UpdateChecker.isFresh(pending))
                val second = executor.submit<UpdateState> { UpdateChecker.check(fixture.plugin) }
                release.countDown()
                val result = first.get(5, TimeUnit.SECONDS)
                assertEquals(result, second.get(5, TimeUnit.SECONDS))
                assertEquals(1, source.requests.get())
                assertFalse(result.checking)
                assertEquals("1.10.0", result.latest)
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `disabling during a request prevents its completion from publishing an enabled result`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        LocalSource {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            "1.10.0"
        }.use { source ->
            val fixture = PluginFixture(source.url)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val request = executor.submit<UpdateState> { UpdateChecker.check(fixture.plugin) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                fixture.enabled = false
                release.countDown()
                val state = request.get(5, TimeUnit.SECONDS)
                assertFalse(state.enabled)
                assertFalse(state.checked)
                assertNull(state.latest)
                assertEquals(state, UpdateChecker.cachedState(fixture.plugin))
                assertEquals(1, source.requests.get())
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `reset cancels waiters and stale completion cannot overwrite a new runtime`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        LocalSource {
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                "9.0.0"
            } else "1.10.0"
        }.use { source ->
            val fixture = PluginFixture(source.url)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val old = executor.submit<UpdateState> { UpdateChecker.check(fixture.plugin) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                UpdateChecker.reset()
                assertNull(UpdateChecker.cachedState(fixture.plugin))
                val current = UpdateChecker.check(fixture.plugin)
                release.countDown()
                assertNotNull(old.get(5, TimeUnit.SECONDS).error)
                assertEquals("1.10.0", current.latest)
                assertEquals(current, UpdateChecker.cachedState(fixture.plugin))
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `startup is asynchronous idempotent and logs one new version notice`() {
        LocalSource { "1.10.0" }.use { source ->
            val fixture = PluginFixture(source.url)
            UpdateChecker.start(fixture.plugin)
            UpdateChecker.start(fixture.plugin)
            assertEquals(0, source.requests.get())
            assertEquals(1, fixture.tasks.size)
            fixture.tasks.remove().run()
            UpdateChecker.start(fixture.plugin)
            UpdateChecker.check(fixture.plugin, true)
            assertEquals(1, fixture.messages.size)
            assertTrue(fixture.messages.single().contains("1.10.0"))
            assertTrue(fixture.messages.single().contains(UpdateChecker.RELEASES))
            UpdateChecker.stop(fixture.plugin)
            assertNull(UpdateChecker.cachedState(fixture.plugin))
        }
    }

    @Test
    fun `cache expires at ten minutes and rejects future timestamps and pending attempts`() {
        val now = System.currentTimeMillis()
        assertTrue(UpdateChecker.isFresh(UpdateState("1.9.0", checked = true, checkedAt = now)))
        assertFalse(UpdateChecker.isFresh(UpdateState("1.9.0", checked = true, checkedAt = now - UpdateChecker.CACHE_MILLIS)))
        assertFalse(UpdateChecker.isFresh(UpdateState("1.9.0", checked = true, checkedAt = now + 60_000)))
        assertFalse(UpdateChecker.isFresh(UpdateState("1.9.0", checking = true, checkedAt = now)))
    }

    @Test
    fun `oversized responses fail nonfatally and are cached`() {
        LocalSource { "1".repeat(1024 * 1024 + 1) }.use { source ->
            val fixture = PluginFixture(source.url)
            val state = UpdateChecker.check(fixture.plugin)
            assertNotNull(state.error)
            assertNull(state.latest)
            assertEquals(state, UpdateChecker.check(fixture.plugin))
            assertEquals(1, source.requests.get())
        }
    }

    private class PluginFixture(source: String) {
        @Volatile var sources = source
        @Volatile var current = "1.9.0"
        @Volatile var enabled = true
        val tasks = ConcurrentLinkedQueue<Runnable>()
        val messages = ConcurrentLinkedQueue<String>()
        val plugin = Proxy.newProxyInstance(
            HuHoBot::class.java.classLoader, arrayOf(HuHoBot::class.java)
        ) { proxy, method, arguments ->
            when (method.name) {
                "getPluginVersion" -> current
                "isUpdateCheckEnabled" -> enabled
                "getUpdateCheckUrls" -> sources
                "submitAsync" -> {
                    val task = arguments!![0] as Runnable
                    tasks.add(task)
                    object : Cancelable {
                        override fun cancel() { tasks.remove(task) }
                    }
                }
                "log_info", "log_warning", "log_error" -> { messages.add(arguments!![0] as String); null }
                "equals" -> proxy === arguments!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "UpdateCheckerTestPlugin"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as HuHoBot
    }

    private class LocalSource(private val response: () -> String) : Closeable {
        val requests = AtomicInteger()
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/latest") { exchange ->
                try {
                    requests.incrementAndGet()
                    val bytes = response().toByteArray(Charsets.UTF_8)
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } catch (_: IOException) {
                    // Reset/size-limit tests intentionally close the HTTP connection.
                } finally {
                    exchange.close()
                }
            }
            this.executor = this@LocalSource.executor
            start()
        }
        val url: String get() = "http://127.0.0.1:${server.address.port}/latest"

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
