package dev.ccdesk.nativeapp.core

import okhttp3.CookieJar
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
            val url = server.url("/ws")
            server.enqueue(MockResponse().setBody("{}"))
            val correct = secureClient(url.origin(),fingerprint(cert))
            correct.newCall(Request.Builder().url(url).build()).execute().use { assertEquals(200,it.code) }
            val wrongHost = secureClient("https://different.invalid:${server.port}",fingerprint(cert))
            assertThrows(Exception::class.java) { wrongHost.newCall(Request.Builder().url(url).build()).execute().close() }
            val system = secureClient(url.origin(),"")
            assertThrows(Exception::class.java) { system.newCall(Request.Builder().url(url).build()).execute().close() }
            val wrong = secureClient(url.origin(),"00".repeat(32))
            assertThrows(Exception::class.java) { wrong.newCall(Request.Builder().url(url).build()).execute().close() }
            listOf(correct,wrongHost,system,wrong).forEach { it.connectionPool.evictAll(); it.dispatcher.executorService.shutdown() }
        } finally { server.shutdown() }
    }
    @Test fun systemCaModeRetainsDefaultHostnameVerifierAndRedirectsStayDisabled() {
        val client = secureClient("https://example.test","")
        assertSame(okhttp3.OkHttpClient().hostnameVerifier,client.hostnameVerifier)
        assertFalse(client.followRedirects); assertFalse(client.followSslRedirects)
    }
    @Test fun serviceClientNeverStoresOrSendsResponseCookies() {
        val cert = HeldCertificate.Builder().commonName("private-workbench").build()
        val server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(),false)
        server.start()
        val client = secureClient(server.url("/").origin(),fingerprint(cert))
        try {
            assertSame(CookieJar.NO_COOKIES,client.cookieJar)
            server.enqueue(MockResponse().setBody("{}").addHeader("Set-Cookie","session=secret; Path=/; Max-Age=3600; Secure; HttpOnly"))
            server.enqueue(MockResponse().setBody("{}"))
            repeat(2) { client.newCall(Request.Builder().url(server.url("/ws")).build()).execute().close() }
            repeat(2) { assertNull(server.takeRequest().getHeader("Cookie")) }
        } finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown(); server.shutdown() }
    }
}
