package app.freeup.ui

import android.app.Application
import android.content.IntentSender
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import app.freeup.data.AppDb
import app.freeup.data.JobItem
import app.freeup.data.JobState
import app.freeup.data.Probe
import app.freeup.data.Video
import app.freeup.data.VideoRepository
import app.freeup.engine.Benchmark
import app.freeup.engine.BitratePlanner
import app.freeup.engine.CompressWorker
import app.freeup.engine.Jobs
import app.freeup.engine.Plan
import app.freeup.engine.Prober
import app.freeup.engine.PlanInput
import app.freeup.engine.Quality
import app.freeup.engine.SkipReason
import app.freeup.engine.SpeedModel
import app.freeup.engine.Transcoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

enum class Access { NONE, PARTIAL, FULL }
enum class Screen { GRID, CONFIRM, PROGRESS }

private data class Extras(
    val screen: Screen,
    val measuring: Boolean,
    val speedVersion: Int,
    val freed: Long,
    val storage: StorageInfo,
    val awaitingDelete: List<JobItem>,
)

data class VideoRow(
    val video: Video,
    val probe: Probe?,
    /** What happens to this video now (forced if you chose "Compress anyway"). */
    val plan: Plan,
    /** What "Compress anyway" would do, for a skipped video. Null when it can't be forced. */
    val anyway: Plan.Compress?,
    val forced: Boolean,
) {
    val compress: Plan.Compress? get() = plan as? Plan.Compress
    val skip: Plan.Skip? get() = plan as? Plan.Skip
    val saving: Long get() = compress?.let { video.sizeBytes - it.estimatedBytes } ?: 0
    val fps: Float get() = probe?.fps?.coerceIn(1f, 60f) ?: 30f

    /** Saving FreeUp suggests without "Compress anyway". */
    val suggestedSaving: Long get() = if (forced) 0 else saving
}

data class StorageInfo(val totalBytes: Long = 0, val freeBytes: Long = 0)

data class BatchProgress(
    val items: List<JobItem>,
    val running: Boolean,
    val currentItemId: Long?,
    val currentFraction: Float,
    val etaSeconds: Long?,
) {
    val finished get() = items.count { it.state !in JobState.ACTIVE }
    val freed get() = items.filter { it.state == JobState.DONE }.sumOf { it.originalSize - (it.newSize ?: it.originalSize) }
    val overall: Float get() = if (items.isEmpty()) 0f else (finished + if (running) currentFraction else 0f) / items.size
}

