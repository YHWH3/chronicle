package com.chronicle.app.mind

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.WorkInfo
import com.chronicle.app.data.ChroniclePrefs
import com.chronicle.app.data.JournalRepository
import com.chronicle.app.data.MindBackend
import com.chronicle.app.data.PrefsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class SmartSetupPhase { HIDDEN, OFFER, WAITING, DOWNLOADING, SETTING_UP, READY, FAILED }
data class SmartSetupState(
    val modelId: String = SmartModels.DEFAULT_ID,
    val phase: SmartSetupPhase = SmartSetupPhase.HIDDEN,
    val progress: Int = 0,
    val reason: String? = null,
    val allowMobile: Boolean = false,
)

/** Pure offer policy, also used by the Settings surface. */
internal fun smartOfferVisible(notes: Int, enoughRam: Boolean, installed: Boolean, dismissedAt: Long, now: Long): Boolean =
    notes > 0 && enoughRam && !installed &&
        (dismissedAt == 0L || now - dismissedAt >= 7L * 24 * 60 * 60 * 1000)

internal fun smartSetupAllowed(backend: MindBackend, modelId: String): Boolean =
    backend in listOf(MindBackend.AUTO, MindBackend.ON_DEVICE) && SmartModels.find(modelId) != null

internal fun transferPhase(model: ModelState?, workQueued: Boolean, wifi: Boolean, allowMobile: Boolean): SmartSetupPhase = when {
    model is ModelState.Extracting -> SmartSetupPhase.SETTING_UP
    model is ModelState.Failed -> SmartSetupPhase.FAILED
    workQueued && !wifi && !allowMobile -> SmartSetupPhase.WAITING
    model is ModelState.Downloading || model == ModelState.Connecting || workQueued -> SmartSetupPhase.DOWNLOADING
    else -> SmartSetupPhase.OFFER
}

