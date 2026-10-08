package br.com.walletapp.desktop.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Every screen of the app (ADR-001, decision 3). Sealed: the app's `when` over the current screen must
 * draw every one, so a new screen cannot be forgotten.
 */
sealed interface Screen {
    data object Login : Screen
    data object Signup : Screen
    data object Home : Screen
    data object Statement : Screen
    data object Transfer : Screen
    data object Pix : Screen
    data object Schedules : Screen
    data object NewSchedule : Screen
}

/**
 * Navigation as plain state: a stack of screens. The top one is shown; [push] goes forward, [back]
 * returns, [reset] starts over (login, logout). No navigation library - the whole mechanism is here.
 */
class Navigator(start: Screen = Screen.Login) {

    private val _stack = MutableStateFlow(listOf(start))
    val stack: StateFlow<List<Screen>> = _stack.asStateFlow()

    val current: Screen get() = _stack.value.last()
    val canGoBack: Boolean get() = _stack.value.size > 1

    fun push(screen: Screen) {
        _stack.value = _stack.value + screen
    }

    fun back() {
        if (canGoBack) _stack.value = _stack.value.dropLast(1)
    }

    fun reset(screen: Screen) {
        _stack.value = listOf(screen)
    }
}
