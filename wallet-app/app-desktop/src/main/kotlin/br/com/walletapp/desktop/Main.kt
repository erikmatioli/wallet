package br.com.walletapp.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import br.com.walletapp.desktop.data.AppApiClient
import br.com.walletapp.desktop.data.SessionStore
import br.com.walletapp.desktop.ui.App

/** Where app-api is. `-Dwallet.api.url=...` or WALLET_APP_API_URL; the local Docker port by default. */
private val apiUrl = System.getProperty("wallet.api.url") ?: System.getenv("WALLET_APP_API_URL") ?: "http://localhost:8083/"

/**
 * `application { }` owns the app's lifetime; `Window` is one OS window whose content is Compose. The
 * session and the API client live as long as the app: closing it forgets the session.
 */
fun main() = application {
    val session = remember { SessionStore() }
    val api = remember { AppApiClient(apiUrl, session) }
    // Which fintech's app this is: each app-api serves one tenant, and the title says which.
    var title by remember { mutableStateOf("Wallet") }
    LaunchedEffect(Unit) { runCatching { api.info() }.onSuccess { title = "Wallet · ${it.tenant}" } }

    Window(onCloseRequest = ::exitApplication, title = title, state = rememberWindowState(width = 900.dp, height = 680.dp)) {
        MaterialTheme {
            App(api, session)
        }
    }
}
