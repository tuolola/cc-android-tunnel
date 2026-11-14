// Copyright 2026 CC Android Tunnel contributors. Apache-2.0.
package dev.ccdesk.nativeapp.core

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigInteger
import java.util.concurrent.TimeUnit

enum class AppUpdateStatus { NOT_CHECKED, CHECKING, NO_RELEASE, UP_TO_DATE, UPDATE_AVAILABLE, ERROR }

data class AppRelease(
    val version: String,
    val tagName: String,
    val name: String,
    val releaseUrl: String,
    val apkUrl: String,
    val body: String = "",
    val prerelease: Boolean = false,
    val sha256: String? = null
)

data class AppUpdateState(
    val currentVersion: String,
    val automatic: Boolean = true,
    val includePrereleases: Boolean = false,
    val checking: Boolean = false,
    /** Last successful complete release-list check, in epoch milliseconds. */
    val lastCheckedAt: Long? = null,
    val latest: AppRelease? = null,
    val updateAvailable: Boolean = false,
    val error: String? = null,
    val status: AppUpdateStatus = AppUpdateStatus.NOT_CHECKED
)

/** SemVer 2.0 precedence; a conventional v tag prefix is accepted, build metadata is ignored. */
internal data class AppVersion(private val numbers: List<BigInteger>, val prerelease: List<String>) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        numbers.zip(other.numbers).forEach { (left, right) -> left.compareTo(right).let { if (it != 0) return it } }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) return when {
            prerelease.isEmpty() && other.prerelease.isEmpty() -> 0
            prerelease.isEmpty() -> 1
            else -> -1
        }
        prerelease.zip(other.prerelease).forEach { (left, right) ->
            val leftNumber = left.all(Char::isDigit); val rightNumber = right.all(Char::isDigit)
            val difference = when {
                leftNumber && rightNumber -> BigInteger(left).compareTo(BigInteger(right))
                leftNumber -> -1
                rightNumber -> 1
                else -> left.compareTo(right)
            }
            if (difference != 0) return difference
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        private val pattern = Regex("^[vV]?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$")
        fun parse(text: String): AppVersion? {
            if (text.length > 512) return null
            val match = pattern.matchEntire(text) ?: return null
            val prerelease = match.groupValues[4].takeIf(String::isNotEmpty)?.split('.') ?: emptyList()
            if (prerelease.any { it.length > 1 && it[0] == '0' && it.all(Char::isDigit) }) return null
            return AppVersion((1..3).map { BigInteger(match.groupValues[it]) }, prerelease)
        }
    }
}

private const val owner = "tuolola"
private const val repositoryName = "cc-android-tunnel"
private const val apkName = "CCtunnel.apk"
internal const val APP_UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
internal const val APP_UPDATE_FAILURE_INTERVAL_MS = 60L * 60 * 1000
private const val maxNotesLength = 20_000
private val releasesEndpoint = "https://api.github.com/repos/$owner/$repositoryName/releases".toHttpUrl()

/** Only exact release links in this repository are passed to an external browser. */
private fun safeReleaseUrl(value: String, tag: String, apk: Boolean): String? {
    if (value.any { it.isWhitespace() || it == '\\' }) return null
    val url = value.toHttpUrlOrNull() ?: return null
    if (url.scheme != "https" || url.host != "github.com" || url.port != 443 ||
        url.encodedUsername.isNotEmpty() || url.encodedPassword.isNotEmpty() || url.query != null || url.fragment != null) return null
    val path = listOf(owner, repositoryName, "releases") + if (apk) listOf("download", tag, apkName) else listOf("tag", tag)
    return url.toString().takeIf { url.pathSegments == path }
}

private fun validRelease(release: AppRelease): AppRelease? {
    val version = AppVersion.parse(release.tagName) ?: return null
    if (AppVersion.parse(release.version)?.compareTo(version) != 0) return null
    return release.copy(
        releaseUrl = safeReleaseUrl(release.releaseUrl, release.tagName, false) ?: return null,
        apkUrl = safeReleaseUrl(release.apkUrl, release.tagName, true) ?: return null,
        name = release.name.take(200), body = release.body.take(maxNotesLength),
        prerelease = release.prerelease || version.prerelease.isNotEmpty(),
        sha256 = release.sha256?.takeIf { Regex("[0-9a-fA-F]{64}").matches(it) }?.lowercase()
    )
}

