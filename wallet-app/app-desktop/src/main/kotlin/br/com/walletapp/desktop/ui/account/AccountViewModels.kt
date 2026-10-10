package br.com.walletapp.desktop.ui.account

import br.com.walletapp.contract.Me
import br.com.walletapp.contract.StatementEntry
import br.com.walletapp.desktop.data.AppApiClient
import br.com.walletapp.desktop.ui.auth.message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import br.com.walletapp.contract.BalanceHistory
import br.com.walletapp.desktop.ui.Format
import java.time.LocalDate

/** The home screen: the account and its last few entries. */
class HomeViewModel(private val api: AppApiClient, private val scope: CoroutineScope) {

    sealed interface State {
        data object Loading : State
        /** [history] is null when it could not be loaded: the rest of the home screen still shows. */
        data class Loaded(val me: Me, val recent: List<StatementEntry>, val history: BalanceHistory? = null) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    fun load(): Job {
        _state.value = State.Loading
        return scope.launch {
            _state.value = try {
                // Both calls at once: async starts each in its own coroutine, await waits for each result.
                // coroutineScope matters: without it, a failing async cancels the whole window's scope
                // instead of reaching this catch; with it, the failure comes back here as an exception.
                coroutineScope {
                    val me = async { api.me() }
                    val recent = async { api.statement(limit = 5) }
                    // The evolution chart is a nice-to-have: its failure must not fail the screen.
                    val history = async { runCatching { api.balanceHistory() }.getOrNull() }
                    State.Loaded(me.await(), recent.await().entries, history.await())
                }
            } catch (e: Exception) {
                State.Failed(message(e))
            }
        }
    }
}

/**
 * One day of the statement. The statement comes newest first, so the day's first line is its last entry and
 * [closingCents] is the balance at the end of the day (wallet-core's ADR-012) - or the balance so far, today.
 */
data class StatementDay(val day: LocalDate, val closingCents: Long, val entries: List<StatementEntry>)

/** The statement, page by page (newest first), and the entry whose detail is open. */
class StatementViewModel(private val api: AppApiClient, private val scope: CoroutineScope) {

    data class State(
        val entries: List<StatementEntry> = emptyList(),
        val nextBefore: Long? = null,
        val loading: Boolean = false,
        val loadedOnce: Boolean = false,
        val error: String? = null,
        val selected: StatementEntry? = null,
    ) {
        val hasMore get() = nextBefore != null

        /** The entries grouped by day in Brasília, newest first, each with its closing balance. */
        val days: List<StatementDay> get() = entries.groupBy { Format.day(it.occurredAt) }
            .map { (day, list) -> StatementDay(day, list.first().balanceAfterCents, list) }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** The first page, or the next one after what is loaded. */
    fun loadMore(): Job? {
        val s = _state.value
        if (s.loading || (s.loadedOnce && !s.hasMore)) return null
        _state.update { it.copy(loading = true, error = null) }
        return scope.launch {
            try {
                val page = api.statement(before = s.nextBefore, limit = PAGE)
                _state.update {
                    it.copy(entries = it.entries + page.entries, nextBefore = page.nextBefore, loading = false, loadedOnce = true)
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = message(e)) }
            }
        }
    }

    /** From the newest again: whatever was paid since the last visit shows up. */
    fun refresh() {
        _state.value = State()
    }

    /** The detail shows what the page already brought: no new request (same as the console). */
    fun open(entry: StatementEntry) = _state.update { it.copy(selected = entry) }

    fun close() = _state.update { it.copy(selected = null) }

    private companion object {
        const val PAGE = 20
    }
}
