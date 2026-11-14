package dev.ccdesk.nativeapp.core

import okhttp3.CookieJar
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.security.cert.CertificateException

class SecurityTest {
    private fun fingerprint(certificate: HeldCertificate) = MessageDigest.getInstance("SHA-256").digest(certificate.certificate.encoded).joinToString("") { "%02X".format(it) }
    @Test fun fingerprintRequiresFullSha256AndNormalizesOpenSslFormat() {
        val pin = "AB".repeat(32)
        assertEquals(pin,normalizeFingerprint("SHA256 Fingerprint=" + pin.chunked(2).joinToString(":")))
        assertEquals("",normalizeFingerprint(" "))
        listOf("AB","AB:".repeat(32),"GG".repeat(32)).forEach { assertThrows(IllegalArgumentException::class.java) { normalizeFingerprint(it) } }
    }
    @Test fun pinnedTrustRejectsWrongLeafEmptyChainAndExpiredCertificates() {
        val cert = HeldCertificate.Builder().commonName("workbench").build()
        val other = HeldCertificate.Builder().commonName("workbench").build()
        val trust = PinnedTrustManager(fingerprint(cert))
        trust.checkServerTrusted(arrayOf(cert.certificate),"RSA")
        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(arrayOf(other.certificate),"RSA") }
        assertThrows(CertificateException::class.java) { trust.checkServerTrusted(emptyArray(),"RSA") }
        val expired = HeldCertificate.Builder().commonName("expired").validityInterval(1,1000).build()
        assertThrows(CertificateException::class.java) { PinnedTrustManager(fingerprint(expired)).checkServerTrusted(arrayOf(expired.certificate),"RSA") }
    }
    @Test fun realTlsAllowsOnlyConfiguredPinAndHostAndSystemModeRejectsUntrustedCa() {
        val cert = HeldCertificate.Builder().commonName("private-workbench").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(),false)
        server.start()
        try {
            val url = server.url("/api/session")
            server.enqueue(MockResponse().setBody("{\"authenticated\":false}"))
            val correct = secureClient(url.origin(),fingerprint(cert),CookieJar.NO_COOKIES)
            correct.newCall(Request.Builder().url(url).build()).execute().use { assertEquals(200,it.code) }
            val wrongHost = secureClient("https://different.invalid:${server.port}",fingerprint(cert),CookieJar.NO_COOKIES)
            assertThrows(Exception::class.java) { wrongHost.newCall(Request.Builder().url(url).build()).execute().close() }
            val system = secureClient(url.origin(),"",CookieJar.NO_COOKIES)
            assertThrows(Exception::class.java) { system.newCall(Request.Builder().url(url).build()).execute().close() }
            val wrong = secureClient(url.origin(),"00".repeat(32),CookieJar.NO_COOKIES)
            assertThrows(Exception::class.java) { wrong.newCall(Request.Builder().url(url).build()).execute().close() }
            listOf(correct,wrongHost,system,wrong).forEach { it.connectionPool.evictAll(); it.dispatcher.executorService.shutdown() }
        } finally { server.shutdown() }
    }
    @Test fun systemCaModeRetainsDefaultHostnameVerifierAndRedirectsStayDisabled() {
        val client = secureClient("https://example.test","",CookieJar.NO_COOKIES)
        assertSame(okhttp3.OkHttpClient().hostnameVerifier,client.hostnameVerifier)
        assertFalse(client.followRedirects); assertFalse(client.followSslRedirects)
    }
    private class MemorySecrets : CookieSecrets {
        val data = mutableMapOf<String,String>()
        override fun write(name: String, value: String) { if (value.isEmpty()) data.remove(name) else data[name] = value }
        override fun read(name: String) = data[name].orEmpty()
        override fun cookieName(origin: String, pin: String, path: String) = "$origin|$pin|$path"
    }
    private fun cookie(url: String, extra: String = "", secure: Boolean = true, httpOnly: Boolean = true, path: String = "/workbench/"): Cookie {
        val name = "cc_web_" + "a".repeat(32) + "_" + "b".repeat(16)
        return Cookie.parse(url.toHttpUrl(),"$name=${"c".repeat(64)}; Path=$path; Max-Age=3600" + (if (secure) "; Secure" else "") + (if (httpOnly) "; HttpOnly" else "") + extra)!!
    }
    @Test fun cookieJarRequiresHostOnlySecureHttpOnlyExpectedNamePathAndSecret() {
        val storage = MemorySecrets(); val origin = "https://example.test"; val url = "$origin/workbench/api/login".toHttpUrl()
        val jar = PinnedCookies(storage,origin,"pin")
        listOf(cookie(url.toString(),secure = false),cookie(url.toString(),httpOnly = false),cookie(url.toString(),extra = "; Domain=example.test"),cookie(url.toString(),path = "/"),Cookie.parse(url,"other=${"c".repeat(64)}; Path=/workbench/; Max-Age=3600; Secure; HttpOnly")!!).forEach { jar.saveFromResponse(url,listOf(it)); assertTrue(storage.data.isEmpty()) }
        jar.saveFromResponse(url,listOf(cookie(url.toString())))
        assertEquals(1,jar.loadForRequest("$origin/workbench/api/ws".toHttpUrl()).size)
        assertTrue(jar.loadForRequest("$origin/other/api/ws".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://different.test/workbench/api/ws".toHttpUrl()).isEmpty())
        assertTrue(PinnedCookies(storage,origin,"different-pin").loadForRequest(url).isEmpty())
        assertTrue(PinnedCookies(storage,origin,"").loadForRequest(url).isEmpty())
    }
    @Test fun retiringCookieJarPreventsLateLoginFromRestoringOldTrustCredentials() {
        val storage = MemorySecrets(); val url = "https://example.test/workbench/api/login".toHttpUrl()
        val jar = PinnedCookies(storage,url.origin(),"old-pin")
        jar.saveFromResponse(url,listOf(cookie(url.toString())))
        assertFalse(storage.data.isEmpty())
        jar.retire(); storage.data.clear()
        jar.saveFromResponse(url,listOf(cookie(url.toString())))
        assertTrue(storage.data.isEmpty()); assertTrue(jar.loadForRequest(url).isEmpty())
    }
}