class SmartSetupController(
    private val context: Context,
    private val prefs: PrefsRepository,
    private val journal: JournalRepository,
    private val installer: ModelInstallManager,
    private val downloads: ModelDownloads,
    private val mind: MindRepository,
    private val scope: CoroutineScope,
) {
    val totalRam: Long = ActivityManager.MemoryInfo().also {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
    }.totalMem
    private val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val unmetered = MutableStateFlow(context.hasUnmeteredNetwork())
    private val warming = MutableStateFlow<Set<String>>(emptySet())
    private val ready = MutableStateFlow<Set<String>>(emptySet())
    private val loadFailures = MutableStateFlow<Map<String, String>>(emptyMap())
    private val showReady = MutableStateFlow<Set<String>>(emptySet())
    private var warmJob: Job? = null
    private var warmId: String? = null
    private val activeWork = downloads.work.getWorkInfosByTagFlow(ModelDownloads.TAG)
        .map { infos -> infos.filter { !it.state.isFinished }.associateBy { info ->
            info.tags.firstOrNull { SmartModels.forPack(it) != null }.orEmpty()
        } }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    private val prefState = prefs.prefs.stateIn(scope, SharingStarted.Eagerly, ChroniclePrefs())
    private val notes = journal.observeAll().map { it.size }.stateIn(scope, SharingStarted.Eagerly, 0)
    private val seenInstalled = SmartModels.all.filter {
        installer.states.value[it.packId] == ModelState.Installed
    }.map { it.id }.toMutableSet()

    val state: StateFlow<SmartSetupState> = combine(
        prefState, installer.states, activeWork, unmetered,
        combine(warming, ready, loadFailures, showReady) { a, b, c, d -> WarmState(a, b, c, d) },
    ) { p, models, works, wifi, warm ->
        val model = SmartModels.find(p.selectedSmartModelId) ?: SmartModels.all.first()
        val installed = models[model.packId] == ModelState.Installed
        val work = works[model.packId]
        val phase = when {
            model.id in warm.loading -> SmartSetupPhase.SETTING_UP
            model.id in warm.failed -> SmartSetupPhase.FAILED
            model.id in warm.flash -> SmartSetupPhase.READY
            installed && model.id in warm.ready -> SmartSetupPhase.HIDDEN
            installed -> SmartSetupPhase.SETTING_UP
            else -> transferPhase(models[model.packId], work != null, wifi, p.allowMobileData)
        }
        val transfer = models[model.packId] as? ModelState.Downloading
        SmartSetupState(model.id, phase,
            (transfer?.progress?.times(100)?.toInt() ?: 0).coerceIn(0, 100),
            warm.failed[model.id] ?: (models[model.packId] as? ModelState.Failed)?.reason, p.allowMobileData)
    }.stateIn(scope, SharingStarted.Eagerly, SmartSetupState())

    val todayState = combine(state, prefState, notes) { s, p, count ->
        val model = SmartModels.find(s.modelId)!!
        val offer = smartOfferVisible(count, model.supportedBy(totalRam),
            installer.states.value[model.packId] == ModelState.Installed,
            p.smartOfferDismissedAt, System.currentTimeMillis())
        val hide = !smartSetupAllowed(p.mindBackend, p.selectedSmartModelId) || count == 0 || !model.supportedBy(totalRam) ||
            (s.phase == SmartSetupPhase.OFFER && (!offer || p.mindBackend == MindBackend.QUIET_PARSER))
        if (hide) s.copy(phase = SmartSetupPhase.HIDDEN) else s
    }.stateIn(scope, SharingStarted.Eagerly, SmartSetupState())

    init {
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                unmetered.value = context.hasUnmeteredNetwork()
            }
            override fun onLost(network: Network) { unmetered.value = context.hasUnmeteredNetwork() }
        })
        scope.launch {
            var previousChoice: Pair<MindBackend, String>? = null
            combine(prefState, installer.states) { p, models -> p to models }.collect { (p, models) ->
                val choice = p.mindBackend to p.selectedSmartModelId
                if (choice != previousChoice) {
                    previousChoice = choice
                    // Release the old selection off-main, even if the new one
                    // is Basic/cloud/custom or has not been downloaded yet.
                    mind.resolve(p)
                }
                if (warmId != p.selectedSmartModelId ||
                    p.mindBackend !in listOf(MindBackend.AUTO, MindBackend.ON_DEVICE)) {
                    warmJob?.cancel()
                    warmId = null
                    ready.value = emptySet()
                    showReady.value = emptySet()
                }
                val model = SmartModels.find(p.selectedSmartModelId) ?: return@collect
                if (models[model.packId] == ModelState.Installed &&
                    p.mindBackend in listOf(MindBackend.AUTO, MindBackend.ON_DEVICE)) warm(p, model.id)
            }
        }
    }

    /** Warm-up is asynchronous; no native load runs on the UI thread. */
    fun onTodayOpened() {
        scope.launch {
            val p = prefs.prefs.first()
            if (p.mindBackend !in listOf(MindBackend.AUTO, MindBackend.ON_DEVICE)) return@launch
            val id = p.selectedSmartModelId
            val model = SmartModels.find(id) ?: return@launch
            if (installer.states.value[model.packId] == ModelState.Installed) warm(p, id)
        }
    }
    @Synchronized
    private fun warm(p: ChroniclePrefs, id: String) {
        if (warmId == id && warmJob?.isActive == true || id in ready.value) return
        warmJob?.cancel()
        warmId = id
        warmJob = scope.launch {
            warming.update { it + id }
            loadFailures.update { it - id }
            try {
                mind.resolve(p).prepare()
                ready.update { it + id }
                if (seenInstalled.add(id)) {
                    showReady.update { it + id }
                    delay(4_000)
                    showReady.update { it - id }
                }
            } catch (ce: CancellationException) { throw ce }
            catch (_: Throwable) { loadFailures.update { it + (id to "This model could not start on this phone.") } }
            finally { warming.update { it - id } }
        }
    }
    fun download() {
        scope.launch {
            val p = prefs.prefs.first()
            val model = SmartModels.find(p.selectedSmartModelId) ?: return@launch
            if (!model.supportedBy(totalRam)) return@launch
            if (installer.states.value[model.packId] == ModelState.Installed) {
                ready.update { it - model.id }
                warm(p, model.id)
            } else {
                if (installer.states.value[model.packId] is ModelState.Failed) {
                    downloads.pause(model.packId)
                    installer.clearFailure(model.packId)
                }
                downloads.enqueue(model.packId, p.allowMobileData)
            }
        }
    }
    fun dismiss() { scope.launch { prefs.dismissSmartOffer(System.currentTimeMillis()) } }
    fun pause() { scope.launch { downloads.pause(SmartModels.find(state.value.modelId)!!.packId) } }
    fun cancel() { scope.launch { downloads.cancel(SmartModels.find(state.value.modelId)!!.packId) } }
    fun useMobileData() {
        scope.launch {
            prefs.setAllowMobileData(true)
            val model = SmartModels.find(state.value.modelId)!!
            downloads.pause(model.packId)
            downloads.enqueue(model.packId, true)
        }
    }
    fun setMobileData(allow: Boolean) {
        scope.launch {
            val model = SmartModels.find(state.value.modelId) ?: return@launch
            val active = activeWork.value.containsKey(model.packId)
            prefs.setAllowMobileData(allow)
            if (active) {
                downloads.pause(model.packId)
                downloads.enqueue(model.packId, allow)
            }
        }
    }
    suspend fun delete(id: String) {
        val model = SmartModels.find(id) ?: return
        downloads.pause(model.packId)
        ready.update { it - id }
        loadFailures.update { it - id }
        installer.delete(model.packId)
    }
    private data class WarmState(val loading: Set<String>, val ready: Set<String>, val failed: Map<String, String>, val flash: Set<String>)
}
