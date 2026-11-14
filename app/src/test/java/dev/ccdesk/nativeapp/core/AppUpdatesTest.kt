package dev.ccdesk.nativeapp.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppUpdatesTest {
    private class MemoryStore : AppUpdateStore {
        @Volatile var value: String? = null
        override fun read() = value
        override fun write(value: String) { this.value = value }
    }

    private fun release(tag: String, prerelease: Boolean = tag.contains('-')) = json(
        "tag_name" to tag, "name" to "Release $tag", "draft" to false, "prerelease" to prerelease,
        "html_url" to "https://github.com/tuolola/cc-android-tunnel/releases/tag/$tag",
        "body" to "Public release notes",
        "assets" to JSONArray().put(json("name" to "CCtunnel.apk", "state" to "uploaded", "size" to 1234,
            "browser_download_url" to "https://github.com/tuolola/cc-android-tunnel/releases/download/$tag/CCtunnel.apk"))
    )

    private fun asset(release: JSONObject) = release.getJSONArray("assets").getJSONObject(0)
    private fun releases(vararg items: JSONObject) = JSONArray(items.toList()).toString()
    private fun parsed(tag: String) = parseAppReleases(JSONArray().put(release(tag))).single()

    private class Harness : AutoCloseable {
        val server = MockWebServer().apply { start() }
        val client = appUpdateHttpClient()
        var now = 1_800_000_000_000L
        private val repositories = mutableListOf<AppUpdateRepository>()
        fun repository(current: String = "1.0.1-native", store: MemoryStore = MemoryStore()) = AppUpdateRepository(
            current, GitHubAppUpdateSource(client, server.url("/repos/tuolola/cc-android-tunnel/releases")), store,
            clock = { now }
        ).also(repositories::add)
        override fun close() {
            repositories.forEach(AppUpdateRepository::dispose)
            client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }

    @Test fun semverOrdersNumericComponentsAndPrereleasesWithoutIntegerOverflow() {
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta",
            "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1-native", "1.0.1",
            "1.9.0", "1.10.0", "2.0.0", "999999999999999999999.0.0")
        ordered.zipWithNext().forEach { (a, b) -> assertTrue("$a must precede $b", AppVersion.parse(a)!! < AppVersion.parse(b)!!) }
        assertEquals(0, AppVersion.parse("v1.2.3+build.1")!!.compareTo(AppVersion.parse("1.2.3+different")!!))
        assertTrue(AppVersion.parse("1.0.0-999999999999999999999")!! < AppVersion.parse("1.0.0-a")!!)
        for (invalid in listOf("1", "1.2", "01.2.3", "1.02.3", "1.2.03", "1.2.3-01", "1.2.3-", "1.2.3-a..b", "1.2.3+", " 1.2.3", "1.2.3/evil")) {
            assertNull("Invalid SemVer accepted: $invalid", AppVersion.parse(invalid))
        }
    }

    @Test fun onlyUploadedNonemptyExactApkFromPublishedSemverReleaseIsAccepted() {
        val invalid = listOf(
            release("9.0.0").put("draft", true),
            release("8.0.0").put("assets", JSONArray()),
            release("7.0.0").also { asset(it).put("name", "app-debug.apk") },
            release("6.0.0").also { asset(it).put("state", "starter") },
            release("5.0.0").also { asset(it).put("size", 0) },
            release("4.0.0").also { asset(it).put("size", "1234") },
            release("3.0.0").also { asset(it).put("size", 1.5) },
            release("not-semver"), release("2.0.0").put("draft", JSONObject.NULL)
        )
        val valid = release("v1.0.2-native").put("body", "x".repeat(25_000))
        asset(valid).put("digest", "sha256:" + "AB".repeat(32))
        val result = parseAppReleases(JSONArray(invalid + valid)).single()
        assertEquals("1.0.2-native", result.version); assertTrue(result.prerelease)
        assertEquals(20_000, result.body.length); assertEquals("ab".repeat(32), result.sha256)
    }

    @Test fun releaseAndApkUrlsCannotEscapeTheExactRepositoryTagAndHttpsOrigin() {
        val tag = "v2.0.0"
        val unsafe = listOf(
            "http://github.com/tuolola/cc-android-tunnel/releases/download/$tag/CCtunnel.apk",
            "https://github.com.evil.invalid/tuolola/cc-android-tunnel/releases/download/$tag/CCtunnel.apk",
            "https://user@github.com/tuolola/cc-android-tunnel/releases/download/$tag/CCtunnel.apk",
            "https://github.com:444/tuolola/cc-android-tunnel/releases/download/$tag/CCtunnel.apk",
            "https://github.com/other/cc-android-tunnel/releases/download/$tag/CCtunnel.apk",
            "https://github.com/tuolola/other/releases/download/$tag/CCtunnel.apk",
            "https://github.com/tuolola/cc-android-tunnel/releases/download/v9.0.0/CCtunnel.apk",
            "https://github.com/tuolola/cc-android-tunnel/releases/download/$tag/other.apk",
            "https://github.com/tuolola/cc-android-tunnel/releases/download/$tag/CCtunnel.apk?redirect=evil",
            "https://github.com/tuolola/cc-android-tunnel/releases/download/$tag/CCtunnel.apk#fragment"
        )
        for (url in unsafe) {
            val item = release(tag).also { asset(it).put("browser_download_url", url) }
            assertTrue("Unsafe asset URL accepted: $url", parseAppReleases(JSONArray().put(item)).isEmpty())
        }
        for (url in listOf("https://evil.invalid/releases/tag/$tag", "https://github.com/tuolola/other/releases/tag/$tag",
            "https://github.com/tuolola/cc-android-tunnel/releases/tag/v3.0.0", "https://github.com/tuolola/cc-android-tunnel/releases")) {
            assertTrue(parseAppReleases(JSONArray().put(release(tag).put("html_url", url))).isEmpty())
        }
    }

    @Test fun updateClientKeepsPlatformTlsAndNeverUsesCookiesOrAuthentication() {
        val client = appUpdateHttpClient()
        assertSame(OkHttpClient().hostnameVerifier, client.hostnameVerifier)
        assertSame(CookieJar.NO_COOKIES, client.cookieJar)
        assertTrue(client.interceptors.isEmpty()); assertTrue(client.networkInterceptors.isEmpty())
        assertFalse(client.followRedirects); assertFalse(client.followSslRedirects)
        client.dispatcher.executorService.shutdown()
    }

    @Test fun realHttpChoosesNewestSemverFromUnorderedListAndSendsNoCredentials() = runBlocking {
        Harness().use { h ->
            h.server.enqueue(MockResponse().setBody(releases(release("1.9.0"), release("1.10.0"), release("1.3.0"), release("3.0.0").put("draft", true))))
            val repo = h.repository("1.0.1-native")
            repo.check()
            assertEquals(AppUpdateStatus.UPDATE_AVAILABLE, repo.state.value.status)
            assertEquals("1.10.0", repo.state.value.latest?.version)
            assertTrue(repo.state.value.includePrereleases)
            val request = h.server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/repos/tuolola/cc-android-tunnel/releases?per_page=100&page=1", request.path)
            assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Cookie")); assertNull(request.getHeader("Origin"))
            assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        }
    }

    @Test fun fullReleasePagesAreTraversedBeforeChoosingLatest() = runBlocking {
        Harness().use { h ->
            h.server.enqueue(MockResponse().setBody(JSONArray((1..100).map { release("1.0.$it") }).toString()))
            h.server.enqueue(MockResponse().setBody(releases(release("9.0.0"))))
            val repo = h.repository(); repo.check()
            assertEquals("9.0.0", repo.state.value.latest?.version); assertEquals(2, h.server.requestCount)
            h.server.takeRequest(); assertTrue(h.server.takeRequest().path!!.endsWith("page=2"))
        }
    }

    @Test fun failedLaterPageNeverPublishesAPartialListAsSuccessfullyChecked() = runBlocking {
        Harness().use { h ->
            h.server.enqueue(MockResponse().setBody(JSONArray((1..100).map { release("2.0.$it") }).toString()))
            h.server.enqueue(MockResponse().setResponseCode(503))
            val repo = h.repository(); repo.check()
            assertEquals(AppUpdateStatus.ERROR, repo.state.value.status)
            assertNull(repo.state.value.lastCheckedAt); assertNull(repo.state.value.latest)
            assertFalse(repo.state.value.updateAvailable); assertEquals(2, h.server.requestCount)
        }
    }

    @Test fun stableInstallExcludesBothExplicitAndSemverPrereleases() = runBlocking {
        Harness().use { h ->
            h.server.enqueue(MockResponse().setBody(releases(release("2.0.0-native", false), release("3.0.0", true), release("1.0.0"))))
            val repo = h.repository("1.0.0"); repo.check()
            assertFalse(repo.state.value.includePrereleases)
            assertEquals("1.0.0", repo.state.value.latest?.version)
            assertEquals(AppUpdateStatus.UP_TO_DATE, repo.state.value.status)
        }
    }

    @Test fun cachedKnownUpdateAndOptionsSurviveRestartDuringCooldown() = runBlocking {
        Harness().use { h ->
            val store = MemoryStore()
            h.server.enqueue(MockResponse().setBody(releases(release("1.1.0"))))
            val original = h.repository(store = store); original.check(); original.setAutomatic(false)
            val restored = h.repository(store = store)
            assertEquals(AppUpdateStatus.UPDATE_AVAILABLE, restored.state.value.status)
            assertEquals("1.1.0", restored.state.value.latest?.version)
            assertFalse(restored.state.value.automatic); assertEquals(h.now, restored.state.value.lastCheckedAt)
            restored.check(); assertEquals(1, h.server.requestCount)
            h.server.enqueue(MockResponse().setBody(releases(release("1.2.0"))))
            restored.check(force = true)
            assertEquals("1.2.0", restored.state.value.latest?.version); assertEquals(2, h.server.requestCount)
        }
    }

    @Test fun httpFailureRetainsKnownUpdateAndFailedAttemptCooldownAcrossRestart() = runBlocking {
        Harness().use { h ->
            val store = MemoryStore(); val repo = h.repository(store = store)
            h.server.enqueue(MockResponse().setBody(releases(release("1.2.0")))); repo.check()
            val successfulAt = repo.state.value.lastCheckedAt
            h.now += 1_000
            h.server.enqueue(MockResponse().setResponseCode(503)); repo.check(force = true)
            assertEquals(AppUpdateStatus.ERROR, repo.state.value.status)
            assertEquals(successfulAt, repo.state.value.lastCheckedAt)
            assertTrue(repo.state.value.updateAvailable); assertEquals("1.2.0", repo.state.value.latest?.version)
            val restored = h.repository(store = store)
            restored.check(); assertEquals(AppUpdateStatus.ERROR, restored.state.value.status); assertEquals(2, h.server.requestCount)
            h.now += APP_UPDATE_FAILURE_INTERVAL_MS
            h.server.enqueue(MockResponse().setBody(releases(release("1.3.0")))); restored.check()
            assertEquals("1.3.0", restored.state.value.latest?.version); assertEquals(3, h.server.requestCount)
        }
    }

    @Test fun rateLimitsMalformedResponsesAndRedirectsNeverMeanUpToDate() = runBlocking {
        for (response in listOf(MockResponse().setResponseCode(429), MockResponse().setResponseCode(403),
            MockResponse().setBody("not json"),
            MockResponse().setResponseCode(302).setHeader("Location", "https://evil.invalid/releases"))) {
            Harness().use { h ->
                h.server.enqueue(response); val repo = h.repository(); repo.check()
                assertEquals(AppUpdateStatus.ERROR, repo.state.value.status)
                assertNotNull(repo.state.value.error); assertNull(repo.state.value.lastCheckedAt)
                assertFalse(repo.state.value.checking); assertFalse(repo.state.value.updateAvailable)
                repo.check(); assertEquals(1, h.server.requestCount)
            }
        }
    }

    @Test fun successfulEmptyReleaseListIsCachedAsNoReleaseRatherThanNetworkFailureOrUpToDate() = runBlocking {
        Harness().use { h ->
            val store = MemoryStore(); val repo = h.repository(store = store)
            h.server.enqueue(MockResponse().setBody(releases(release("1.2.0").put("assets", JSONArray()))))
            repo.check()
            assertEquals(AppUpdateStatus.NO_RELEASE, repo.state.value.status)
            assertEquals(h.now, repo.state.value.lastCheckedAt)
            assertNull(repo.state.value.error); assertNull(repo.state.value.latest); assertFalse(repo.state.value.updateAvailable)
            val restored = h.repository(store = store); restored.check()
            assertEquals(AppUpdateStatus.NO_RELEASE, restored.state.value.status)
            assertEquals(h.now, restored.state.value.lastCheckedAt); assertEquals(1, h.server.requestCount)
        }
    }

    @Test fun stableChannelWithoutStableAssetsIsANormalEmptyState() = runBlocking {
        Harness().use { h ->
            h.server.enqueue(MockResponse().setBody(releases(release("1.1.0-beta"))))
            val repo = h.repository("1.0.0"); repo.check()
            assertEquals(AppUpdateStatus.NO_RELEASE, repo.state.value.status)
            assertNull(repo.state.value.error); assertFalse(repo.state.value.updateAvailable)
            assertEquals(h.now, repo.state.value.lastCheckedAt)
        }
    }

    @Test fun successCooldownAndClockRollbackHaveExplicitRetryBehavior() = runBlocking {
        Harness().use { h ->
            val repo = h.repository(); h.server.enqueue(MockResponse().setBody(releases(release("1.1.0")))); repo.check()
            h.now += APP_UPDATE_CHECK_INTERVAL_MS - 1; repo.check(); assertEquals(1, h.server.requestCount)
            h.now += 1; h.server.enqueue(MockResponse().setBody(releases(release("1.2.0")))); repo.check()
            assertEquals(2, h.server.requestCount)
            h.now -= 60_000; h.server.enqueue(MockResponse().setBody(releases(release("1.3.0")))); repo.check()
            assertEquals(3, h.server.requestCount); assertEquals("1.3.0", repo.state.value.latest?.version)
        }
    }

    @Test fun overlappingForceChecksShareOneRealHttpRequestAndCallerCancellationDoesNotCancelOthers() = runBlocking {
        Harness().use { h ->
            val entered = CountDownLatch(1); val releaseResponse = CountDownLatch(1)
            h.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    entered.countDown()
                    check(releaseResponse.await(5, TimeUnit.SECONDS))
                    return MockResponse().setBody(releases(release("2.0.0")))
                }
            }
            val repo = h.repository()
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) { repo.check(force = true) }
            val checks = (1..12).map { async(start = CoroutineStart.UNDISPATCHED) { repo.check(force = true) } }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); assertTrue(repo.state.value.checking)
            cancelled.cancelAndJoin(); releaseResponse.countDown(); checks.awaitAll()
            assertEquals(1, h.server.requestCount); assertEquals(AppUpdateStatus.UPDATE_AVAILABLE, repo.state.value.status)
        }
    }

    @Test fun channelChangeDuringRequestUsesLatestOptionAndRemainsManualWhenAutomaticIsDisabled() = runTest {
        val response = CompletableDeferred<List<AppRelease>>()
        var requests = 0
        val repo = AppUpdateRepository("1.0.1-native", object : AppUpdateSource {
            override suspend fun releases(): List<AppRelease> { requests++; return response.await() }
        }, MemoryStore(), clock = { 1_000L }, dispatcher = StandardTestDispatcher(testScheduler))
        try {
            val initial = async { repo.check() }; runCurrent()
            repo.setAutomatic(false); repo.setIncludePrereleases(false); runCurrent()
            response.complete(listOf(parsed("3.0.0-beta"), parsed("2.0.0"))); advanceUntilIdle(); initial.await()
            assertEquals(1, requests); assertFalse(repo.state.value.includePrereleases)
            assertEquals("2.0.0", repo.state.value.latest?.version)
            repo.setIncludePrereleases(true); advanceUntilIdle()
            assertEquals(2, requests); assertEquals("3.0.0-beta", repo.state.value.latest?.version)
            assertFalse(repo.state.value.automatic)
        } finally { repo.dispose() }
    }

    @Test fun corruptCacheDoesNotInventSuccessfulCheckOrUnsafeDownload() = runTest {
        val store = MemoryStore().apply { value = "{broken" }
        val repo = AppUpdateRepository("1.0.0", object : AppUpdateSource {
            override suspend fun releases() = emptyList<AppRelease>()
        }, store, dispatcher = StandardTestDispatcher(testScheduler))
        try {
            assertEquals(AppUpdateStatus.NOT_CHECKED, repo.state.value.status)
            assertNull(repo.state.value.latest); assertNull(repo.state.value.lastCheckedAt)
        } finally { repo.dispose() }
    }
}