data class UiState(
    val access: Access = Access.NONE,
    val loading: Boolean = true,
    val rows: List<VideoRow> = emptyList(),
    val selected: Set<Long> = emptySet(),
    val quality: Quality = Quality.BALANCED,
    val screen: Screen = Screen.GRID,
    val measuringSpeed: Boolean = false,
    val etaSeconds: Long = 0,
    val progress: BatchProgress? = null,
    val freedSoFar: Long = 0,
    val storage: StorageInfo = StorageInfo(),
    /** Converted videos whose original Android wants you to confirm deleting. */
    val awaitingDelete: List<JobItem> = emptyList(),
) {
    val selectedRows get() = rows.filter { it.video.id in selected && it.compress != null }
    val selectedSize get() = selectedRows.sumOf { it.video.sizeBytes }
    val selectedSaving get() = selectedRows.sumOf { it.saving }
    val totalSize get() = rows.sumOf { it.video.sizeBytes }
    val totalSaving get() = rows.sumOf { it.suggestedSaving }
    val compressibleCount get() = rows.count { it.compress != null && !it.forced }

    /** Caveats from "Compress anyway" in the current selection, with how many videos each covers. */
    val selectedOverrides: List<Pair<app.freeup.engine.SkipReason, Int>>
        get() = selectedRows.mapNotNull { it.compress?.overridden }.groupingBy { it }.eachCount().toList()

    /** Conversion notes across the selection, each with how many videos it applies to. */
    val selectedConversionNotes: List<Pair<String, Int>>
        get() = selectedRows.flatMap { it.compress?.conversionNotes.orEmpty() }.groupingBy { it }.eachCount().toList()

    /** Why the rest are left alone, most common first. */
    val skipCounts: List<Pair<SkipReason, Int>>
        get() = rows.mapNotNull { (it.plan as? Plan.Skip)?.reason }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = VideoRepository(app)
    private val dao = AppDb.get(app).jobs()
    private val speed = SpeedModel(app)
    private val hevc = Transcoder.hevcEncoderAvailable

    private val access = MutableStateFlow(Access.NONE)
    private val loading = MutableStateFlow(true)
    private val videos = MutableStateFlow<List<Video>>(emptyList())
    private val probes = MutableStateFlow<Map<Long, Probe>>(emptyMap())
    private val selected = MutableStateFlow<Set<Long>>(emptySet())
    private val forced = MutableStateFlow<Set<Long>>(emptySet())
    private val storage = MutableStateFlow(StorageInfo())
    private val quality = MutableStateFlow(Quality.BALANCED)
    private val screen = MutableStateFlow(Screen.GRID)
    private val measuring = MutableStateFlow(false)
    private val speedVersion = MutableStateFlow(0)
    private var probeJob: Job? = null

    private val rows = combine(videos, probes, quality, dao.observeDone(), forced) { list, probeMap, q, done, forcedIds ->
        val doneSizes = done.associate { it.mediaId to it.newSize }
        list.map { v ->
            val p = probeMap[v.id]
            val input = PlanInput(
                sizeBytes = v.sizeBytes,
                durationMs = v.durationMs,
                width = v.width,
                height = v.height,
                containerMime = v.mime,
                fps = p?.fps,
                captureFps = v.captureFps,
                audioBitrate = p?.audioBitrate,
                isHdr = p?.isHdr == true,
                sourceCodec = p?.videoMime,
                audioMime = p?.audioMime,
                audioChannels = p?.audioChannels,
                audioTracks = p?.audioTracks ?: 1,
                subtitleTracks = p?.subtitleTracks ?: 0,
                audioDecodable = p?.audioDecodable ?: true,
                readable = p?.readable ?: true,
                alreadyDone = doneSizes[v.id] == v.sizeBytes || p?.compressedByFreeUp == true,
            )
            val normal = BitratePlanner.plan(input, q, hevc)
            val anyway = if (normal is Plan.Skip && normal.reason.overridable) {
                BitratePlanner.plan(input, q, hevc, force = true) as? Plan.Compress
            } else {
                null
            }
            val isForced = v.id in forcedIds && anyway != null
            VideoRow(v, p, if (isForced) anyway!! else normal, anyway, isForced)
        }
    }

    private val progress = combine(
        dao.observeLatestBatchId().flatMapLatest { id -> if (id == null) flowOf(emptyList()) else dao.observeBatch(id) },
        Jobs.observe(app),
    ) { items, infos ->
        val running = infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        val live = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }?.progress
        BatchProgress(
            items = items,
            running = running && items.any { it.state in JobState.ACTIVE },
            currentItemId = live?.getLong(CompressWorker.KEY_ITEM, -1)?.takeIf { it >= 0 },
            currentFraction = live?.getFloat(CompressWorker.KEY_FRACTION, 0f) ?: 0f,
            etaSeconds = live?.getLong(CompressWorker.KEY_ETA, -1)?.takeIf { it >= 0 },
        ).takeIf { items.isNotEmpty() }
    }

    private val base = combine(access, loading, rows, selected, quality) { a, l, r, s, q ->
        UiState(access = a, loading = l, rows = r, selected = s, quality = q)
    }

    private val extras = combine(
        combine(screen, measuring, speedVersion) { sc, m, v -> Triple(sc, m, v) },
        dao.observeFreed(),
        storage,
        dao.observeAwaitingDelete(),
    ) { (sc, m, v), f, st, awaiting -> Extras(sc, m, v, f, st, awaiting) }

    val state: StateFlow<UiState> = combine(base, extras, progress) { b, x, p ->
        val sc = x.screen
        val m = x.measuring
        val eta = b.selectedRows.sumOf { speed.secondsFor(it.video.width, it.video.height, it.fps, it.video.durationMs) }
        // Show progress whenever a batch is still going, even after the app was reopened.
        val effectiveScreen = if (p?.running == true) Screen.PROGRESS else sc
        b.copy(screen = effectiveScreen, measuringSpeed = m, etaSeconds = eta.toLong(), progress = p, freedSoFar = x.freed, storage = x.storage, awaitingDelete = x.awaitingDelete)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    private var mediaLocation = false

    fun onAccess(newAccess: Access, mediaLocationGranted: Boolean) {
        access.value = newAccess
        // Headers read before this was granted have their GPS tag hidden; read them again.
        if (mediaLocationGranted != mediaLocation) {
            mediaLocation = mediaLocationGranted
            probeJob?.cancel()
            probes.value = emptyMap()
        }
        if (newAccess != Access.NONE) reload()
    }

    fun reload() {
        viewModelScope.launch {
            loading.value = true
            storage.value = readStorage()
            val list = repo.loadVideos()
            videos.value = list
            val ids = list.mapTo(HashSet()) { it.id }
            selected.update { s -> s.filterTo(mutableSetOf()) { it in ids } }
            forced.update { s -> s.filterTo(mutableSetOf()) { it in ids } }
            loading.value = false
            probeAll(list)
        }
    }

    /** Reads each video's headers in the background so the estimates get sharper as you look. */
    private fun probeAll(list: List<Video>) {
        probeJob?.cancel()
        val context = getApplication<Application>()
        probeJob = viewModelScope.launch(Dispatchers.IO) {
            val gate = Semaphore(3)
            val pending = list.filter { it.id !in probes.value }
            pending.chunked(12).forEach { chunk ->
                val results = coroutineScope {
                    chunk.map { v -> async { gate.withPermit { v.id to Prober.probe(context, v.uri) } } }.awaitAll()
                }
                probes.update { it + results }
            }
        }
    }

    fun toggle(id: Long) {
        if (id in selected.value) {
            selected.update { it - id }
            forced.update { it - id }
        } else {
            selected.update { it + id }
        }
    }

    /** "Compress anyway": you accept the caveat for a video FreeUp would have skipped. */
    fun compressAnyway(id: Long) {
        forced.update { it + id }
        selected.update { it + id }
    }

    fun selectTopBiggest(n: Int = 10) {
        val ids = state.value.rows.filter { it.compress != null && !it.forced }
            .sortedByDescending { it.saving }.take(n).map { it.video.id }
        selected.update { current -> current.filterTo(mutableSetOf()) { it in forced.value } + ids }
    }

    fun clearSelection() {
        selected.value = emptySet()
        forced.value = emptySet()
    }

    private fun readStorage(): StorageInfo = runCatching {
        val stat = android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path)
        StorageInfo(stat.totalBytes, stat.availableBytes)
    }.getOrDefault(StorageInfo())

    fun setQuality(q: Quality) { quality.value = q }

    fun openConfirm() {
        screen.value = Screen.CONFIRM
        if (!speed.isMeasured && !measuring.value) measureSpeed()
    }

    fun back() {
        screen.value = Screen.GRID
    }

    private fun measureSpeed() {
        val row = state.value.selectedRows.maxByOrNull { it.video.width * it.video.height } ?: return
        val plan = row.compress ?: return
        measuring.value = true
        viewModelScope.launch {
            Benchmark.run(getApplication(), row.video, row.fps, plan)
            speedVersion.update { it + 1 }
            measuring.value = false
        }
    }

    /** The one system dialog that lets FreeUp overwrite the selected videos. */
    fun writeRequest(): IntentSender? {
        val uris = state.value.selectedRows.map { it.video.uri }
        if (uris.isEmpty()) return null
        return MediaStore.createWriteRequest(getApplication<Application>().contentResolver, uris).intentSender
    }

    fun startCompressing() {
        val current = state.value
        val batchId = System.currentTimeMillis()
        val items = current.selectedRows.mapIndexed { i, row ->
            val plan = row.compress!!
            JobItem(
                batchId = batchId,
                position = i,
                mediaId = row.video.id,
                uri = row.video.uri.toString(),
                name = row.video.name,
                originalSize = row.video.sizeBytes,
                estimatedSize = plan.estimatedBytes,
                durationMs = row.video.durationMs,
                width = row.video.width,
                height = row.video.height,
                fps = row.fps,
                targetBitrate = plan.videoBitrate,
                videoMime = plan.outputMime,
                maxShortSide = plan.maxShortSide,
                toneMapHdr = plan.toneMapHdr,
                overridden = plan.overridden?.name,
                convertToMp4 = plan.convertToMp4,
                downmixToStereo = plan.downmixToStereo,
                audioChannels = row.probe?.audioChannels,
                relativePath = row.video.relativePath,
                dateTakenMs = row.video.dateTakenMs,
                creationTimeMs = row.probe?.creationTimeMs,
                latitude = row.probe?.location?.first,
                longitude = row.probe?.location?.second,
            )
        }
        if (items.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { Jobs.start(getApplication(), items) }
            selected.value = emptySet()
            forced.value = emptySet()
            screen.value = Screen.PROGRESS
        }
    }

    fun stop() = Jobs.stop(getApplication())

    /** Android's own dialog to delete originals that were converted to MP4. */
    fun deleteRequest(): IntentSender? {
        val uris = state.value.awaitingDelete.map { it.uri.toUri() }
        if (uris.isEmpty()) return null
        return MediaStore.createDeleteRequest(getApplication<Application>().contentResolver, uris).intentSender
    }

    fun onOriginalsDeleted() {
        val items = state.value.awaitingDelete
        viewModelScope.launch {
            items.forEach { dao.update(it.copy(state = JobState.DONE, reason = null)) }
            reload()
        }
    }

    fun finishProgress() {
        screen.value = Screen.GRID
        reload()
    }
}
