package dev.ccdesk.nativeapp.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ccdesk.nativeapp.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelCatalogStartupTest {
    @get:Rule val compose = createComposeRule()

    @Test fun connectionFetchesRealModelsBeforeOpeningPickerAndDraftReusesThem() {
        val fixture = FixtureTransport()
        val transport = object : WorkbenchTransport by fixture {
            override suspend fun socket(endpoint: String, observer: SocketObserver): SocketConnection {
                val connection = fixture.socket(endpoint, observer)
                return object : SocketConnection by connection {
                    override fun send(text: String): Boolean {
                        val request = JSONObject(text)
                        if (request.optString("type") == "session.status") {
                            fixture.frame(json("type" to "session.event", "event" to json(
                                "sessionId" to request.getString("sessionId"), "runId" to "catalog", "sequence" to 1,
                                "createdAt" to "2026-11-15T00:00:00Z", "payload" to json("type" to "native.capabilities",
                                    "models" to JSONArray().put(json("value" to "provider-real", "displayName" to "Provider real model"))))))
                        }
                        return connection.send(text)
                    }
                }
            }
        }
        val repository = WorkbenchRepository(transport)
        try {
            runBlocking { repository.start() }
            compose.waitUntil(5_000) { repository.state.value.modelCatalogs["server"]?.models?.isNotEmpty() == true }
            assertEquals(1, fixture.sentOf("session.status").size)
            assertFalse(fixture.sentOf("session.status").single().getBoolean("quota"))
            assertNull(repository.state.value.selectedId)
            assertTrue(fixture.sentOf("server.session.create").isEmpty())
            assertTrue(fixture.sentOf("session.subscribe").isEmpty())
            compose.setContent { WorkbenchApp(repository) }
            compose.onNode(hasContentDescription("选择模型", substring = true)).performClick()
            compose.onNodeWithText("Provider real model").assertIsDisplayed()
            for (alias in listOf("Opus", "Sonnet", "Haiku")) compose.onNodeWithText(alias).assertDoesNotExist()
            assertEquals(1, fixture.sentOf("session.status").size)
        } finally { compose.runOnIdle { repository.dispose() } }
    }
}
