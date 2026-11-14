package dev.ccdesk.nativeapp.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in: only the public GitHub feed, never a workbench or saved credentials. */
@RunWith(AndroidJUnit4::class)
class AppUpdateLiveTest {
    @Test fun publicReleaseFeedContainsAnInstallableNativeApk() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("runLiveUpdates") == "true")
        val releases = withTimeout(60_000) { withContext(Dispatchers.IO) { GitHubAppUpdateSource().releases() } }
        assertTrue("The public repository must expose a usable APK", releases.isNotEmpty())
        val newest = releases.maxBy { AppVersion.parse(it.version)!! }
        assertTrue(newest.apkUrl.startsWith("https://github.com/tuolola/cc-android-tunnel/releases/download/"))
        assertTrue(newest.apkUrl.endsWith("/CCtunnel.apk"))
        assertTrue(newest.releaseUrl.startsWith("https://github.com/tuolola/cc-android-tunnel/releases/tag/"))
        assertNotNull(AppVersion.parse(newest.version))
        println("Public update feed verified: ${newest.tagName}; APK checksum metadata present: ${newest.sha256 != null}")
    }
}
