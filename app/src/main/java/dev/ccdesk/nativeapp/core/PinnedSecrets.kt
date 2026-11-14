// Copyright 2026 CC Desk Android contributors.
// Native Android rewrite/adaptation of CC Desk Tunnel, modified in 2026.
// Apache-2.0; upstream attribution and third-party exceptions are listed in NOTICE.

package dev.ccdesk.nativeapp.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.security.KeyStore
import java.security.MessageDigest
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val keyAlias = "dev.ccdesk.native.secrets.v1"

private fun keyDigest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal interface CookieSecrets {
    fun write(name: String, value: String)
    fun read(name: String): String
    fun cookieName(origin: String, pin: String, path: String): String
}

internal class PinnedSecrets(context: Context) : CookieSecrets {
    private val preferences = context.getSharedPreferences("workbench-native-secrets-v1", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }

    @Synchronized
    override fun write(name: String, value: String) {
        val editor = preferences.edit()
        if (value.isEmpty()) {
            editor.remove(name)
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            editor.putString(name, Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP))
        }
        check(editor.commit()) { "Unable to save workbench credentials" }
    }

    @Synchronized
    override fun read(name: String): String {
        val stored = preferences.getString(name, null) ?: return ""
        return try {
            val data = Base64.decode(stored, Base64.NO_WRAP)
            if (data.size < 28) throw GeneralSecurityException("Invalid encrypted value")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            // Restoring preferences on another device cannot restore the Keystore key.
            write(name, "")
            ""
        } catch (_: IllegalArgumentException) {
            write(name, "")
            ""
        }
    }

    fun credentialName(url: HttpUrl) = "device." + keyDigest(url.toString())

    private fun cookiePrefix(origin: String) = "cookie." + keyDigest(origin) + "."

    override fun cookieName(origin: String, pin: String, path: String) =
        cookiePrefix(origin) + pin + "." + keyDigest(path)

    @Synchronized
    fun clearCookies(origin: String) {
        val prefix = cookiePrefix(origin)
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        check(editor.commit()) { "Unable to clear workbench cookies" }
    }
}

internal val workbenchCookie = Regex("cc_web_[0-9a-f]{32}_[0-9a-f]{16}")
private val sessionSecret = Regex("[a-f0-9]{64}")

internal class PinnedCookies(
    private val storage: CookieSecrets, private val origin: String, private val pin: String
) : CookieJar {
    private var active = true

    // An old response may finish after the user changes the trust decision.
    @Synchronized fun retire() { active = false }

    private fun path(url: HttpUrl): String = url.encodedPath.substringBeforeLast("/api/") + "/"

    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!active || url.origin() != origin) return
        val prefix = path(url)
        val key = storage.cookieName(origin, pin, prefix)
        for (cookie in cookies) {
            if (!workbenchCookie.matches(cookie.name) || !cookie.secure || !cookie.httpOnly ||
                !cookie.hostOnly || cookie.path != prefix || !cookie.persistent) continue
            if (cookie.expiresAt <= System.currentTimeMillis()) {
                storage.write(key, "")
            } else if (sessionSecret.matches(cookie.value)) {
                storage.write(key, cookie.toString())
            }
        }
    }

    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!active || url.origin() != origin) return emptyList()
        val key = storage.cookieName(origin, pin, path(url))
        val stored = storage.read(key)
        if (stored.isEmpty()) return emptyList()
        val cookie = Cookie.parse(url, stored)
        if (cookie != null && cookie.matches(url) && cookie.expiresAt > System.currentTimeMillis()) {
            return listOf(cookie)
        }
        storage.write(key, "")
        return emptyList()
    }
}
