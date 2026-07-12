package com.stremiox.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stremiox.android.data.CatalogRepository
import com.stremiox.android.model.Catalog
import com.stremiox.android.model.DiscoverResult
import com.stremiox.android.model.LibraryResult
import com.stremiox.android.model.MetaItem
import com.stremiox.android.search.SearchHistoryStore
import com.stremiox.android.ui.UiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/// Maps a repository [Result] to a [UiState], turning a thrown/failed add-on call into a visible
/// error instead of an empty screen. Shared by every catalog ViewModel.
private fun <T> Result<T>.toUiState(): UiState<T> = fold(
    onSuccess = { UiState.Success(it) },
    onFailure = { UiState.Error(it.message ?: "Something went wrong loading your add-ons.") },
)

/// Home: Continue Watching + add-on catalog rails. Collects the repository's CONTINUOUS
/// [CatalogRepository.homeUpdates] stream for the ViewModel's lifetime (not a one-shot load): the
/// engine settles the board add-on by add-on, so rails must appear incrementally as each answers,
/// and a sign-in/sign-out mid-session must swap the rail set live -- both of which a single
/// `home()` call can never do (the S03 device round showed exactly that: rails empty forever).
///
/// State contract: Loading (shimmer) until the FIRST non-empty rail set arrives; then Success,
/// updated in place on every later change. If nothing arrives within [EMPTY_TIMEOUT_MS], Error with
/// Retry -- never a silent black screen -- but collection continues, so data landing late still
/// replaces the error.
class HomeViewModel(private val repo: CatalogRepository) : ViewModel() {
    private val _state = MutableStateFlow<UiState<List<Catalog>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Catalog>>> = _state.asStateFlow()

    private var collectJob: Job? = null

    init {
        load()
    }

    fun load() {
        collectJob?.cancel()
        _state.value = UiState.Loading
        collectJob = viewModelScope.launch {
            // Watchdog: an engine that produces no rails at all (no network AND no cache) must show
            // the composed error card, never shimmer/black forever. A child job, so cancel-on-reload
            // covers it too; it no-ops once real data has landed.
            launch {
                delay(EMPTY_TIMEOUT_MS)
                if (_state.value is UiState.Loading) {
                    _state.value = UiState.Error("Couldn't load your catalogs. Check your connection and try again.")
                }
            }
            repo.homeUpdates()
                .catch { error ->
                    _state.value = UiState.Error(error.message ?: "Something went wrong loading your add-ons.")
                }
                .collect { rows ->
                    // Empty emissions are the stream's "nothing settled yet" heartbeat -- keep the
                    // shimmer (or the previous Success) rather than rendering an empty Home.
                    if (rows.isNotEmpty()) _state.value = UiState.Success(rows)
                }
        }
    }

    private companion object {
        const val EMPTY_TIMEOUT_MS = 15_000L
    }
}

/// Discover: the engine-driven type/catalog/genre pivot (S04). REPLACES the old static-[MediaType]
/// chip switch: that switch dispatched the SAME `args: null` Load regardless of which chip was tapped
/// (see [com.stremiox.android.engine.EngineActions.loadDiscover]'s old doc), so every type/catalog
/// selection rendered the exact same rail -- the "Discover chips are inert" bug this session fixes.
/// Every selection now re-dispatches the chip's own `request` JSON verbatim (never reconstructed), and
/// filters + items come back from a SINGLE engine round-trip (see
/// [com.stremiox.android.engine.EngineStremioRepository.discoverResultFrom]).
class DiscoverViewModel(private val repo: CatalogRepository) : ViewModel() {
    private val _state = MutableStateFlow<UiState<DiscoverResult>>(UiState.Loading)
    val state: StateFlow<UiState<DiscoverResult>> = _state.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    private var selectJob: Job? = null

    init {
        select(null)
    }

    /// Pivot to a specific type/catalog/genre. [requestJson] is null for the engine's own default
    /// (first load, and the entry point for the whole screen), or a chip's `requestJson` from the
    /// current [DiscoverResult.filters].
    fun select(requestJson: String?) {
        selectJob?.cancel()
        _state.value = UiState.Loading
        selectJob = viewModelScope.launch {
            _state.value = repo.discover(requestJson).toUiState()
        }
    }

    fun retry() = select(null)

    /// "Load more" (DESIGN-SYSTEM.md §4 "Discover / Search": "'Load more' (per-catalog skip)"). The
    /// engine APPENDS the next page to the already-loaded catalog, so the returned items already
    /// contain everything loaded so far -- no manual merge needed here.
    fun loadMore() {
        val filters = (_state.value as? UiState.Success)?.data?.filters ?: return
        if (!filters.hasNextPage || _loadingMore.value) return
        viewModelScope.launch {
            _loadingMore.value = true
            repo.discoverNextPage().onSuccess { _state.value = UiState.Success(it) }
            _loadingMore.value = false
        }
    }
}

/// Library: the engine-driven type/sort pivot + add/remove (S04). Re-loads on every filter switch and
/// after a library mutation, keeping the currently applied [requestJson] so a remove doesn't silently
/// reset the user's filter/sort choice.
class LibraryViewModel(private val repo: CatalogRepository) : ViewModel() {
    private val _state = MutableStateFlow<UiState<LibraryResult>>(UiState.Loading)
    val state: StateFlow<UiState<LibraryResult>> = _state.asStateFlow()

    private var currentRequestJson: String? = null
    private var loadJob: Job? = null

    init {
        load(null)
    }

    fun load(requestJson: String?) {
        currentRequestJson = requestJson
        loadJob?.cancel()
        _state.value = UiState.Loading
        loadJob = viewModelScope.launch {
            _state.value = repo.library(requestJson).toUiState()
        }
    }

    fun retry() = load(currentRequestJson)

    /// Remove a title from the Library (the grid's per-poster "x" control), then re-load so the grid
    /// and the remaining filter/sort selection stay in sync with the engine.
    fun remove(id: String) {
        viewModelScope.launch {
            repo.removeFromLibrary(id)
            _state.value = repo.library(currentRequestJson).toUiState()
        }
    }
}

/// Search: debounced full-text query across every installed add-on. An empty query is a calm idle
/// state, never an error. [history] surfaces recent searches (DESIGN-SYSTEM.md §4 "Discover / Search":
/// "Recent searches as chips when empty"), ported from Apple `SearchHistoryStore` -- see
/// [SearchHistoryStore]'s doc for the plain-prefs rationale.
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(private val repo: CatalogRepository, private val historyStore: SearchHistoryStore) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _history = MutableStateFlow(historyStore.load())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    val state: StateFlow<UiState<List<MetaItem>>> = _query
        .debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MS }
        .distinctUntilChanged()
        .flatMapLatest { q ->
            flow {
                if (q.isNotBlank()) emit(UiState.Loading)
                emit(repo.search(q).toUiState())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Success(emptyList()))

    fun onQueryChange(value: String) {
        _query.value = value
    }

    /// Record the CURRENT query in history (mirrors Apple: recorded when a result is actually opened,
    /// not on every keystroke) and refresh the published list. Called by the screen's `onItem`.
    fun recordHistory() {
        val q = _query.value
        historyStore.add(q)
        _history.value = historyStore.load()
    }

    fun clearHistory() {
        historyStore.clear()
        _history.value = emptyList()
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 350L
    }
}