internal fun parseAppReleases(array: JSONArray): List<AppRelease> = (0 until array.length()).mapNotNull { index ->
    val item = array.optJSONObject(index) ?: return@mapNotNull null
    if (item.opt("draft") != false || item.opt("prerelease") !is Boolean) return@mapNotNull null
    val tag = item.optString("tag_name")
    if (AppVersion.parse(tag) == null) return@mapNotNull null
    val assets = item.optJSONArray("assets") ?: return@mapNotNull null
    (0 until assets.length()).firstNotNullOfOrNull { assetIndex ->
        val asset = assets.optJSONObject(assetIndex) ?: return@firstNotNullOfOrNull null
        val size = asset.opt("size") as? Number ?: return@firstNotNullOfOrNull null
        if (asset.optString("name") != apkName || asset.optString("state") != "uploaded" || size.toLong() <= 0 ||
            size.toDouble() != size.toLong().toDouble()) return@firstNotNullOfOrNull null
        val digest = Regex("^sha256:([0-9a-fA-F]{64})$").matchEntire(asset.optString("digest"))?.groupValues?.get(1)
        validRelease(AppRelease(tag.removePrefix("v").removePrefix("V"), tag,
            item.optString("name").ifEmpty { tag }, item.optString("html_url"), asset.optString("browser_download_url"),
            item.optString("body").takeUnless { item.isNull("body") }.orEmpty(), item.getBoolean("prerelease"), digest))
    }
}

internal interface AppUpdateSource { suspend fun releases(): List<AppRelease> }
internal interface AppUpdateStore { fun read(): String?; fun write(value: String) }

/** This client has no connection to workbench cookies, credentials, pins or interceptors. */
internal fun appUpdateHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()

internal class GitHubAppUpdateSource(
    private val client: OkHttpClient = appUpdateHttpClient(),
    private val endpoint: HttpUrl = releasesEndpoint
) : AppUpdateSource {
    override suspend fun releases(): List<AppRelease> {
        val releases = mutableListOf<AppRelease>()
        // GitHub's release order is not SemVer order. Read every page before choosing;
        // fail rather than falsely report "latest" if the bounded list is incomplete.
        for (page in 1..20) {
            val request = Request.Builder().url(endpoint.newBuilder().addQueryParameter("per_page", "100").addQueryParameter("page", page.toString()).build())
                .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2026-03-10")
                .header("User-Agent", "CC-Android-Tunnel-update-check").build()
            val array = client.newCall(request).execute().use { response ->
                if (response.code == 403 || response.code == 429) throw UpdateCheckFailure("GitHub 暂时限制了检查请求，请稍后手动重试。")
                if (!response.isSuccessful) throw UpdateCheckFailure("更新检查失败（HTTP ${response.code}），请稍后重试。")
                val body = response.body ?: throw UpdateCheckFailure("GitHub 没有返回版本信息。")
                val source = body.source()
                if (source.request(8L * 1024 * 1024 + 1)) throw UpdateCheckFailure("GitHub 版本列表过大，无法完成检查。")
                try { JSONArray(source.readUtf8()) } catch (_: Exception) { throw UpdateCheckFailure("GitHub 返回了无法读取的版本信息。") }
            }
            releases += parseAppReleases(array)
            if (array.length() < 100) return releases
        }
        throw UpdateCheckFailure("GitHub 版本列表尚未读取完整，请稍后重试。")
    }
}

private class UpdateCheckFailure(message: String) : IOException(message)

