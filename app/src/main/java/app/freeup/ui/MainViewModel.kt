package app.freeup.ui

import android.app.Application
import android.content.IntentSender
import android.provider.MediaStore
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

data class VideoRow(val video: Video, val probe: Probe?, val plan: Plan) {
    val compress: Plan.Compress? get() = plan as? Plan.Compress
    val saving: Long get() = compress?.let { video.sizeBytes - it.estimatedBytes } ?: 0
    val fps: Float get() = probe?.fps?.coerceIn(1f, 60f) ?: 30f
}

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
) {
    val selectedRows get() = rows.filter { it.video.id in selected && it.compress != null }
    val selectedSize get() = selectedRows.sumOf { it.video.sizeBytes }
    val selectedSaving get() = selectedRows.sumOf { it.saving }
    val totalSize get() = rows.sumOf { it.video.sizeBytes }
    val totalSaving get() = rows.sumOf { it.saving }
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
    private val quality = MutableStateFlow(Quality.BALANCED)
    private val screen = MutableStateFlow(Screen.GRID)
    private val measuring = MutableStateFlow(false)
    private val speedVersion = MutableStateFlow(0)
    private var probeJob: Job? = null

    private val rows = combine(videos, probes, quality, dao.observeDone()) { list, probeMap, q, done ->
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
                readable = p?.readable ?: true,
                alreadyDone = doneSizes[v.id] == v.sizeBytes || p?.compressedByFreeUp == true,
            )
            VideoRow(v, p, BitratePlanner.plan(input, q, hevc))
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

    val state: StateFlow<UiState> = combine(base, screen, measuring, speedVersion, progress) { b, sc, m, _, p ->
        val eta = b.selectedRows.sumOf { speed.secondsFor(it.video.width, it.video.height, it.fps, it.video.durationMs) }
        // Show progress whenever a batch is still going, even after the app was reopened.
        val effectiveScreen = if (p?.running == true) Screen.PROGRESS else sc
        b.copy(screen = effectiveScreen, measuringSpeed = m, etaSeconds = eta.toLong(), progress = p)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun onAccess(newAccess: Access) {
        access.value = newAccess
        if (newAccess != Access.NONE) reload()
    }

    fun reload() {
        viewModelScope.launch {
            loading.value = true
            val list = repo.loadVideos()
            videos.value = list
            selected.update { s -> s.filterTo(mutableSetOf()) { id -> list.any { it.id == id } } }
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

    fun toggle(id: Long) = selected.update { if (id in it) it - id else it + id }

    fun selectTopBiggest(n: Int = 10) {
        val ids = state.value.rows.filter { it.compress != null }.sortedByDescending { it.saving }.take(n).map { it.video.id }
        selected.value = ids.toSet()
    }

    fun clearSelection() { selected.value = emptySet() }

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
            screen.value = Screen.PROGRESS
        }
    }

    fun stop() = Jobs.stop(getApplication())

    fun finishProgress() {
        screen.value = Screen.GRID
        reload()
    }
}
