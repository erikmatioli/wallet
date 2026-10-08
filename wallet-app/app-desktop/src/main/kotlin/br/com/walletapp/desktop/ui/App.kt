package br.com.walletapp.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import br.com.walletapp.desktop.data.AppApiClient
import br.com.walletapp.desktop.data.SessionStore
import br.com.walletapp.desktop.ui.account.HomeScreen
import br.com.walletapp.desktop.ui.account.HomeViewModel
import br.com.walletapp.desktop.ui.account.StatementScreen
import br.com.walletapp.desktop.ui.account.StatementViewModel
import br.com.walletapp.desktop.ui.auth.LoginScreen
import br.com.walletapp.desktop.ui.auth.LoginViewModel
import br.com.walletapp.desktop.ui.auth.SignupScreen
import br.com.walletapp.desktop.ui.auth.SignupViewModel
import br.com.walletapp.desktop.ui.payments.PixScreen
import br.com.walletapp.desktop.ui.payments.PixViewModel
import br.com.walletapp.desktop.ui.payments.TransferScreen
import br.com.walletapp.desktop.ui.payments.TransferViewModel
import br.com.walletapp.desktop.ui.schedules.NewScheduleScreen
import br.com.walletapp.desktop.ui.schedules.NewScheduleViewModel
import br.com.walletapp.desktop.ui.schedules.SchedulesScreen
import br.com.walletapp.desktop.ui.schedules.SchedulesViewModel

/**
 * The whole app: the session decides between the logged-out and logged-in worlds, and the navigator's
 * top screen decides what is drawn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(api: AppApiClient, session: SessionStore) {
    val scope = rememberCoroutineScope()
    val navigator = remember { Navigator() }
    val stack by navigator.stack.collectAsState()
    val sessionState by session.state.collectAsState()

    // Session started -> home, and the inactivity watch starts; ended (expired, inactive, logged out) ->
    // login. Runs again whenever the session changes, which also cancels the previous watch.
    val inactivity = remember { InactivityMonitor() }
    LaunchedEffect(sessionState) {
        when (sessionState) {
            is SessionStore.State.LoggedIn -> {
                navigator.reset(Screen.Home)
                watchInactivity(inactivity, session)
            }
            else -> navigator.reset(Screen.Login)
        }
    }

    // ViewModels per screen. remember(key) builds a new one when the key changes: a new login gets fresh
    // ones, and nothing of the previous customer survives in memory.
    val loginId = (sessionState as? SessionStore.State.LoggedIn)?.session?.token
    val login = remember { LoginViewModel(api, session, scope) }
    val signup = remember { SignupViewModel(api, session, scope) }
    val home = remember(loginId) { HomeViewModel(api, scope) }
    val statement = remember(loginId) { StatementViewModel(api, scope) }
    val transfer = remember(loginId) { TransferViewModel(api, scope) }
    val pix = remember(loginId) { PixViewModel(api, scope) }
    val schedules = remember(loginId) { SchedulesViewModel(api, scope) }
    val newSchedule = remember(loginId) { NewScheduleViewModel(api, scope) }

    val screen = stack.last()
    Scaffold(
        modifier = Modifier.reportsActivityTo(inactivity),
        topBar = {
            if (sessionState is SessionStore.State.LoggedIn) {
                TopAppBar(
                    title = { Text(title(screen)) },
                    navigationIcon = { if (stack.size > 1) TextButton(onClick = navigator::back) { Text("← Voltar") } },
                    actions = { TextButton(onClick = session::logout) { Text("Sair") } },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // Exhaustive over the sealed Screen: a new screen does not compile until it is drawn here.
            when (screen) {
                Screen.Login -> LoginScreen(login, notice = (sessionState as? SessionStore.State.Ended)?.reason,
                    onSignup = { navigator.push(Screen.Signup) })
                Screen.Signup -> SignupScreen(signup, onBack = navigator::back)
                Screen.Home -> HomeScreen(home, onStatement = { statement.refresh(); navigator.push(Screen.Statement) },
                    onTransfer = { transfer.reset(); navigator.push(Screen.Transfer) },
                    onPix = { pix.reset(); navigator.push(Screen.Pix) },
                    onSchedules = { navigator.push(Screen.Schedules) })
                Screen.Statement -> StatementScreen(statement)
                // Done -> back to a fresh home (its LaunchedEffect reloads balance and entries).
                Screen.Transfer -> TransferScreen(transfer, onFinish = { navigator.reset(Screen.Home) })
                Screen.Pix -> PixScreen(pix, onFinish = { navigator.reset(Screen.Home) })
                Screen.Schedules -> SchedulesScreen(schedules, onNew = { newSchedule.reset(); navigator.push(Screen.NewSchedule) })
                // Done -> back to the list, which reloads and shows the new schedule.
                Screen.NewSchedule -> NewScheduleScreen(newSchedule, onFinish = navigator::back)
            }
        }
    }
}

/**
 * Every mouse and keyboard event anywhere in the window counts as activity. The pointer events are only
 * watched on their way down (Initial pass) and never consumed, so buttons and fields work as before.
 */
private fun Modifier.reportsActivityTo(monitor: InactivityMonitor): Modifier = this
    .pointerInput(monitor) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial)
                monitor.touch()
            }
        }
    }
    .onPreviewKeyEvent {
        monitor.touch()
        false // not handled: the key still reaches the focused field
    }

private fun title(screen: Screen) = when (screen) {
    Screen.Home -> "Início"
    Screen.Statement -> "Extrato"
    Screen.Transfer -> "Transferir"
    Screen.Pix -> "Pix"
    Screen.Schedules -> "Agendamentos"
    Screen.NewSchedule -> "Novo agendamento"
    Screen.Login, Screen.Signup -> ""
}
