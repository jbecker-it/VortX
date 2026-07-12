package com.stremiox.android.engine

import android.content.Context
import android.util.Log
import com.stremiox.android.auth.AuthIdentityStore
import com.stremiox.android.data.AuthRepository
import com.stremiox.android.data.CatalogRepository
import com.stremiox.android.debrid.DebridKeys
import com.stremiox.android.debrid.DebridResolver
import com.stremiox.android.model.AuthState
import com.stremiox.android.model.Catalog
import com.stremiox.android.model.MediaType
import com.stremiox.android.model.MetaDetail
import com.stremiox.android.model.MetaItem
import com.stremiox.android.model.Playable
import com.stremiox.android.model.StreamGroup
import com.stremiox.android.model.StreamSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.time.Duration.Companion.seconds

/// The real engine implementation of the UI seams. Drop-in replacement for `PreviewCatalogRepository`
/// AND `PreviewAuthRepository`: it satisfies the SAME [CatalogRepository] (alias `StremioRepository`)
/// and [AuthRepository] contracts the Compose screens were built against, so wiring it in is a
/// one-line change at the injection site (see `VortXApplication`) with zero UI churn.
///
/// How it bridges the request/response gap: stremio-core is event-driven, not request/response. Each
/// catalog repository call (a) dispatches the matching Load action, (b) suspends until the engine
/// emits a `NewState` event naming the field it drives, then (c) pulls + parses that field's JSON. A
/// timeout guards against a field that never re-emits (e.g. the engine had it cached and ActionLoad
/// was a no-op), in which case we read whatever state is currently present. `signIn`/`signOut` follow
/// the same shape but dispatch a whole-model broadcast (see [EngineActions.authenticateLogin]) instead
/// of a single field's Load, and additionally race a `CoreEvent` sign-in error so a bad password
/// surfaces the engine's own message instead of just timing out.
///
/// Threading (hardened, S03): [StremioXCore.EventListener.onEvent] fires on a native worker thread
/// (a stremio-core tokio worker attached to the JVM, see `android_jni.rs`). [onEngineEvent] does the
/// minimum JSON parse there and publishes into two `MutableSharedFlow`s via `tryEmit`, which NEVER
/// suspends -- a slow/absent collector can never block the native callback, satisfying the engine's
/// "return promptly" contract on [StremioXCore.EventListener.onEvent]. Both flows use
/// `BufferOverflow.DROP_OLDEST`: if a burst of events outruns every collector (bounded buffer full),
/// we drop the STALEST entry, not the callback -- the next field pull always sees the latest state
/// regardless of which individual "it changed" notifications were conflated away. [authState] is
/// additionally re-published (off the native thread, via [engineScope]) so every subscriber gets a
/// live, conflated `StateFlow` rather than having to replay the shared-flow history themselves.
class EngineStremioRepository(
    context: Context,
    /// How long to wait for a field's NewState before falling back to the current state. The engine is
    /// local except for add-on HTTP, so a few seconds covers a cold add-on fan-out.
    private val loadTimeoutSeconds: Long = 12,
) : CatalogRepository, AuthRepository {

    private val appContext = context.applicationContext

    /// Native in-client debrid resolver: turns a raw-torrent infoHash into a DIRECT, playable HTTPS URL
    /// through the user's own debrid account (keys in EncryptedSharedPreferences). Built lazily so no
    /// key store is opened until a torrent is actually resolved; with no key configured it is a no-op
    /// and torrents keep today's behavior (a clear error the player layer surfaces).
    private val debridResolver by lazy { DebridResolver(DebridKeys(appContext)) }

    /// The Keystore-backed "who was last signed in" display cache (ANDROID-PLAN.md §0 invariant #5);
    /// the engine's own persisted `ctx.profile.auth` remains the actual source of truth, see
    /// [AuthIdentityStore]'s doc comment.
    private val identityStore by lazy { AuthIdentityStore(appContext) }

    /// Field names that changed in the most recent engine event. extraBufferCapacity + DROP_OLDEST
    /// keeps a burst of back-to-back events from ever suspending (blocking) the native callback thread
    /// that publishes them -- see the class doc.
    private val changedFields = MutableSharedFlow<Set<String>>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /// Sign-in failure messages, published only for `CoreEvent` `Error`s whose `source` is
    /// `UserAuthenticated` (see [EngineState.parseAuthErrorMessage]) so an unrelated background error
    /// (a library sync hiccup, say) can never masquerade as a sign-in failure.
    private val authErrors = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /// A small supervisor-scoped coroutine scope this repository owns for work that must outlive any
    /// single caller's coroutine (republishing [authState] off engine events). `SupervisorJob` so one
    /// failure (e.g. a malformed ctx JSON on a single event) can't cancel the whole scope.
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _authState = MutableStateFlow<AuthState>(AuthState.SignedOut)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    @Volatile
    private var started = false

    init {
        start()
    }

    /// Initialize the engine once. Idempotent; safe to call from multiple repositories (the native
    /// side is also idempotent). Storage goes to the durable filesDir, the HTTP cache to cacheDir.
    ///
    /// Fail-soft: if the native library failed to load or init throws (e.g. a missing/incompatible
    /// `libstremiox_core.so`), we log and leave [started] false. Every dispatch/getState below is then
    /// a no-op that yields the engine's `"null"` sentinel, so the parsers return empty state and the UI
    /// renders an empty (not crashed) screen. The boundary must never take down the whole app.
    @Synchronized
    private fun start() {
        if (started) return
        val storageDir = appContext.filesDir.absolutePath
        val cacheDir = appContext.cacheDir.absolutePath
        started = runCatching {
            StremioXCore.init(storageDir, cacheDir) { json -> onEngineEvent(json) }
        }.getOrElse { error ->
            Log.e(TAG, "stremio-core init failed; UI will render empty until the engine is available", error)
            false
        }
        // The engine hydrates `ctx.profile` (including a persisted sign-in) from its own storage
        // DURING init, before this call returns -- so a restored sign-in is visible immediately, no
        // extra dispatch/round-trip needed ("kill and relaunch restores state from engine
        // persistence", ANDROID-PLAN.md S03 DoD). Runs on whatever thread constructed this repository
        // (the engine's own lock, not the native callback thread), matching every other getState call.
        if (started) refreshAuthState()
    }

    /// Decode a `RuntimeEvent`: publish changed field names on a NewState, or a sign-in failure
    /// message on a matching CoreEvent (see [EngineState.parseAuthErrorMessage]). Called on a native
    /// worker thread (see the class doc) -- every branch here is a cheap parse + a non-suspending
    /// `tryEmit`, so this always returns promptly regardless of collector speed.
    private fun onEngineEvent(json: ByteArray) {
        val event = runCatching { JSONObject(String(json, Charsets.UTF_8)) }.getOrNull() ?: return
        when (event.optString("name")) {
            "NewState" -> {
                val args = event.optJSONArray("args") ?: return
                val fields = buildSet {
                    for (i in 0 until args.length()) {
                        val field = args.optString(i)
                        if (field.isNotEmpty()) add(field)
                    }
                }
                if (fields.isEmpty()) return
                changedFields.tryEmit(fields)
                // ctx changed (a sign-in, sign-out, or a persisted-profile hydration racing this
                // listener registration): refresh the published AuthState off this thread so
                // subscribers see it without polling. Cheap to check on every event; ctx changes are
                // infrequent (account actions), not per-frame.
                if (EngineActions.FIELD_CTX in fields) engineScope.launch { refreshAuthState() }
            }
            "CoreEvent" -> {
                EngineState.parseAuthErrorMessage(json.toString(Charsets.UTF_8))?.let { authErrors.tryEmit(it) }
            }
        }
    }

    /// Pull `ctx`, parse it into [AuthState], publish it, and keep [identityStore] in sync (a display
    /// cache only -- see its doc comment; the engine's own `ctx.profile.auth` stays authoritative).
    private fun refreshAuthState() {
        val state = EngineState.parseAuthState(StremioXCore.getState(EngineActions.ctxField()))
        _authState.value = state
        when (state) {
            is AuthState.SignedIn -> identityStore.rememberSignedIn(state.email)
            AuthState.SignedOut -> identityStore.forget()
        }
    }

    /// Dispatch [actionJson], then await a NewState naming [field] (up to [loadTimeoutSeconds]), then
    /// return the field's current JSON. If no event arrives in time, returns the current state anyway
    /// (covers the cached no-op-load case). Returns [field]'s JSON, never null ("null" on error).
    private suspend fun loadField(field: String, actionJson: String): String {
        val awaited = withTimeoutOrNull(loadTimeoutSeconds.seconds) {
            // Subscribe first conceptually: dispatch then await. A buffered SharedFlow plus the small
            // network latency of add-on calls means the emission lands after our await begins.
            StremioXCore.dispatch(actionJson)
            changedFields.first { field in it }
        }
        // Whether the event arrived or we timed out, pull the latest state for the field.
        if (awaited == null) {
            // No event: the engine likely had it cached (ActionLoad was a no-op). Current state is best.
        }
        return StremioXCore.getState("\"$field\"")
    }

    override suspend fun home(): Result<List<Catalog>> = runCatching {
        StremioXCore.dispatch(EngineActions.loadBoard())
        val state = loadField(EngineActions.FIELD_BOARD, EngineActions.loadBoardRange(DEFAULT_BOARD_ROWS))
        // Real rail titles ("<add-on> · <catalog>"), not the bare `path.id`/`path.type` fallback: look
        // up every row against the installed add-ons' own manifests (ctx is already hydrated by now,
        // see `start()`/`refreshAuthState`).
        val titleMap = EngineState.parseAddonCatalogTitles(StremioXCore.getState(EngineActions.ctxField()))
        val boardRows = EngineState.parseCatalogs(state, titleMap)
        // Prepend Continue Watching, the leading Home rail on iOS/tvOS. It is DERIVED state the engine
        // hydrated from the library at construction (it emits no NewState of its own, mirroring Apple
        // CoreBridge.seedInitialState), so we read the field straight rather than dispatching a load.
        // The board load above has already pumped the event loop, so the field is populated by now.
        // Fail-soft: an empty CW list simply yields no row, never an error, so Home still renders the
        // add-on rails on a fresh (never-watched) account.
        val continueWatching = runCatching {
            EngineState.parseContinueWatching(StremioXCore.getState(EngineActions.continueWatchingPreviewField()))
        }.getOrDefault(emptyList())
        if (continueWatching.isEmpty()) {
            boardRows
        } else {
            // id = "continue" is the contract HomeScreen keys its editorial eyebrow off of.
            listOf(Catalog(id = "continue", title = "Continue Watching", items = continueWatching)) + boardRows
        }
    }

    override suspend fun discover(type: MediaType): Result<List<Catalog>> = runCatching {
        val state = loadField(EngineActions.FIELD_DISCOVER, EngineActions.loadDiscover())
        // Discover is one selectable rail in the engine (a CatalogWithFilters: the selected catalog's
        // flat pages, not the board's list-of-rails). parseCatalogWithFilters decodes that single rail
        // into a one-row catalog list so the UI's row-based Discover screen renders without special-
        // casing. Fail-soft: any miss (engine unavailable, still-loading, empty) yields an empty list.
        val titleMap = EngineState.parseAddonCatalogTitles(StremioXCore.getState(EngineActions.ctxField()))
        EngineState.parseCatalogWithFilters(state, titleMap)
    }

    override suspend fun library(): Result<List<MetaItem>> = runCatching {
        val state = loadField(EngineActions.FIELD_LIBRARY, EngineActions.loadLibrary())
        EngineState.parseLibrary(state)
    }

    override suspend fun search(query: String): Result<List<MetaItem>> = runCatching {
        if (query.isBlank()) return@runCatching emptyList()
        StremioXCore.dispatch(EngineActions.searchLoad(query))
        val state = loadField(EngineActions.FIELD_SEARCH, EngineActions.searchRange(DEFAULT_SEARCH_ROWS))
        // search is a CatalogsWithExtra (rails); flatten the rails to a flat result list for the UI.
        EngineState.parseCatalogs(state).flatMap { it.items }
    }

    override suspend fun meta(type: MediaType, id: String): Result<MetaDetail> = runCatching {
        val state = loadField(EngineActions.FIELD_META_DETAILS, EngineActions.loadMeta(type.id, id))
        EngineState.parseMetaDetail(state)
            ?: throw IllegalStateException("meta_details not ready for $id")
    }

    override suspend fun streams(type: MediaType, id: String, episodeId: String?): Result<List<StreamGroup>> = runCatching {
        // Meta + a guessed stream were already requested by meta(); re-pull meta_details for its
        // stream groups. If meta() was not called first, this Load brings both in.
        //
        // For a series with a chosen [episodeId], pass a streamPath so the engine fetches THAT episode's
        // streams (the engine stream resource type is the meta type, its id is the episode's video id);
        // otherwise (movie, or no episode chosen yet) leave the streamPath null and take the guessed set.
        val action = if (episodeId != null) {
            EngineActions.loadMeta(type.id, id, streamType = type.id, streamId = episodeId)
        } else {
            EngineActions.loadMeta(type.id, id)
        }
        val state = loadField(EngineActions.FIELD_META_DETAILS, action)
        EngineState.parseStreamGroups(state)
    }

    override suspend fun resolve(source: StreamSource): Result<Playable> = runCatching {
        // A stream id encodes its handle (see EngineState.parseStream: id = handle#name#desc, handle is
        // url/externalUrl/infoHash). Direct URLs are playable as-is. A raw torrent (handle = infoHash)
        // resolves through the user's own debrid account when a key is configured (native in-client
        // debrid, the Android port of the Apple DebridResolver); without a key it still needs the
        // in-process streaming server (nodejs-mobile, not yet wired), so it surfaces a clear error the
        // player layer can show instead of failing opaquely.
        val handle = source.id.substringBefore('#')
        if (!source.isTorrent && (handle.startsWith("http://") || handle.startsWith("https://"))) {
            Playable(url = handle, title = source.title, viaStreamingServer = false)
        } else if (source.isTorrent) {
            // Raw torrent: the handle IS the infoHash (see EngineState.parseStream: for a torrent
            // url == null, so the id-handle is the infoHash). If the user has a debrid key configured,
            // resolve it to a DIRECT, cached-instant HTTPS link through their own account (the Android
            // port of the Apple DebridResolver). The resolved URL is a plain direct stream, NOT a
            // torrent, so it plays without the streaming-server bridge (viaStreamingServer = false).
            //
            // Fail-soft: DebridResolver.resolve returns null on ANY failure (no key, not actually
            // cached, no playable file, provider/network error). With no key it never opens the key
            // store. On null we surface the SAME clear error as before so the player layer shows a real
            // message rather than failing opaquely, and torrents keep today's behavior when no debrid
            // is configured.
            val resolved = debridResolver.resolve(infoHash = handle)
            if (resolved != null) {
                Playable(url = resolved, title = source.title, viaStreamingServer = false, isTorrent = false)
            } else {
                throw UnsupportedOperationException(
                    "Torrent playback needs a debrid key or the streaming-server bridge (not yet wired on Android).",
                )
            }
        } else {
            throw UnsupportedOperationException(
                "This source type is not playable on Android yet.",
            )
        }
    }

    // ---- AuthRepository ----

    override suspend fun signIn(email: String, password: String): Result<Unit> = runCatching {
        if (email.isBlank() || password.isBlank()) {
            throw IllegalArgumentException("Enter your email and password.")
        }
        // Race a `ctx` NewState (success: profile.auth now set) against a CoreEvent sign-in error (bad
        // password, no network, ...) by merging both into one flow and taking whichever lands first --
        // mirrors how Apple `StremioAccount.signIn` surfaces `res.error?.message` instead of a generic
        // failure. The await is launched before the dispatch (and login is a full network round-trip,
        // so the subscription is up long before the engine can answer); if an event is somehow still
        // missed, the post-timeout ctx re-read below resolves the true outcome -- the race exists to
        // make the common path fast and the error message specific, not for correctness.
        val ctxChanged: Flow<AuthWait> = changedFields.filter { EngineActions.FIELD_CTX in it }.map { AuthWait.CtxChanged }
        val failed: Flow<AuthWait> = authErrors.map { AuthWait.Failed(it) }
        val awaitOutcome = withTimeoutOrNull(loadTimeoutSeconds.seconds) {
            val outcome = async { merge(ctxChanged, failed).first() }
            StremioXCore.dispatch(EngineActions.authenticateLogin(email, password))
            outcome.await()
        }
        if (awaitOutcome is AuthWait.Failed) throw IllegalStateException(awaitOutcome.message)
        // Whether we won the race on ctx, timed out (a dropped/coalesced event -- current state is
        // best, the same fallback [loadField] uses), the actual signed-in-ness is the real check.
        refreshAuthState()
        if (_authState.value !is AuthState.SignedIn) {
            throw IllegalStateException("Sign-in failed. Check your connection and try again.")
        }
    }

    /// The two outcomes [signIn] races: a `ctx` change (assume success; the real check afterward is
    /// `authState is SignedIn`) or an explicit failure message from the engine.
    private sealed interface AuthWait {
        data object CtxChanged : AuthWait
        data class Failed(val message: String) : AuthWait
    }

    override suspend fun signOut() {
        // Broadcast dispatch (field = null): mirrors Apple's plain Logout, an explicit user sign-out.
        // Fail-soft by design -- if the engine is unavailable the dispatch is a no-op, but we still
        // clear the LOCAL published state + identity cache so the UI never shows a stale signed-in
        // account it can't actually act on.
        runCatching { StremioXCore.dispatch(EngineActions.logout()) }
        _authState.value = AuthState.SignedOut
        identityStore.forget()
    }

    private companion object {
        // Match CoreBridge's initial board fetch and search range so behavior tracks the reference app.
        const val DEFAULT_BOARD_ROWS = 12
        const val DEFAULT_SEARCH_ROWS = 30
        const val TAG = "StremioXEngine"
    }
}
