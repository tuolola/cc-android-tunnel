package dev.ccdesk.nativeapp.core

import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

internal fun parseUrl(input: String, websocket: Boolean): HttpUrl {
    val scheme = if (websocket) "wss://" else "https://"
    require(input.startsWith(scheme, true) && input.none { it in "?#\\" }) { "请输入完整的 HTTPS/WSS 工作台地址。" }
    val authority = input.substring(scheme.length).substringBefore('/')
    require('@' !in authority) { "地址不可包含用户名或密码。" }
    val url = ("https://" + input.substring(scheme.length)).toHttpUrl()
    require(url.encodedUsername.isEmpty() && url.encodedPassword.isEmpty() && url.encodedQuery == null && url.encodedFragment == null)
    return url
}
internal fun HttpUrl.origin(): String = newBuilder().encodedPath("/").build().toString().removeSuffix("/")
fun normalizeEndpoint(raw: String): String {
    if (raw.isBlank()) return ""
    return try { val url = parseUrl(raw.trim(),false); url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/").build().toString() }
    catch (_: IllegalArgumentException) { throw IllegalArgumentException("请输入完整的 HTTPS 工作台地址，可包含端口和入口路径。") }
}
fun normalizeServiceAddress(raw: String): String = try {
    val url = parseUrl(raw.trim(),true)
    require(url.encodedPath == "/ws")
    url.toString().replaceFirst("https:","wss:")
} catch (_: IllegalArgumentException) { throw IllegalArgumentException("请输入完整的服务地址，例如 wss://example.com:8787/ws。") }
fun normalizeFingerprint(raw: String): String {
    if (raw.isBlank()) return ""
    val value = raw.trim().replace(Regex("^SHA-?256\\s+Fingerprint\\s*=\\s*", RegexOption.IGNORE_CASE), "")
    require(Regex("(?i)([0-9a-f]{64}|([0-9a-f]{2}:){31}[0-9a-f]{2})").matches(value)) { "请输入完整的 SHA-256 证书指纹，应为 64 位十六进制字符，可包含冒号。" }
    return value.replace(":", "").uppercase()
}
internal class PinnedTrustManager(pin: String) : X509TrustManager {
    private val expected = normalizeFingerprint(pin).also { require(it.isNotEmpty()) }.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { throw CertificateException("Client certificates are unsupported") }
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("Missing server certificate")
        if (!MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(leaf.encoded))) throw CertificateException("PIN_MISMATCH: certificate fingerprint mismatch")
        leaf.checkValidity()
    }
}
internal fun secureClient(origin: String, pin: String, cookies: CookieJar): OkHttpClient {
    val builder = OkHttpClient.Builder().cookieJar(cookies).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS)
    if (pin.isNotEmpty()) {
        val trust = PinnedTrustManager(pin)
        val context = SSLContext.getInstance("TLS").apply { init(null,arrayOf(trust),null) }
        val expectedHost = origin.toHttpUrl().host
        builder.sslSocketFactory(context.socketFactory,trust)
        // Baseline trust semantics: an explicitly confirmed leaf fingerprint authenticates ONLY
        // this configured host, including self-signed IP endpoints. Other hosts always fail.
        builder.hostnameVerifier { hostname, session ->
            val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
            hostname == expectedHost && leaf != null && runCatching { trust.checkServerTrusted(arrayOf(leaf),leaf.publicKey.algorithm) }.isSuccess
        }
    }
    // No custom TrustManager or HostnameVerifier in system-CA mode.
    return builder.build()
}
class CertificateFailure(message: String, cause: Throwable? = null) : IllegalStateException(message,cause)
internal fun networkError(error: Throwable): Throwable {
    val chain = generateSequence(error) { it.cause }.toList()
    val all = chain.joinToString(" ") { it.message.orEmpty() }
    val message = when {
        all.contains("PIN_MISMATCH",true) || all.contains("fingerprint mismatch",true) -> "证书指纹不匹配，连接已停止。请在服务器上核对当前的 SHA-256 指纹。"
        chain.any { it is CertificateExpiredException || it is CertificateNotYetValidException } || Regex("not yet valid|expired|certificate validity",RegexOption.IGNORE_CASE).containsMatchIn(all) -> "证书不在有效期内。请检查手机时间和服务器证书的有效期。"
        Regex("Trust anchor|CertPathValidatorException|unable to find valid certification path|TrustManager",RegexOption.IGNORE_CASE).containsMatchIn(all) -> "手机不信任此证书。如果工作台使用自签证书，请填写服务器提供的 SHA-256 指纹后重新连接。"
        Regex("hostname .*not verified|subject alternative",RegexOption.IGNORE_CASE).containsMatchIn(all) -> "访问地址与证书不匹配。请核对工作台地址，自签工作台可填写服务器提供的 SHA-256 指纹。"
        else -> return error
    }
    return CertificateFailure(message,error)
}
