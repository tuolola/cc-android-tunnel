package dev.ccdesk.nativeapp.core

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Uses only isolated preference files; never clears an existing user credential or Keystore key. */
@RunWith(AndroidJUnit4::class)
class CredentialVaultTest {
    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val prefix = "vault-test-${UUID.randomUUID()}-"
        private val opened = mutableListOf<SharedPreferences>()
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences(prefix + name,mode).also { if (it !in opened) opened += it }
        fun clear() { opened.forEach { check(it.edit().clear().commit()) } }
    }
    @Test fun credentialsAreEncryptedRandomizedAndBoundToEntryName() {
        val context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val secrets = PinnedSecrets(context)
            val value = "test-only-service-credential-0123456789"
            secrets.write("first",value); secrets.write("second",value)
            val prefs = context.getSharedPreferences("workbench-native-secrets-v1",Context.MODE_PRIVATE)
            val first = prefs.getString("first",null)!!; val second = prefs.getString("second",null)!!
            assertNotEquals(value,first); assertNotEquals(first,second)
            assertFalse(first.contains(value)); assertEquals(value,secrets.read("first")); assertEquals(value,secrets.read("second"))
            // Copying ciphertext to another entry must fail its GCM AAD check and remove it.
            check(prefs.edit().putString("second",first).commit())
            assertEquals("",secrets.read("second")); assertFalse(prefs.contains("second"))
            assertEquals(value,secrets.read("first"))
            secrets.write("first",""); assertFalse(prefs.contains("first"))
            check(prefs.edit().putString("damaged","AAAA").commit())
            assertEquals("",secrets.read("damaged")); assertFalse(prefs.contains("damaged"))
        } finally { context.clear() }
    }
    @Test fun changingTrustClearsOnlyTheMatchingServiceCredential() = runBlocking {
        val context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val transport = AndroidWorkbenchTransport(context)
        try {
            val service = "wss://example.test/ws"
            val otherService = "wss://other.test/ws"
            val firstPin = "AB".repeat(32)
            transport.setFingerprint(service,firstPin)
            transport.saveCredential(service,"test-only-service-credential-0123456789")
            transport.saveCredential(otherService,"another-test-service-credential-0123456789")
            assertEquals(firstPin,transport.fingerprint(service))
            transport.setFingerprint(service,"CD".repeat(32))
            assertEquals("",transport.credential(service))
            assertEquals("another-test-service-credential-0123456789",transport.credential(otherService))
            transport.saveCredential(service,"replacement-service-credential-0123456789")
            transport.clearOrigin(service)
            assertEquals("",transport.credential(service))
            assertEquals("another-test-service-credential-0123456789",transport.credential(otherService))
        } finally { transport.close(); transport.awaitClosed(); context.clear() }
    }
}
