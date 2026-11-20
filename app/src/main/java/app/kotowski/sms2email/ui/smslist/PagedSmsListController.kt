package app.kotowski.sms2email.ui.smslist

import app.kotowski.sms2email.AppConfig
import app.kotowski.sms2email.data.database.SmsListScope
import app.kotowski.sms2email.data.database.SmsRepository
import app.kotowski.sms2email.data.database.SmsSearch
import app.kotowski.sms2email.data.database.SmsUiModel
import app.kotowski.sms2email.data.prefs.EncryptedPrefs
import app.kotowski.sms2email.system.ContactChangeObserver
import app.kotowski.sms2email.system.ContactResolver
import app.kotowski.sms2email.system.SimChangeObserver
import app.kotowski.sms2email.system.SimNameResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PagedSmsListState(
    val smsList: List<SmsUiModel> = emptyList(),
    val isLoading: Boolean = true,
    val simNames: Map<Int, String> = emptyMap(),
    val simSlots: Map<Int, Int> = emptyMap(),
    val contactNames: Map<String, String> = emptyMap(),
    // The exact unfiltered total (the loaded list is a window, not the total): the top
    // bar title shows it, unfiltered by the active search.
    val smsCount: Int = 0,
    val hasMore: Boolean = false,
)

/**
 * The paged-list pipeline shared by the History and Queue view models.
 *
 * A live, search-filtered, growing window of rows for one list scope, plus the
 * unfiltered count. The owning view model keeps its distinct state and composes
 * [state] in.
 */
