package dev.ccdesk.nativeapp

import android.app.Application
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import dev.ccdesk.nativeapp.core.WorkbenchRepository
import dev.ccdesk.nativeapp.ui.WorkbenchApp
import kotlinx.coroutines.launch

class WorkbenchViewModel(application: Application) : AndroidViewModel(application) {
    val repository = WorkbenchRepository(application)
    var requestedRoute by mutableStateOf<String?>(null)
    init { viewModelScope.launch { repository.start() } }
    override fun onCleared() { repository.dispose() }
}

class MainActivity : ComponentActivity() {
    private val model: WorkbenchViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        routeIntent(intent)
        setContent { Box { WorkbenchApp(model.repository, model.requestedRoute) { model.requestedRoute = null }; LaunchOverlay() } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); routeIntent(intent) }
    private fun routeIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW || uri.scheme != "ccdesk") return
        val route = (uri.host.orEmpty() + uri.path.orEmpty()).trim('/')
        if (route in setOf("", "settings", "connection", "usage", "access", "notifications", "help", "chats")) model.requestedRoute = route
    }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); enableEdgeToEdge() }
    override fun onStart() { super.onStart(); model.repository.foreground(true) }
    override fun onStop() { model.repository.foreground(false); super.onStop() }
    override fun onUserInteraction() { super.onUserInteraction(); model.repository.activity() }
}