/** Application-scoped update checks; callers share one request even when force=true. */
class AppUpdateRepository internal constructor(
    currentVersion: String,
    private val source: AppUpdateSource,
    private val store: AppUpdateStore,
    private val clock: () -> Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val version = requireNotNull(AppVersion.parse(currentVersion)) { "Invalid application version" }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()
    private var inFlight: Deferred<Unit>? = null
    private var lastAttemptAt: Long? = null
    private var cached = emptyList<AppRelease>()
    private var checkError: String? = null
    private val mutableState = MutableStateFlow(AppUpdateState(currentVersion, includePrereleases = version.prerelease.isNotEmpty()))
    val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()

    init {
        runCatching { store.read()?.let(::restore) }
        synchronized(lock) { refreshStateLocked() }
    }

    suspend fun check(force: Boolean = false) {
        val task = synchronized(lock) {
            inFlight?.takeIf { !it.isCompleted } ?: run {
                val timestamp = clock()
                val interval = if (checkError == null) APP_UPDATE_CHECK_INTERVAL_MS else APP_UPDATE_FAILURE_INTERVAL_MS
                val cooling = lastAttemptAt?.let { timestamp >= it && timestamp - it < interval } == true
                if (!force && (!state.value.automatic || cooling)) return
                scope.async(start = CoroutineStart.LAZY) { performCheck() }.also { inFlight = it }
            }
        }
        task.start()
        task.await()
    }

    fun setAutomatic(enabled: Boolean) {
        synchronized(lock) { mutableState.value = state.value.copy(automatic = enabled); persistLocked() }
        if (enabled) scope.launch { check() }
    }

    fun setIncludePrereleases(enabled: Boolean) {
        synchronized(lock) {
            if (state.value.includePrereleases == enabled) return
            mutableState.value = state.value.copy(includePrereleases = enabled)
            refreshStateLocked(); persistLocked()
        }
        // This is an explicit user action, even if automatic checks are disabled.
        // A shared in-flight request returns both channels and is filtered using
        // the current option only after it completes.
        scope.launch { check(force = true) }
    }

    private suspend fun performCheck() {
        synchronized(lock) {
            mutableState.value = state.value.copy(checking = true, error = null, status = AppUpdateStatus.CHECKING)
            lastAttemptAt = clock()
        }
        try {
            val releases = source.releases().mapNotNull(::validRelease)
            synchronized(lock) {
                // Retain both channels so changing the option or restarting during
                // the cooldown never loses an already known update.
                cached = listOf(false, true).mapNotNull { prerelease -> releases.filter { it.prerelease == prerelease }.maxWithOrNull(releaseOrder) }
                checkError = null
                mutableState.value = state.value.copy(lastCheckedAt = clock())
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            synchronized(lock) {
                checkError = if (error is UpdateCheckFailure) error.message else "无法连接 GitHub，请检查网络后重试。"
            }
        } finally {
            synchronized(lock) {
                mutableState.value = state.value.copy(checking = false)
                refreshStateLocked(); persistLocked(); inFlight = null
            }
        }
    }

    private fun refreshStateLocked() {
        val previous = state.value
        val selected = cached.filter { previous.includePrereleases || !it.prerelease }.maxWithOrNull(releaseOrder)
        val available = selected?.let { AppVersion.parse(it.version)!! > version } == true
        val error = checkError
        val status = when {
            previous.checking -> AppUpdateStatus.CHECKING
            error != null -> AppUpdateStatus.ERROR
            previous.lastCheckedAt == null -> AppUpdateStatus.NOT_CHECKED
            selected == null -> AppUpdateStatus.NO_RELEASE
            available -> AppUpdateStatus.UPDATE_AVAILABLE
            else -> AppUpdateStatus.UP_TO_DATE
        }
        mutableState.value = previous.copy(latest = selected, updateAvailable = available, error = error, status = status)
    }

    private fun restore(text: String) {
        val data = JSONObject(text)
        require(data.optInt("schema") == 1)
        val entries = data.getJSONArray("releases")
        val restored = (0 until entries.length()).map { index ->
            val release = entries.getJSONObject(index)
            requireNotNull(validRelease(AppRelease(release.getString("version"), release.getString("tagName"), release.getString("name"),
                release.getString("releaseUrl"), release.getString("apkUrl"), release.optString("body"), release.optBoolean("prerelease"), release.stringOrNull("sha256"))))
        }
        fun timestamp(key: String) = (data.opt(key) as? Number)?.toLong()?.takeIf { it >= 0 }
        cached = restored
        lastAttemptAt = timestamp("lastAttemptAt")
        checkError = data.stringOrNull("error")
        mutableState.value = state.value.copy(automatic = data.optBoolean("automatic", true),
            includePrereleases = data.optBoolean("includePrereleases", version.prerelease.isNotEmpty()),
            lastCheckedAt = timestamp("lastCheckedAt"))
    }

    private fun persistLocked() {
        val value = state.value
        val releases = cached.map { release -> json("version" to release.version, "tagName" to release.tagName,
            "name" to release.name, "releaseUrl" to release.releaseUrl, "apkUrl" to release.apkUrl,
            "body" to release.body, "prerelease" to release.prerelease, "sha256" to release.sha256) }
        runCatching {
            store.write(json("schema" to 1, "automatic" to value.automatic, "includePrereleases" to value.includePrereleases,
                "lastAttemptAt" to lastAttemptAt, "lastCheckedAt" to value.lastCheckedAt, "error" to checkError,
                "releases" to JSONArray(releases)).toString())
        }.onFailure { mutableState.value = state.value.copy(error = "更新检查设置或结果暂时无法保存。", status = AppUpdateStatus.ERROR) }
    }

    internal fun dispose() { scope.cancel() }

    companion object {
        private val releaseOrder = compareBy<AppRelease> { AppVersion.parse(it.version)!! }.thenBy { it.tagName }
        @Volatile private var instance: AppUpdateRepository? = null
        fun forApplication(context: Context): AppUpdateRepository = instance ?: synchronized(this) {
            instance ?: run {
                val application = context.applicationContext
                @Suppress("DEPRECATION")
                val currentVersion = application.packageManager.getPackageInfo(application.packageName, 0).versionName.orEmpty()
                val preferences = application.getSharedPreferences("app-updates-v1", Context.MODE_PRIVATE)
                AppUpdateRepository(currentVersion, GitHubAppUpdateSource(), object : AppUpdateStore {
                    override fun read(): String? = preferences.getString("state", null)
                    override fun write(value: String) { preferences.edit().putString("state", value).apply() }
                }).also { repository -> instance = repository; repository.scope.launch { repository.check() } }
            }
        }
    }
}