internal class PagedSmsListController(
    private val scope: CoroutineScope,
    private val listScope: SmsListScope,
    private val repository: SmsRepository,
    private val listEnricher: SmsListEnricher,
    private val prefs: EncryptedPrefs,
    private val contactResolver: ContactResolver,
    private val simNameResolver: SimNameResolver,
    private val simChangeObserver: SimChangeObserver,
    private val contactChangeObserver: ContactChangeObserver,
) {
    private val _state = MutableStateFlow(PagedSmsListState())

    val state: StateFlow<PagedSmsListState> = _state.asStateFlow()

    private val pageWindow = SmsPageWindow()

    // The debounced search query and the page-grow trigger: a non-empty search change
    // re-runs the window query debounced (a keystroke burst is one query), a clear
    // (non-empty to empty) re-runs it immediately (the unfiltered window must not wait
    // out the debounce — a stale empty window would flash the screen's empty state),
    // and a pageBump re-runs it immediately with the grown limit (loadMore must feel
    // instant).
    private val searchQuery = MutableStateFlow("")

    private val pageBump = MutableStateFlow(0)

    private var windowJob: Job? = null

    private var countJob: Job? = null

    private var simJob: Job? = null

    private var contactJob: Job? = null

    // The last loaded window: a SIM change re-enriches only its SIM maps (no DB re-query),
    // so a rename/swap updates the badges without re-running the window or contact scans.
    private var currentWindow: List<SmsUiModel> = emptyList()

    // The facet inputs (contacts, SIM slots) are resolved per distinct query only:
    // a page grow re-runs the window query without re-hitting the contacts provider.
    private var cachedSearchQuery: String? = null

    private var cachedSearch: SmsSearch = SmsSearch.none()

    // Starts the window, count and refresh collections (safe to call twice: the jobs are
    // cancelled and restarted).
    fun start() {
        startWindowCollection()
        startCountCollection()
        startSimRefresh()
        startContactRefresh()
    }

    // Live SIM badges: on a subscription change (or the 60 s fallback tick) re-resolve the
    // names and tray slots for the already-loaded window only. It does not re-query the DB
    // or re-scan contacts, and it is a no-op until the first window lands.
    private fun startSimRefresh() {
        simJob?.cancel()
        simJob =
            scope.launch {
                simChangeObserver.simChanges.collect {
                    val window = currentWindow
                    if (window.isEmpty()) return@collect
                    val simNames = withContext(Dispatchers.IO) { listEnricher.buildSimNames(window) }
                    val simSlots = withContext(Dispatchers.IO) { listEnricher.buildSimSlots(window) }
                    _state.update { it.copy(simNames = simNames, simSlots = simSlots) }
                }
            }
    }

    // Live contact names: on a contact change (or the 60 s fallback tick) invalidate the
    // enricher's window cache and re-resolve the already-loaded window only (no DB
    // re-query, no scan beyond the resolver's own TTL). It is a no-op until the first
    // window lands.
    private fun startContactRefresh() {
        contactJob?.cancel()
        contactJob =
            scope.launch {
                contactChangeObserver.contactChanges.collect {
                    val window = currentWindow
                    if (window.isEmpty()) return@collect
                    val contactNames =
                        withContext(Dispatchers.IO) {
                            listEnricher.invalidate()
                            listEnricher.resolveAllContacts(window)
                        }
                    _state.update { it.copy(contactNames = contactNames) }
                }
            }
    }

    // The live window: the newest `loadedLimit` rows (plus one probe row for hasMore),
    // re-emitted on any DB change, so a status transition (a send landing, a retry
    // scheduled) while the screen is open is reflected without interaction. The query
    // already returns the DB's order (timeReceived DESC, id DESC): a single query cannot
    // duplicate or misorder, so no re-sort or dedup here.
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private fun startWindowCollection() {
        windowJob?.cancel()
        windowJob =
            scope.launch {
                combine(
                    // A clear (a non-empty query becoming empty) is not debounced: the
                    // unfiltered window must land immediately, not after the debounce
                    // (the stale empty filtered window would otherwise flash the screen's
                    // empty state). The initial (already-empty) query keeps the debounce:
                    // it has no keystrokes to collapse.
                    searchQuery
                        .scan("" to false) { (prev, _), cur -> cur to (prev.isNotEmpty() && cur.isEmpty()) }
                        .debounce { (_, cleared) -> if (cleared) 0L else AppConfig.SMS_SEARCH_DEBOUNCE_MS }
                        .map { (query, _) -> query },
                    pageBump,
                ) { query, _ -> query }
                    .map { query ->
                        withContext(Dispatchers.IO) { buildSearch(query) }
                    }.flatMapLatest { search ->
                        repository.observeSms(
                            scope = listScope,
                            search = search,
                            limit = pageWindow.loadedLimit + 1,
                        )
                    }.collect { rows ->
                        val window = rows.take(pageWindow.loadedLimit)
                        currentWindow = window
                        // provider IPC: runs off the main thread
                        val simNames = withContext(Dispatchers.IO) { listEnricher.buildSimNames(window) }
                        val simSlots = withContext(Dispatchers.IO) { listEnricher.buildSimSlots(window) }
                        val resolvedContacts = withContext(Dispatchers.IO) { listEnricher.resolveAllContacts(window) }
                        _state.update {
                            it.copy(
                                smsList = window,
                                hasMore = pageWindow.hasMore(rows.size),
                                isLoading = false,
                                simNames = simNames,
                                simSlots = simSlots,
                                contactNames = resolvedContacts,
                            )
                        }
                    }
            }
    }

    // The exact unfiltered total for the top bar title (the loaded list is
    // window-sized, not the total).
    private fun startCountCollection() {
        countJob?.cancel()
        countJob =
            scope.launch {
                repository.observeCount(listScope).collect { count ->
                    _state.update { it.copy(smsCount = count) }
                }
            }
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    // The search facet inputs: the contact-name facet (E.164-normalized numbers of
    // name-matched contacts, the digits-only fold when a number does not normalize)
    // and the SIM-name facet (slots whose display name matches). Both are gated on the
    // same prefs as the display enrichment, so a search never finds rows by a label
    // the user is not shown.
    private suspend fun buildSearch(query: String): SmsSearch {
        if (query == cachedSearchQuery) return cachedSearch
        if (query.isEmpty()) {
            cachedSearchQuery = query
            cachedSearch = SmsSearch.none()
            return cachedSearch
        }
        val settings = prefs.load()
        val contactNumbers =
            if (settings.useContactResolution) {
                contactResolver.resolveNumbersByName(query)
            } else {
                emptyList()
            }
        val simIds =
            if (settings.displaySimCards) {
                simNameResolver
                    .getSlotEntries()
                    .filter { (_, name) -> name?.contains(query, ignoreCase = true) == true }
                    .map { (slotId, _) -> slotId }
            } else {
                emptyList()
            }
        cachedSearchQuery = query
        cachedSearch = SmsSearch.forQuery(query, contactNumbers, simIds)
        return cachedSearch
    }

    fun loadMore() {
        if (!_state.value.hasMore) return
        pageWindow.grow()
        pageBump.value += 1
    }
}
