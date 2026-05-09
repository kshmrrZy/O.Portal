package com.example.o_portal_ott

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.Log
import android.util.TypedValue
import android.util.Xml
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.GestureDetectorCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread
import kotlin.math.abs

// Модели данных
data class Channel(
    val name: String,
    val url: String,
    val tvgId: String?,
    val tvgName: String?,
    val logoFromPlaylist: String?,
    val catchupDays: Int = 0,
    val catchupSource: String? = null,
    var logoFromEpg: String? = null
)

data class Program(val title: String, val start: Long, val stop: Long)

data class PlaylistProfile(
    val name: String,
    val type: String, // token|url
    val value: String
)

class MainActivity : AppCompatActivity() {

    private var mediaPlayer: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var retriedWithoutAudio = false
    private var firstFrameRendered = false
    private var retriedWithAlternateDecoder = false
    private var softwareDecoderMode = false
    private var preferGpuDecoding = true
    private var lastPlaybackPositionMs = -1L
    private var lastProgressWallClockMs = 0L
    private var bufferingSinceMs = 0L
    private var startupWaitSinceMs = 0L
    private var allowNonIdrKeyframes = false
    private lateinit var mDetector: GestureDetectorCompat

    private var channelListDialog: AlertDialog? = null
    private var scheduleDialog: AlertDialog? = null

    // UI элементы
    private lateinit var controlsPanel: View
    private lateinit var topInfoPanel: View
    private lateinit var tvEpg: TextView
    private lateinit var tvChannelName: TextView
    private lateinit var tvSystemTime: TextView
    private lateinit var tvHomeSystemTime: TextView
    private lateinit var tvHomeAppTitle: TextView
    private lateinit var tvHomeStartTitle: TextView
    private lateinit var tvHomeStartSubtitle: TextView
    private lateinit var ivLogo: ImageView
    private lateinit var btnLock: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnSleepTimer: ImageButton
    private lateinit var btnLiveReload: LinearLayout
    private lateinit var sbTimeline: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvProgramEndTime: TextView
    private lateinit var tvReloadingStatus: TextView
    private lateinit var listBackgroundOverlay: View
    private lateinit var timerWarningPanel: View
    private lateinit var btnStopTimer: TextView
    private lateinit var homePanel: View
    private lateinit var ivHomeSettings: ImageView
    private lateinit var ivHomePower: ImageView
    private lateinit var tvHomeSettingsTitle: TextView
    private lateinit var homeSettingsScreen: View

    private var lastHomePanelWidth = 0
    private var lastHomePanelHeight = 0

    // Состояние
    private var isLocked = false
    private var isPlaybackPaused = false
    private var currentChannelIndex = 0
    private val channels = mutableListOf<Channel>()
    private val epgData = mutableMapOf<String, MutableList<Program>>()
    private val epgDataLock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private var inputNumber = ""
    private var currentPlaylistText: String = ""
    private var availableEpgSources: List<String> = emptyList()
    private var selectedEpgSources: MutableSet<String> = mutableSetOf()
    private val epgSourceStatus = mutableMapOf<String, String>()
    private val cachedLogos = mutableMapOf<String, String>()

    // Fallback alias for legacy references after refactors (kept to avoid unresolved symbol issues in stale IDE states)
    private var candidates: List<String> = emptyList()

    private var timerEndAtMillis: Long = 0L
    private var lastBackPressAt = 0L

    private var shouldOpenLastChannelOnStart = false
    private var isArchivePlayback = false
    private var currentArchiveProgram: Program? = null
    private var timelineUserSeeking = false
    private var shouldReloadStreamOnStart = false
    @Volatile private var epgFetchInProgress = false
    private var archiveStreamStartMs: Long = 0L
    private var lastRequestedPlaybackUrl: String = ""
    private val startupFrameTimeoutRunnable: Runnable = Runnable {
        if (firstFrameRendered) return@Runnable
        val player = mediaPlayer
        val now = System.currentTimeMillis()
        if (startupWaitSinceMs == 0L) startupWaitSinceMs = now
        if (player != null && player.playbackState == androidx.media3.common.Player.STATE_BUFFERING && now - startupWaitSinceMs < 30_000L) {
            handler.postDelayed(startupFrameTimeoutRunnable, 4000L)
            return@Runnable
        }
        if (!retriedWithAlternateDecoder && lastRequestedPlaybackUrl.isNotBlank()) {
            showCenterError("Нет видеокадра, пробуем другой декодер", 2500L)
            retriedWithAlternateDecoder = true
            switchDecoderModeAndRestart()
            return@Runnable
        } else if (!retriedWithoutAudio && lastRequestedPlaybackUrl.isNotBlank()) {
            showCenterError("Нет видеокадра, пробуем запуск без аудио", 2200L)
            retryCurrentStreamWithoutAudio()
        } else if (!allowNonIdrKeyframes && lastRequestedPlaybackUrl.isNotBlank()) {
            // Last-resort fallback for H.264 inside MPEG-TS: decoding from non-IDR frames may
            // temporarily look blocky because reference frames are missing, so keep it after
            // decoder/audio retries rather than enabling it first.
            showCenterError("Нет IDR, последний fallback для TS", 2400L)
            allowNonIdrKeyframes = true
            restartCurrentStream(recreatePlayer = true)
        } else {
            showCenterError("Нет видеокадра, перезапускаем поток", 2200L)
            restartCurrentStream(recreatePlayer = false)
        }
    }

    private val playbackFreezeWatchdogRunnable: Runnable = Runnable {
        val player = mediaPlayer ?: return@Runnable
        val now = System.currentTimeMillis()
        if (!player.isPlaying) {
            if (player.playbackState == androidx.media3.common.Player.STATE_BUFFERING) {
                if (bufferingSinceMs == 0L) bufferingSinceMs = now
                if (now - bufferingSinceMs > 12_000L) {
                    showCenterError("Буферизация зависла, перезапуск потока", 2200L)
                    player.stop()
                    player.setMediaItem(buildMediaItem(lastRequestedPlaybackUrl))
                    player.prepare()
                    player.playWhenReady = true
                    bufferingSinceMs = now
                }
            } else {
                bufferingSinceMs = 0L
            }
            handler.postDelayed(playbackFreezeWatchdogRunnable, 4000L)
            return@Runnable
        }
        bufferingSinceMs = 0L
        val pos = player.currentPosition
        if (pos > lastPlaybackPositionMs + 250L) {
            lastPlaybackPositionMs = pos
            lastProgressWallClockMs = now
        } else if (lastProgressWallClockMs > 0L && now - lastProgressWallClockMs > 10_000L) {
            showCenterError("Поток завис, выполняем перезапуск", 2200L)
            player.stop()
            player.setMediaItem(buildMediaItem(lastRequestedPlaybackUrl))
            player.prepare()
            player.playWhenReady = true
            lastProgressWallClockMs = now
        }
        handler.postDelayed(playbackFreezeWatchdogRunnable, 4000L)
    }


    private val returnToLiveRunnable = Runnable {
        tvReloadingStatus.text = "Возвращаемся к прямой трансляции"
        playChannel(forcePlay = true)
    }

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
    private val prefs by lazy { getSharedPreferences("oportal_settings", Context.MODE_PRIVATE) }
    private val golosTypeface: Typeface? by lazy { ResourcesCompat.getFont(this, R.font.golos_text) }

    companion object {
        private const val PREF_PLAYLISTS = "playlist_profiles"
        private const val PREF_SELECTED_PLAYLIST = "selected_playlist"
        private const val PREF_SELECTED_EPG = "selected_epg"
        private const val PREF_LAST_CHANNEL = "last_channel"
        private const val PREF_EPG_CACHE = "epg_cache"
        private const val PREF_EPG_STATUS = "epg_status"
        private const val PREF_EPG_LAST_REFRESH = "epg_last_refresh"
        private const val PREF_CUSTOM_EPG_SOURCES = "custom_epg_sources"
        private const val PREF_LOGO_CACHE = "logo_cache"
        private const val PREF_START_LAST_CHANNEL = "pref_start_last_channel"
        private const val PREF_SHOW_LOCK_BUTTON = "pref_show_lock_button"
        private const val PREF_APP_VERSION_CODE = "pref_app_version_code"
        private const val PREF_USE_GPU_DECODER = "pref_use_gpu_decoder"
        private const val PREF_EPG_SOURCES_FINGERPRINT = "pref_epg_sources_fingerprint"

        private const val TOKEN_PREFIX = "https://o.avff.ru/my/"
        private const val TOKEN_SUFFIX = ".m3u"
        private const val MAX_EPG_COMPRESSED_BYTES = 900L * 1024L * 1024L
        private const val MAX_EPG_UNPACKED_BYTES = 1800L * 1024L * 1024L
        private const val EPG_KEEP_DAYS = 7
        private const val MAX_PROGRAMS_PER_CHANNEL = 5000

        private const val HOME_BASE_WIDTH = 1280f
        private const val HOME_BASE_HEIGHT = 720f
    }

    private val hideUiRunnable = Runnable { hideUI() }
    private val channelSwitchRunnable = Runnable { processChannelNumberInput() }
    private val restoreEpgRunnable = Runnable { updateEpgDisplay() }
    private val epgTickerRunnable = object : Runnable {
        override fun run() {
            updateEpgDisplay()
            handler.postDelayed(this, 10_000L)
        }
    }
    private val timelineTickerRunnable = object : Runnable {
        override fun run() {
            if (isArchivePlayback) updateEpgDisplay() else updateTimelineUi()
            handler.postDelayed(this, 1000L)
        }
    }
    private val timerFinishRunnable = Runnable {
        if (timerEndAtMillis > 0L && System.currentTimeMillis() >= timerEndAtMillis) {
            closeAppCompletely()
        }
    }
    private val timerWarnRunnable = Runnable { showTimerWarning() }

    private fun getProgramsForChannel(ch: Channel): List<Program> {
        val key1 = ch.tvgId?.lowercase()?.trim()
        val key2 = ch.tvgName?.lowercase()?.trim()
        val key3 = ch.name.lowercase().trim()
        return synchronized(epgDataLock) {
            val list = epgData[key1] ?: epgData[key2] ?: epgData[key3]
            list?.toList().orEmpty()
        }
    }

    private fun buildPlaceholderPrograms(): List<Program> {
        val result = mutableListOf<Program>()
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -7)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val end = System.currentTimeMillis() + 60L * 60L * 1000L
        while (cal.timeInMillis < end) {
            val start = cal.timeInMillis
            cal.add(Calendar.HOUR_OF_DAY, 1)
            result += Program("Выполняется обновление программы передач", start, cal.timeInMillis)
        }
        return result
    }

    private fun getProgramsForDisplay(ch: Channel): List<Program> {
        val real = getProgramsForChannel(ch)
        return if (real.isNotEmpty()) real else buildPlaceholderPrograms()
    }

    private fun isEpgDataEmpty(): Boolean = synchronized(epgDataLock) { epgData.isEmpty() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } catch (_: Exception) {
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()
        setContentView(R.layout.activity_main)

        ensureDefaultPlaylistProfile()
        initViews()
        setupInteractions()
        setupBackHandling()
        loadEpgCache()
        shouldOpenLastChannelOnStart = prefs.getBoolean(PREF_START_LAST_CHANNEL, false)
        if (shouldOpenLastChannelOnStart) restoreLastChannel()
        startClockUpdater()
        startEpgTicker()
        applyLockButtonVisibility()
        loadPlaylist(showErrors = true, autoPlay = shouldOpenLastChannelOnStart)
        if (!shouldOpenLastChannelOnStart) {
            showStartPage()
        }
    }

    private fun initViews() {
        controlsPanel = findViewById(R.id.controlsPanel)
        topInfoPanel = findViewById(R.id.topInfoPanel)
        tvEpg = findViewById(R.id.tvEpgInfo)
        tvChannelName = findViewById(R.id.tvChannelNameInfo)
        tvSystemTime = findViewById(R.id.tvSystemTime)
        tvHomeSystemTime = findViewById(R.id.tvHomeSystemTime)
        tvHomeAppTitle = findViewById(R.id.tvHomeAppTitle)
        tvHomeStartTitle = findViewById(R.id.tvHomeStartTitle)
        tvHomeStartSubtitle = findViewById(R.id.tvHomeStartSubtitle)
        ivLogo = findViewById(R.id.ivChannelLogo)
        btnLock = findViewById(R.id.btnLock)
        btnSettings = findViewById(R.id.btnSettings)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnSleepTimer = findViewById(R.id.btnSleepTimer)
        btnLiveReload = findViewById(R.id.btnLiveReload)
        sbTimeline = findViewById(R.id.sbTimeline)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvProgramEndTime = findViewById(R.id.tvProgramEndInfo)
        tvReloadingStatus = findViewById(R.id.tvReloadingStatus)
        listBackgroundOverlay = findViewById(R.id.listBackgroundOverlay)
        timerWarningPanel = findViewById(R.id.timerWarningPanel)
        btnStopTimer = findViewById(R.id.btnStopTimer)
        homePanel = findViewById(R.id.homePanel)
        ivHomeSettings = findViewById(R.id.ivHomeSettings)
        ivHomePower = findViewById(R.id.ivHomePower)
        tvHomeSettingsTitle = findViewById(R.id.tvHomeSettingsTitle)
        homeSettingsScreen = findViewById(R.id.homeSettingsScreen)
        tvEpg.isSelected = true
        applyGolosTypeface(window.decorView)
        applyHomeAppTitleStyle()
        homePanel.addOnLayoutChangeListener { _, _, _, right, bottom, _, _, oldRight, oldBottom ->
            if (right != oldRight || bottom != oldBottom) {
                applyHomeScreenScale(force = true)
            }
        }
        homePanel.post { applyHomeScreenScale(force = true) }
    }

    private fun applyHomeAppTitleStyle() {
        val title = SpannableString("O.Portal")
        golosTypeface?.let { font ->
            tvHomeAppTitle.typeface = Typeface.create(font, Typeface.NORMAL)
            title.setSpan(
                TypefaceSpan(Typeface.create(font, Typeface.BOLD)),
                2,
                title.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            tvHomeSystemTime.typeface = Typeface.create(font, Typeface.BOLD)
        } ?: title.setSpan(StyleSpan(Typeface.BOLD), 2, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        tvHomeAppTitle.text = title
    }

    private fun applyHomeScreenScale(force: Boolean = false) {
        val panelWidth = homePanel.width.takeIf { it > 0 } ?: return
        val panelHeight = homePanel.height.takeIf { it > 0 } ?: return
        if (!force && panelWidth == lastHomePanelWidth && panelHeight == lastHomePanelHeight) return

        lastHomePanelWidth = panelWidth
        lastHomePanelHeight = panelHeight

        val widthScale = panelWidth.toFloat() / HOME_BASE_WIDTH
        val heightScale = panelHeight.toFloat() / HOME_BASE_HEIGHT
        val scale = minOf(widthScale, heightScale)

        tvHomeAppTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, 42f * scale)
        tvHomeSystemTime.setTextSize(TypedValue.COMPLEX_UNIT_PX, 34f * scale)
        tvHomeStartTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, 52f * scale)
        tvHomeStartSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22f * scale)




        setHomeFrame(tvHomeAppTitle, 61f, 47f, null, null, widthScale, heightScale)
        setHomeFrame(tvHomeSystemTime, 1045f, 47f, 85f, null, widthScale, heightScale)
        setHomeFrame(ivHomeSettings, 1140f, 47f, 40f, 40f, widthScale, heightScale)
        setHomeFrame(ivHomePower, 1196f, 47f, 40f, 40f, widthScale, heightScale)
        setHomeFrame(tvHomeStartTitle, 257f, 321f, 765f, null, widthScale, heightScale)
        setHomeFrame(tvHomeStartSubtitle, 315f, 379f, 650f, null, widthScale, heightScale)
    }

    private fun setHomeFrame(
        view: View,
        baseLeft: Float,
        baseTop: Float,
        baseWidth: Float?,
        baseHeight: Float?,
        widthScale: Float,
        heightScale: Float
    ) {
        val params = view.layoutParams as ConstraintLayout.LayoutParams
        params.startToStart = ConstraintSet.PARENT_ID
        params.topToTop = ConstraintSet.PARENT_ID
        params.endToEnd = ConstraintSet.UNSET
        params.bottomToBottom = ConstraintSet.UNSET
        params.horizontalBias = 0f
        params.verticalBias = 0f
        val leftMargin = (baseLeft * widthScale).toInt()
        params.leftMargin = leftMargin
        params.marginStart = leftMargin
        params.topMargin = (baseTop * heightScale).toInt()
        params.width = baseWidth?.let { (it * widthScale).toInt().coerceAtLeast(1) } ?: ViewGroup.LayoutParams.WRAP_CONTENT
        params.height = baseHeight?.let { (it * heightScale).toInt().coerceAtLeast(1) } ?: ViewGroup.LayoutParams.WRAP_CONTENT
        view.layoutParams = params
    }

    private fun applyGolosTypeface(view: View) {
        val font = golosTypeface ?: return
        if (view is TextView) {
            view.typeface = Typeface.create(font, view.typeface?.style ?: Typeface.NORMAL)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyGolosTypeface(view.getChildAt(i))
            }
        }
    }

    private fun setupInteractions() {
        ivHomeSettings.setOnClickListener { if (homeSettingsScreen.visibility == View.VISIBLE) hideSettingsScreen() else showSettingsDialog() }
        ivHomePower.setOnClickListener { closeAppCompletely() }

        btnLiveReload.setOnClickListener {
            tvReloadingStatus.visibility = View.VISIBLE
            if (isArchivePlayback) {
                tvReloadingStatus.text = "Возвращаемся к прямой трансляции"
            }
            playChannel(forcePlay = true)
            handler.postDelayed({
                tvReloadingStatus.visibility = View.GONE
                tvReloadingStatus.text = "Обновление трансляции..."
            }, 1200)
        }
        sbTimeline.max = 1000
        sbTimeline.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                timelineUserSeeking = true
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val progress = seekBar?.progress ?: 0
                if (isArchivePlayback) {
                    val p = currentArchiveProgram ?: run { timelineUserSeeking = false; return }
                    val target = p.start + ((p.stop - p.start) * (progress / 1000f)).toLong()
                    seekArchiveTo(target)
                } else {
                    val ch = channels.getOrNull(currentChannelIndex)
                    val cur = ch?.let { getProgramsForDisplay(it).find { pr -> System.currentTimeMillis() in pr.start until pr.stop } }
                    if (ch != null && cur != null && isArchiveAvailable(ch, cur)) {
                        val target = cur.start + ((cur.stop - cur.start) * (progress / 1000f)).toLong()
                        playArchiveProgram(ch, cur)
                        handler.postDelayed({ seekArchiveTo(target) }, 450L)
                    }
                }
                timelineUserSeeking = false
            }
        })

        btnSettings.setOnClickListener { showSettingsDialog() }
        btnSleepTimer.setOnClickListener { showTimerDialog() }

        btnPlayPause.setOnClickListener {
            if (isPlaybackPaused) {
                mediaPlayer?.play()
                handler.postDelayed(startupFrameTimeoutRunnable, 8000L)
                isPlaybackPaused = false
                btnPlayPause.setImageResource(R.drawable.ic_pause)
            } else {
                mediaPlayer?.pause()
                isPlaybackPaused = true
                btnPlayPause.setImageResource(R.drawable.ic_play)
            }
            showUI()
        }

        btnLock.setOnClickListener {
            isLocked = !isLocked
            btnLock.setImageResource(if (isLocked) R.drawable.ic_lock_closed else R.drawable.ic_lock_open)
            tvEpg.text = if (isLocked) "Управление свайпами заблокировано." else "Управление свайпами разблокировано."
            handler.removeCallbacks(restoreEpgRunnable)
            handler.postDelayed(restoreEpgRunnable, 2000)
            showUI()
        }

        btnStopTimer.setOnClickListener {
            cancelSleepTimer()
            Toast.makeText(this, "Таймер остановлен", Toast.LENGTH_SHORT).show()
        }

        mDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                if (isLocked) {
                    showLockedMessage()
                    return false
                }

                val dx = e2.x - e1.x
                val dy = e2.y - e1.y

                if (abs(dy) > abs(dx)) {
                    if (channels.isNotEmpty()) {
                        currentChannelIndex = (currentChannelIndex + if (dy < 0) 1 else -1 + channels.size) % channels.size
                        playChannel()
                    }
                    return true
                }

                if (dx > 120 && abs(dx) > abs(dy)) {
                    showChannelList()
                    return true
                }

                if (dx < -120 && abs(dx) > abs(dy)) {
                    showCurrentChannelSchedule()
                    return true
                }

                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (controlsPanel.visibility == View.VISIBLE) hideUI() else showUI()
                return true
            }
        })
    }

    private fun showStartPage() {
        homePanel.visibility = View.VISIBLE
        homePanel.post { applyHomeScreenScale(force = true) }
        topInfoPanel.visibility = View.GONE
        controlsPanel.visibility = View.GONE
    }

    private fun hideStartPage() {
        homePanel.visibility = View.GONE
        showUI()
    }

    private fun applyLockButtonVisibility() {
        val showLock = prefs.getBoolean(PREF_SHOW_LOCK_BUTTON, true)
        btnLock.visibility = if (showLock) View.VISIBLE else View.GONE
    }

    private fun showHomePlaylistSelector() {
        val profiles = getPlaylistProfiles()
        if (profiles.isEmpty()) {
            Toast.makeText(this, "Добавьте плейлист в настройках", Toast.LENGTH_SHORT).show()
            return
        }
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            profiles.map { it.name })
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(sp)
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setTitle("Выбор плейлиста")
            .setView(view)
            .setPositiveButton("Открыть каналы") { _, _ ->
                val p = profiles.getOrNull(sp.selectedItemPosition) ?: return@setPositiveButton
                setSelectedPlaylistName(p.name)
                hideStartPage()
                loadPlaylist(forceReload = true, showErrors = true, autoPlay = true)
                handler.postDelayed({ showChannelList() }, 700)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun openFavoritesByToken() {
        val selected = getPlaylistProfiles().firstOrNull { it.name == getSelectedPlaylistName() }
            ?: getPlaylistProfiles().firstOrNull()
        if (selected == null || selected.type != "token" || selected.value.isBlank()) {
            Toast.makeText(this, "Введите токен в настройках", Toast.LENGTH_LONG).show()
            showSettingsDialog()
            return
        }
        hideStartPage()
        loadPlaylist(forceReload = true, showErrors = true, autoPlay = true)
        handler.postDelayed({ showChannelList() }, 700)
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this) {
            val now = System.currentTimeMillis()
            if (now - lastBackPressAt < 2000L) {
                closeAppCompletely()
            } else {
                lastBackPressAt = now
                showExitWarning()
            }
        }
    }

    private fun showExitWarning() {
        tvReloadingStatus.text = "Нажмите ещё раз для выхода!"
        tvReloadingStatus.setBackgroundResource(R.drawable.live_btn_bg)
        tvReloadingStatus.visibility = View.VISIBLE
        handler.postDelayed({
            tvReloadingStatus.visibility = View.GONE
            tvReloadingStatus.text = "Обновление трансляции..."
            tvReloadingStatus.setBackgroundResource(R.drawable.bg_reload_toast)
        }, 1800)
    }

    private fun closeAppCompletely() {
        cancelSleepTimer()
        stopPlayback()
        finish()
    }

    private fun showTimerDialog() {
        val options = arrayOf(10, 20, 30, 60, 90, 120, 240)
        val view = layoutInflater.inflate(R.layout.dialog_timer, null)
        val spinner = view.findViewById<Spinner>(R.id.spTimerMinutes)
        val btnApply = view.findViewById<TextView>(R.id.btnApplyTimer)
        val btnClose = view.findViewById<TextView>(R.id.btnCloseTimer)

        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            options.map { "$it минут" }
        )

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(view)
            .create()

        btnApply.setOnClickListener {
            val idx = spinner.selectedItemPosition.coerceIn(options.indices)
            startSleepTimer(options[idx])
            dialog.dismiss()
        }
        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
        dialog.window?.decorView?.let { applyGolosTypeface(it) }
        val dm = resources.displayMetrics
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.CENTER)
            setLayout((dm.widthPixels * 0.42f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun startSleepTimer(minutes: Int) {
        cancelSleepTimer()
        timerEndAtMillis = System.currentTimeMillis() + minutes * 60_000L
        handler.postDelayed(timerFinishRunnable, minutes * 60_000L)
        handler.postDelayed(timerWarnRunnable, (minutes * 60_000L - 30_000L).coerceAtLeast(0L))
        Toast.makeText(this, "Таймер установлен на $minutes минут", Toast.LENGTH_SHORT).show()
    }

    private fun showTimerWarning() {
        timerWarningPanel.visibility = View.VISIBLE
        handler.postDelayed({ timerWarningPanel.visibility = View.GONE }, 30_000L)
    }

    private fun cancelSleepTimer() {
        timerEndAtMillis = 0L
        handler.removeCallbacks(timerFinishRunnable)
        handler.removeCallbacks(timerWarnRunnable)
        timerWarningPanel.visibility = View.GONE
    }

    private fun showChannelList() {
        if (channelListDialog?.isShowing == true) return
        channelListDialog?.dismiss()

        val view = layoutInflater.inflate(R.layout.dialog_channel_list, null)
        val grid = view.findViewById<GridView>(R.id.gvChannels)

        listBackgroundOverlay.apply {
            setBackgroundColor(Color.parseColor("#99000000"))
            visibility = View.VISIBLE
        }

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(view)
            .create()

        channelListDialog = dialog

        dialog.setOnDismissListener {
            listBackgroundOverlay.visibility = View.GONE
            channelListDialog = null
        }

        grid.adapter = object : ArrayAdapter<Channel>(this, 0, channels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val holder: ChannelItemViewHolder
                val itemView: View

                if (convertView == null) {
                    itemView = layoutInflater.inflate(R.layout.item_channel, parent, false)
                    holder = ChannelItemViewHolder(
                        tvName = itemView.findViewById(R.id.itemName),
                        tvEpgItem = itemView.findViewById(R.id.itemEpg),
                        ivLogoItem = itemView.findViewById(R.id.itemLogo),
                        btnWatch = itemView.findViewById(R.id.btnWatch)
                    )
                    itemView.tag = holder
                } else {
                    itemView = convertView
                    holder = convertView.tag as ChannelItemViewHolder
                }

                val channel = channels[position]
                holder.tvName.text = "${position + 1}. ${channel.name}"
                holder.tvName.isSelected = true

                val pList = getProgramsForDisplay(channel)
                val cur = pList.find { System.currentTimeMillis() in it.start until it.stop }
                holder.tvEpgItem.text = cur?.title ?: "Нет программы"
                holder.tvEpgItem.isSelected = true

                loadLogoWithGlide(channel.logoFromEpg ?: channel.logoFromPlaylist, holder.ivLogoItem)

                val startChannel = View.OnClickListener {
                    currentChannelIndex = position
                    playChannel(forcePlay = true)
                    channelListDialog?.dismiss()
                }

                itemView.setOnClickListener(startChannel)
                holder.btnWatch.setOnClickListener(startChannel)

                return itemView
            }
        }

        grid.apply {
            setSelector(R.drawable.selector_channel_item)
            setLayerType(View.LAYER_TYPE_NONE, null)
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        dialog.show()

        val dm = resources.displayMetrics
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setGravity(Gravity.CENTER)
            setLayout((dm.widthPixels * 0.82f).toInt(), (dm.heightPixels * 0.82f).toInt())
        }
    }

    private fun showCurrentChannelSchedule() {
        val ch = channels.getOrNull(currentChannelIndex) ?: return
        val programs = getProgramsForDisplay(ch).distinctBy { Triple(it.title.trim(), it.start, it.stop) }.sortedBy { it.start }

        val byDate = programs.groupBy {
            SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(it.start))
        }

        val view = layoutInflater.inflate(R.layout.dialog_channel_schedule, null)
        val tvHeader = view.findViewById<TextView>(R.id.tvScheduleHeader)
        val hsvDates = view.findViewById<android.widget.HorizontalScrollView>(R.id.hsvDates)
        val dateContainer = view.findViewById<LinearLayout>(R.id.dateContainer)
        val lvSchedule = view.findViewById<ListView>(R.id.lvSchedule)

        val currentProgram = programs.find { System.currentTimeMillis() in it.start until it.stop }
        tvHeader.text = "${ch.name}\nСейчас: ${currentProgram?.title ?: "Нет данных"}"

        val dateKeys = byDate.keys.sortedBy { key ->
            SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).parse(key)?.time ?: 0L
        }

        val todayKey = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date())
        var selectedDate = dateKeys.firstOrNull { it == todayKey } ?: dateKeys.first()

        fun renderSchedule(date: String) {
            val items = byDate[date].orEmpty().sortedBy { it.start }
            val now = System.currentTimeMillis()
            lvSchedule.adapter = object : ArrayAdapter<Program>(this, 0, items) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val row = convertView ?: layoutInflater.inflate(R.layout.item_schedule_program, parent, false)
                    val item = getItem(position) ?: return row
                    val tvTime = row.findViewById<TextView>(R.id.tvProgramTime)
                    val tvTitle = row.findViewById<TextView>(R.id.tvProgramTitle)
                    val tvBadge = row.findViewById<TextView>(R.id.tvNowOnAirBadge)
                    val tvArchiveBadge = row.findViewById<TextView>(R.id.tvArchiveBadge)
                    tvTime.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.start))
                    tvTitle.text = item.title
                    val isNow = now in item.start until item.stop
                    val archiveAvailable = isArchiveAvailable(ch, item)
                    tvBadge.visibility = if (isNow) View.VISIBLE else View.GONE
                    tvArchiveBadge.visibility = if (archiveAvailable) View.VISIBLE else View.GONE
                    row.alpha = if (isNow) 1f else 0.95f
                    row.setOnClickListener {
                        if (!archiveAvailable) {
                            Toast.makeText(this@MainActivity, "Архив недоступен для этой передачи", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        playArchiveProgram(ch, item)
                        scheduleDialog?.dismiss()
                    }
                    return row
                }
            }

            val currentIdx = items.indexOfFirst { now in it.start until it.stop }
            if (currentIdx >= 0) lvSchedule.post { lvSchedule.setSelection(currentIdx.coerceAtLeast(0)) }
        }

        dateKeys.forEach { dateKey ->
            val chip = TextView(this).apply {
                text = dateKey
                setTextColor(Color.WHITE)
                setShadowLayer(2f, 0f, 0f, Color.parseColor("#80000000"))
                setPadding(24, 12, 24, 12)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                background = getDrawable(R.drawable.bg_date_chip)
                isSelected = dateKey == selectedDate
                setOnClickListener {
                    selectedDate = dateKey
                    for (i in 0 until dateContainer.childCount) {
                        dateContainer.getChildAt(i).isSelected = false
                    }
                    isSelected = true
                    renderSchedule(dateKey)
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = 12
            dateContainer.addView(chip, lp)
        }

        val selectedIdx = dateKeys.indexOf(selectedDate)
        if (selectedIdx >= 0) {
            dateContainer.post {
                val selectedChip = dateContainer.getChildAt(selectedIdx)
                if (selectedChip != null) {
                    val scrollX = (selectedChip.left - (hsvDates.width - selectedChip.width) / 2).coerceAtLeast(0)
                    hsvDates.smoothScrollTo(scrollX, 0)
                }
            }
        }

        renderSchedule(selectedDate)

        scheduleDialog?.dismiss()
        scheduleDialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(view)
            .create()

        scheduleDialog?.show()
        val dm = resources.displayMetrics
        scheduleDialog?.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.CENTER)
            setLayout((dm.widthPixels * 0.92f).toInt(), (dm.heightPixels * 0.9f).toInt())
        }
    }

    private fun showSettingsDialog() {
        homePanel.visibility = View.VISIBLE
        homePanel.post { applyHomeScreenScale(force = true) }
        tvHomeStartTitle.visibility = View.GONE
        tvHomeStartSubtitle.visibility = View.GONE
        tvHomeSettingsTitle.visibility = View.VISIBLE
        homeSettingsScreen.visibility = View.VISIBLE

        val btnPlaylistSettings = findViewById<View>(R.id.btnPlaylistSettings)
        val btnEpgSelect = findViewById<View>(R.id.btnEpgSelect)
        val tbStartMode = findViewById<ToggleButton>(R.id.tbStartMode)
        val btnSleepTimerSettings = findViewById<View>(R.id.btnSleepTimerSettings)
        val btnAdvancedSettings = findViewById<View>(R.id.btnAdvancedSettings)
        val btnUserSettings = findViewById<View>(R.id.btnUserSettings)
        val btnClose = findViewById<View>(R.id.btnCloseSettingsDialog)

        tbStartMode.isChecked = prefs.getBoolean(PREF_START_LAST_CHANNEL, false)
        tbStartMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREF_START_LAST_CHANNEL, isChecked).apply()
            shouldOpenLastChannelOnStart = isChecked
        }

        btnPlaylistSettings.setOnClickListener { showPlaylistSettingsDialog() }
        btnEpgSelect.setOnClickListener { showEpgSelectionDialog() }
        btnSleepTimerSettings.setOnClickListener { showTimerDialog() }
        btnAdvancedSettings.setOnClickListener { showSettingsPlaceholderDialog() }
        btnUserSettings.setOnClickListener { showSettingsPlaceholderDialog() }
        btnClose.setOnClickListener { hideSettingsScreen() }
    }

    private fun hideSettingsScreen() {
        homeSettingsScreen.visibility = View.GONE
        tvHomeSettingsTitle.visibility = View.GONE
        tvHomeStartTitle.visibility = View.VISIBLE
        tvHomeStartSubtitle.visibility = View.VISIBLE
        if (channels.isNotEmpty()) {
            homePanel.visibility = View.GONE
            showUI()
        }
    }

    private fun showSettingsPlaceholderDialog() {
        AlertDialog.Builder(this)
            .setMessage("В данный момент ничего нет! Попробуйте посмотреть позже")
            .setPositiveButton("ОК", null)
            .show()
    }

    private fun showPlaylistSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_playlist_settings, null)
        val spPlaylist = view.findViewById<Spinner>(R.id.spPlaylist)
        val etPlaylistName = view.findViewById<EditText>(R.id.etPlaylistName)
        val rgSourceType = view.findViewById<RadioGroup>(R.id.rgSourceType)
        val etSourceValue = view.findViewById<EditText>(R.id.etSourceValue)
        val btnAddOrUpdate = view.findViewById<TextView>(R.id.btnAddOrUpdatePlaylist)
        val btnApply = view.findViewById<TextView>(R.id.btnApplyPlaylist)
        val btnDelete = view.findViewById<TextView>(R.id.btnDeletePlaylist)
        val btnClose = view.findViewById<TextView>(R.id.btnClosePlaylistDialog)

        var profiles = getPlaylistProfiles().toMutableList()
        var selectedIndex = profiles.indexOfFirst { it.name == getSelectedPlaylistName() }.takeIf { it >= 0 } ?: 0

        fun refreshSpinner() {
            val names = profiles.map { it.name }
            spPlaylist.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
            if (selectedIndex in profiles.indices) spPlaylist.setSelection(selectedIndex)
        }

        fun fillFields(index: Int) {
            if (index !in profiles.indices) return
            val p = profiles[index]
            etPlaylistName.setText(p.name)
            etSourceValue.text?.clear()
            rgSourceType.check(if (p.type == "token") R.id.rbToken else R.id.rbUrl)
        }

        fun updateSourceHint() {
            etSourceValue.hint =
                if (rgSourceType.checkedRadioButtonId == R.id.rbToken) {
                    "Введите токен с сайта O.Portal"
                } else {
                    "Введите ссылку на плейлист"
                }
        }

        refreshSpinner()
        fillFields(selectedIndex)
        updateSourceHint()

        rgSourceType.setOnCheckedChangeListener { _, _ -> updateSourceHint() }

        spPlaylist.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedIndex = position
                fillFields(position)
                if (position in profiles.indices) {
                    setSelectedPlaylistName(profiles[position].name)
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        })

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(view)
            .create()

        btnAddOrUpdate.setOnClickListener {
            val name = etPlaylistName.text.toString().trim()
            val value = etSourceValue.text.toString().trim()
            val type = if (rgSourceType.checkedRadioButtonId == R.id.rbToken) "token" else "url"

            if (name.isBlank() || value.isBlank()) {
                Toast.makeText(this, "Заполните название и значение", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val profile = PlaylistProfile(name, type, value)
            val existing = profiles.indexOfFirst { it.name.equals(name, true) }
            if (existing >= 0) profiles[existing] = profile else profiles.add(profile)
            selectedIndex = profiles.indexOfFirst { it.name == name }
            savePlaylistProfiles(profiles)
            setSelectedPlaylistName(name)
            refreshSpinner()
            loadPlaylist(forceReload = true, showErrors = true)
            etSourceValue.text?.clear()
            Toast.makeText(this, "Плейлист сохранён", Toast.LENGTH_SHORT).show()
        }

        btnApply.setOnClickListener {
            if (selectedIndex !in profiles.indices) {
                Toast.makeText(this, "Выберите профиль плейлиста", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val selected = profiles[selectedIndex]
            val enteredName = etPlaylistName.text.toString().trim()
            val finalName = enteredName.ifBlank { selected.name }
            val enteredValue = etSourceValue.text.toString().trim()
            val value = enteredValue.ifBlank { selected.value }
            val type = if (rgSourceType.checkedRadioButtonId == R.id.rbToken) "token" else "url"

            if (value.isBlank()) {
                Toast.makeText(this, "Введите токен или URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val duplicate = profiles.indexOfFirst { indexProfile ->
                indexProfile.name.equals(finalName, true)
            }.takeIf { it >= 0 && it != selectedIndex } ?: -1
            if (duplicate >= 0) {
                Toast.makeText(this, "Профиль с таким названием уже существует", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            profiles[selectedIndex] = selected.copy(name = finalName, type = type, value = value)
            selectedIndex = profiles.indexOfFirst { it.name == finalName }.takeIf { it >= 0 } ?: selectedIndex
            savePlaylistProfiles(profiles)
            setSelectedPlaylistName(finalName)
            refreshSpinner()
            etSourceValue.text?.clear()
            loadPlaylist(forceReload = true, showErrors = true)
            Toast.makeText(this, "Применено", Toast.LENGTH_SHORT).show()
        }

        btnDelete.setOnClickListener {
            if (profiles.size <= 1) {
                Toast.makeText(this, "Должен остаться хотя бы один профиль", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (selectedIndex in profiles.indices) {
                profiles.removeAt(selectedIndex)
                selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                savePlaylistProfiles(profiles)
                setSelectedPlaylistName(profiles[selectedIndex].name)
                refreshSpinner()
                fillFields(selectedIndex)
                loadPlaylist(forceReload = true, showErrors = false)
            }
        }

        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
        dialog.window?.decorView?.let { applyGolosTypeface(it) }
        val dm = resources.displayMetrics
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setGravity(Gravity.CENTER)
            setLayout((dm.widthPixels * 0.82f).toInt(), (dm.heightPixels * 0.82f).toInt())
        }
    }

    private fun showEpgSelectionDialog() {
        val playlistEpgSources = extractEpgSourcesFromPlaylist(currentPlaylistText)
        val editableSources = getCustomEpgSources().ifEmpty { playlistEpgSources }
        availableEpgSources = editableSources
        if (availableEpgSources.isEmpty()) {
            Toast.makeText(this, "Программа передач отсутствует", Toast.LENGTH_SHORT).show()
            return
        }

        val view = layoutInflater.inflate(R.layout.dialog_epg_selection, null)
        val container = view.findViewById<LinearLayout>(R.id.epgContainer)
        val btnApply = view.findViewById<TextView>(R.id.btnApplyEpgDialog)
        val btnClose = view.findViewById<TextView>(R.id.btnCloseEpgDialog)
        val localSelection = selectedEpgSources.toMutableSet()
        var dialogRef: AlertDialog? = null

        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 10)
        }
        val btnEditLinks = TextView(this).apply {
            text = "Редактировать ссылки"
            setTextColor(Color.WHITE)
            setShadowLayer(2f, 0f, 0f, Color.parseColor("#80000000"))
            setPadding(20, 12, 20, 12)
            background = getDrawable(R.drawable.bg_watch_button)
            setOnClickListener {
                val et = EditText(this@MainActivity).apply {
                    setText(availableEpgSources.joinToString("\n"))
                    setTextColor(Color.WHITE)
                    setShadowLayer(2f, 0f, 0f, Color.parseColor("#80000000"))
                    setHintTextColor(Color.parseColor("#99FFFFFF"))
                    hint = "Каждая ссылка с новой строки"
                    minLines = 6
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Ссылки EPG")
                    .setView(et)
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Сохранить") { _, _ ->
                        val links = et.text.toString()
                            .lines()
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .distinct()
                        saveCustomEpgSources(links)
                        availableEpgSources = links
                        selectedEpgSources = links.toMutableSet()
                        saveSelectedEpgSources(selectedEpgSources)
                        Toast.makeText(this@MainActivity, "Ссылки EPG сохранены", Toast.LENGTH_SHORT).show()
                        dialogRef?.dismiss()
                        showEpgSelectionDialog()
                    }
                    .show()
            }
        }
        val btnRestoreLinks = TextView(this).apply {
            text = "Восстановить EPG"
            setTextColor(Color.WHITE)
            setShadowLayer(2f, 0f, 0f, Color.parseColor("#80000000"))
            setPadding(20, 12, 20, 12)
            background = getDrawable(R.drawable.bg_watch_button)
            setOnClickListener {
                clearCustomEpgSources()
                availableEpgSources = playlistEpgSources
                selectedEpgSources = availableEpgSources.toMutableSet()
                saveSelectedEpgSources(selectedEpgSources)
                Toast.makeText(this@MainActivity, "Настройки EPG восстановлены", Toast.LENGTH_SHORT).show()
                dialogRef?.dismiss()
                showEpgSelectionDialog()
            }
        }
        actionsRow.addView(btnEditLinks, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actionsRow.addView(btnRestoreLinks, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = 10
        })
        container.addView(actionsRow)

        val rows = mutableMapOf<String, TextView>()

        availableEpgSources.forEach { source ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(8, 8, 8, 8)
            }
            val cb = android.widget.CheckBox(this).apply {
                text = source
                setTextColor(Color.WHITE)
                setShadowLayer(2f, 0f, 0f, Color.parseColor("#80000000"))
                isChecked = localSelection.contains(source)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) localSelection.add(source) else localSelection.remove(source)
                }
            }
            val tvStatus = TextView(this).apply {
                setTextColor(Color.parseColor("#B3FFFFFF"))
                setShadowLayer(2f, 0f, 0f, Color.parseColor("#70000000"))
                textSize = 12f
                text = epgSourceStatus[source] ?: "Загрузка файла: 0%"
            }
            rows[source] = tvStatus
            row.addView(cb)
            row.addView(tvStatus)
            container.addView(row)
        }

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(view)
            .create()
        dialogRef = dialog

        btnApply.setOnClickListener {
            selectedEpgSources = localSelection
            saveSelectedEpgSources(selectedEpgSources)
            if (selectedEpgSources.isNotEmpty()) {
                synchronized(epgDataLock) { epgData.clear() }
                fetchEpgSources(selectedEpgSources.toList(), rows)
            }
            dialog.dismiss()
        }
        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
        dialog.window?.decorView?.let { applyGolosTypeface(it) }
        val dm = resources.displayMetrics
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.CENTER)
            setLayout((dm.widthPixels * 0.82f).toInt(), (dm.heightPixels * 0.82f).toInt())
        }
    }

    private fun loadPlaylist(forceReload: Boolean = false, showErrors: Boolean = false, autoPlay: Boolean = true) {
        thread {
            try {
                val playlistUrl = resolveCurrentPlaylistUrl()
                if (playlistUrl.isBlank()) {
                    handler.post {
                        tvEpg.text = "Откройте настройки и задайте токен или плейлист"
                        showUI()
                    }
                    return@thread
                }

                val content = URL(playlistUrl).readText()
                currentPlaylistText = content
                val parsedChannels = M3uParser.parse(content)
                val parsedEpgUrls = extractEpgSourcesFromPlaylist(content)

                handler.post {
                    channels.clear()
                    channels.addAll(parsedChannels)
                    applyCachedLogosToChannels()
                    availableEpgSources = parsedEpgUrls

                    val savedSelection = getSelectedEpgSources()
                    selectedEpgSources = if (savedSelection.isNotEmpty()) {
                        savedSelection.intersect(availableEpgSources.toSet()).toMutableSet()
                    } else {
                        availableEpgSources.toMutableSet()
                    }

                    if (shouldRefreshEpgNow()) {
                        synchronized(epgDataLock) { epgData.clear() }
                    }

                    if (channels.isNotEmpty() && autoPlay) {
                        currentChannelIndex = currentChannelIndex.coerceIn(channels.indices)
                        playChannel(forcePlay = true)
                    } else {
                        tvEpg.text = if (channels.isEmpty()) "Каналы не найдены в плейлисте" else "Выберите раздел на стартовой странице"
                    }

                    if (forceReload) {
                        Toast.makeText(this, "Плейлист обновлён", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("M3U", "Ошибка загрузки плейлиста", e)
                handler.post {
                    if (showErrors) {
                        AlertDialog.Builder(this)
                            .setTitle("Ошибка загрузки")
                            .setMessage("Не удалось загрузить плейлист по умолчанию. Проверьте токен или ссылку в настройках.")
                            .setPositiveButton("Открыть настройки") { _, _ -> showPlaylistSettingsDialog() }
                            .setNegativeButton("Закрыть", null)
                            .show()
                    }
                    tvEpg.text = "Ошибка загрузки плейлиста"
                    showUI()
                }
            }
        }
    }

    private fun fetchEpgSources(urls: List<String>, statusViews: Map<String, TextView> = emptyMap()) {
        if (epgFetchInProgress || urls.isEmpty()) return
        epgFetchInProgress = true
        thread {
            fun humanReadableEpgError(t: Throwable): String {
                return when {
                    t is UnknownHostException -> "Нет доступа к сети или DNS недоступен"
                    t.message?.contains("Unable to resolve host", ignoreCase = true) == true -> "Не удаётся определить адрес хоста"
                    t.message?.contains("timeout", ignoreCase = true) == true -> "Превышено время ожидания сети"
                    t.message?.contains("too large", ignoreCase = true) == true ||
                            t.message?.contains("safe limit", ignoreCase = true) == true -> "Файл EPG слишком большой"
                    else -> t.message?.take(120) ?: t.javaClass.simpleName
                }
            }
            fun applyEpgStatus(source: String, status: String) {
                epgSourceStatus[source] = status
                saveEpgStatusCache()
                handler.post { statusViews[source]?.text = status }
            }

            urls.forEach { sourceUrl ->
                applyEpgStatus(sourceUrl, "Загрузка файла: 0%")
                var parsed = false
                var lastError = "Неизвестная ошибка"
                val epgUrlVariants = buildEpgUrlCandidates(sourceUrl)
                candidates = epgUrlVariants

                for (candidateUrl in epgUrlVariants) {
                    try {
                        parseEpgUrlStreaming(
                            candidateUrl,
                            onDownload = { p -> applyEpgStatus(sourceUrl, "Загрузка файла: $p%") },
                            onUnpack = { p -> applyEpgStatus(sourceUrl, "Распаковка файла: $p%") },
                            onParse = { p -> applyEpgStatus(sourceUrl, "Чтение файла: $p%") }
                        )
                        applyEpgStatus(sourceUrl, "Чтение файла: 100%")
                        parsed = true
                        break
                    } catch (t: Throwable) {
                        Log.w("EPG", "Ошибка обработки EPG кандидата: $candidateUrl", t)
                        lastError = humanReadableEpgError(t)
                        when (t) {
                            is OutOfMemoryError -> applyEpgStatus(sourceUrl, "Файл EPG слишком большой")
                            is IOException -> if (t.message?.contains("too large", ignoreCase = true) == true ||
                                t.message?.contains("safe limit", ignoreCase = true) == true) {
                                applyEpgStatus(sourceUrl, "Файл EPG слишком большой")
                            }
                        }
                    }
                }

                if (!parsed) {
                    applyEpgStatus(sourceUrl, "Ошибка загрузки: $lastError")
                    Log.w("EPG", "Не удалось обработать источник EPG: $sourceUrl ($lastError)")
                }
            }

            runCatching {
                trimEpgCacheToWeek()
                saveEpgCache()
                saveCurrentEpgSourceFingerprint()
            }.onFailure { Log.e("EPG", "Ошибка сохранения EPG кэша", it) }
            handler.post {
                prefs.edit().putLong(PREF_EPG_LAST_REFRESH, System.currentTimeMillis()).apply()
                updateEpgDisplay()
                refreshLogo()
                epgFetchInProgress = false
            }
        }

    }

    private fun ensureEpgLoadedLazy() {
        if (selectedEpgSources.isEmpty() || epgFetchInProgress) return
        if (isEpgDataEmpty() || shouldRefreshEpgNow()) {
            fetchEpgSources(selectedEpgSources.toList())
        }
    }

    private fun parseEpgUrlStreaming(
        url: String,
        onDownload: (Int) -> Unit,
        onUnpack: (Int) -> Unit,
        onParse: (Int) -> Unit
    ) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", userAgent)
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 12_000
        conn.readTimeout = 25_000

        val compressedTotal = conn.contentLengthLong.coerceAtLeast(0L)

        conn.inputStream.use { raw ->
            val compressedLimited = SizeLimitedInputStream(raw, MAX_EPG_COMPRESSED_BYTES)
            val progressCompressed = ProgressInputStream(compressedLimited, compressedTotal, onDownload)
            val buffered = BufferedInputStream(progressCompressed)
            val pushback = PushbackInputStream(buffered, 2)

            val b1 = pushback.read()
            val b2 = pushback.read()
            if (b2 != -1) pushback.unread(b2)
            if (b1 != -1) pushback.unread(b1)
            val isGzip = (b1 and 0xFF == 0x1F) && (b2 and 0xFF == 0x8B)

            val xmlStream: InputStream = if (isGzip) {
                onUnpack(0)
                SizeLimitedInputStream(GZIPInputStream(pushback), MAX_EPG_UNPACKED_BYTES)
            } else {
                SizeLimitedInputStream(pushback, MAX_EPG_UNPACKED_BYTES)
            }

            xmlStream.use { stream ->
                parseEpgXml(stream, -1, onParse)
            }
            if (isGzip) onUnpack(100)
        }
    }

    private fun downloadEpgBytes(url: String, onProgress: (Int) -> Unit): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", userAgent)
        conn.connectTimeout = 12_000
        conn.readTimeout = 20_000
        val total = conn.contentLengthLong.coerceAtLeast(0L)
        val out = ByteArrayOutputStream()
        var readTotal = 0L
        var lastProgress = -1
        conn.inputStream.use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                readTotal += n
                if (readTotal > MAX_EPG_COMPRESSED_BYTES) {
                    throw IOException("EPG archive exceeded safe limit: $readTotal bytes")
                }
                if (total > 0) {
                    val progress = ((readTotal * 100) / total).toInt().coerceIn(0, 100)
                    if (progress != lastProgress) {
                        onProgress(progress)
                        lastProgress = progress
                    }
                }
            }
        }
        if (total <= 0) onProgress(100)
        return out.toByteArray()
    }

    private fun unpackEpgBytes(bytes: ByteArray, onProgress: (Int) -> Unit): ByteArray {
        if (bytes.size < 2) return bytes
        val isGzip = (bytes[0].toInt() and 0xFF == 0x1F) && (bytes[1].toInt() and 0xFF == 0x8B)
        if (!isGzip) {
            onProgress(100)
            return bytes
        }
        val input = ByteArrayInputStream(bytes)
        val countingInput = ProgressInputStream(input, bytes.size.toLong(), onProgress)
        val out = ByteArrayOutputStream()
        var unpackedSize = 0L
        GZIPInputStream(BufferedInputStream(countingInput)).use { gzip ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = gzip.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                unpackedSize += n
                if (unpackedSize > MAX_EPG_UNPACKED_BYTES) {
                    throw IOException("Unpacked EPG XML exceeded safe limit: $unpackedSize bytes")
                }
            }
        }
        onProgress(100)
        return out.toByteArray()
    }

    private fun parseEpgXml(inputStream: InputStream, totalBytes: Int, onProgress: (Int) -> Unit) {
        ProgressInputStream(inputStream, totalBytes.toLong(), onProgress).use { stream ->
            val parser = Xml.newPullParser()
            parser.setInput(stream, "UTF-8")
            var eventType = parser.eventType
            var tempId = ""
            val sdf = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "channel" -> tempId = parser.getAttributeValue(null, "id") ?: ""
                        "icon" -> {
                            val src = parser.getAttributeValue(null, "src")
                            channels.filter {
                                it.tvgId.equals(tempId, true) || it.tvgName.equals(tempId, true) || it.name.equals(tempId, true)
                            }.forEach { it.logoFromEpg = src }
                        }

                        "programme" -> {
                            val chId = parser.getAttributeValue(null, "channel")?.lowercase()?.trim() ?: ""
                            val start = try { sdf.parse(parser.getAttributeValue(null, "start"))?.time ?: 0L } catch (_: Exception) { 0L }
                            val stop = try { sdf.parse(parser.getAttributeValue(null, "stop"))?.time ?: 0L } catch (_: Exception) { 0L }
                            var title = ""
                            while (!(parser.next() == XmlPullParser.END_TAG && parser.name == "programme")) {
                                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "title") {
                                    title = parser.nextText()
                                }
                            }
                            if (chId.isNotEmpty()) {
                                synchronized(epgDataLock) {
                                    val bucket = epgData.getOrPut(chId) { mutableListOf() }
                                    if (bucket.size < MAX_PROGRAMS_PER_CHANNEL) {
                                        bucket.add(Program(title, start, stop))
                                    }
                                }
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        }
    }

    private fun isArchiveAvailable(channel: Channel, program: Program): Boolean {
        if (channel.catchupDays <= 0 || channel.catchupSource.isNullOrBlank()) return false
        val now = System.currentTimeMillis()
        val maxDepthMs = channel.catchupDays * 24L * 60L * 60L * 1000L
        return program.start in 1..now && (now - program.start) <= maxDepthMs
    }

    private fun buildArchiveUrl(channel: Channel, program: Program): String? {
        val source = channel.catchupSource?.trim().orEmpty()
        if (source.isBlank()) return null
        val startUnix = (program.start / 1000L).coerceAtLeast(0L)
        val nowUnix = System.currentTimeMillis() / 1000L
        val endUnix = (program.stop / 1000L).coerceAtLeast((nowUnix + 6 * 60 * 60).coerceAtLeast(startUnix))
        val duration = (endUnix - startUnix).coerceAtLeast(0L)
        // offset в секундах назад: текущее unix-время минус unix-время начала программы
        val offset = (nowUnix - startUnix).coerceAtLeast(0L)
        var resolved = source
        val replacements = mapOf(
            "start" to startUnix.toString(),
            "end" to endUnix.toString(),
            "utcstart" to startUnix.toString(),
            "utcend" to endUnix.toString(),
            "offset" to offset.toString(),
            "dur" to duration.toString()
        )
        replacements.forEach { (key, value) ->
            resolved = resolved
                .replace("\${$key}", value)
                .replace("{$key}", value)
                .replace("$$key", value)
        }
        return resolved
    }

    private fun playArchiveProgram(channel: Channel, program: Program) {
        val archiveUrl = buildArchiveUrl(channel, program)
        if (archiveUrl.isNullOrBlank()) {
            Toast.makeText(this, "Не удалось сформировать ссылку архива", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            homePanel.visibility = View.GONE
            val shouldUseSoftware = !preferGpuDecoding
            // Start in strict IDR mode to avoid visual artifacts (macroblocking) on some TS streams.
            // Non-IDR parsing is enabled only by startup fallback when no first frame appears.
            allowNonIdrKeyframes = false
            if (softwareDecoderMode != shouldUseSoftware) {
                stopPlayback()
                softwareDecoderMode = shouldUseSoftware
                setupPlayer(preferSoftwareDecoder = shouldUseSoftware)
            }
            mediaPlayer?.stop()
            lastRequestedPlaybackUrl = archiveUrl
            mediaPlayer?.setMediaItem(buildMediaItem(archiveUrl))
            mediaPlayer?.prepare()
            mediaPlayer?.play()
            handler.postDelayed(startupFrameTimeoutRunnable, 8000L)
            lastPlaybackPositionMs = -1L
            lastProgressWallClockMs = System.currentTimeMillis()
            handler.removeCallbacks(playbackFreezeWatchdogRunnable)
            handler.postDelayed(playbackFreezeWatchdogRunnable, 4000L)
            isPlaybackPaused = false
            isArchivePlayback = true
            currentArchiveProgram = program
            archiveStreamStartMs = program.start
            btnPlayPause.setImageResource(R.drawable.ic_pause)
            tvChannelName.text = "${currentChannelIndex + 1}. ${channel.name}"
            val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(program.start))
            tvEpg.text = "Архив от $stamp - ${program.title}"
            showUI()
        }.onFailure { e ->
            showPlaybackFailureAndReturn(archiveUrl, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun playChannel(forcePlay: Boolean = false) {
        runCatching {
            val ch = channels.getOrNull(currentChannelIndex) ?: return
            homePanel.visibility = View.GONE
            val shouldUseSoftware = !preferGpuDecoding
            // Start in strict IDR mode to avoid visual artifacts (macroblocking) on some TS streams.
            // Non-IDR parsing is enabled only by startup fallback when no first frame appears.
            allowNonIdrKeyframes = false
            if (softwareDecoderMode != shouldUseSoftware) {
                stopPlayback()
                softwareDecoderMode = shouldUseSoftware
                setupPlayer(preferSoftwareDecoder = shouldUseSoftware)
            }
            mediaPlayer?.stop()
            lastRequestedPlaybackUrl = ch.url
            firstFrameRendered = false
            handler.removeCallbacks(startupFrameTimeoutRunnable)
            handler.removeCallbacks(playbackFreezeWatchdogRunnable)
            retriedWithoutAudio = false
            retriedWithAlternateDecoder = false
            startupWaitSinceMs = 0L
            enableAudioTrack()
            applyUnlimitedVideoConstraints()

            mediaPlayer?.setMediaItem(buildMediaItem(ch.url))
            mediaPlayer?.prepare()
            mediaPlayer?.play()
            handler.postDelayed(startupFrameTimeoutRunnable, 8000L)
            isPlaybackPaused = false
            isArchivePlayback = false
            currentArchiveProgram = null
            archiveStreamStartMs = 0L
            btnPlayPause.setImageResource(R.drawable.ic_pause)

            tvChannelName.text = "${currentChannelIndex + 1}. ${ch.name}"
            prefs.edit().putInt(PREF_LAST_CHANNEL, currentChannelIndex).apply()
            ensureEpgLoadedLazy()
            refreshLogo()
            updateEpgDisplay()
            showUI()
        }.onFailure { e ->
            Log.e("PLAYER", "Ошибка воспроизведения канала", e)
            showPlaybackFailureAndReturn(lastRequestedPlaybackUrl, e.message ?: e.javaClass.simpleName)
            showUI()
        }
    }


    private fun buildMediaItem(url: String): MediaItem {
        val uri = Uri.parse(url)
        val mime = if (url.contains(".m3u8", ignoreCase = true) || url.contains(".m3u", ignoreCase = true)) "application/x-mpegURL" else null
        val builder = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(mime)

        if (url.contains("/only4/", ignoreCase = true)) {
            builder.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(16_000)
                    .setMinPlaybackSpeed(0.98f)
                    .setMaxPlaybackSpeed(1.03f)
                    .build()
            )
        }

        return builder.build()
    }

    private fun showCenterError(message: String, durationMs: Long = 2200L) {
        tvReloadingStatus.text = message
        tvReloadingStatus.setBackgroundResource(R.drawable.bg_reload_toast)
        tvReloadingStatus.visibility = View.VISIBLE
        handler.postDelayed({
            tvReloadingStatus.visibility = View.GONE
            tvReloadingStatus.text = "Обновление трансляции..."
        }, durationMs)
    }

    private fun showPlaybackFailureAndReturn(url: String, error: String) {
        val message = "Ошибка воспроизведения\n$error\n$url\nВозврат к прямому эфиру"
        showCenterError(message, 3000L)
        handler.removeCallbacks(returnToLiveRunnable)
        handler.postDelayed(returnToLiveRunnable, 3000L)
    }

    private fun updateEpgDisplay() {
        if (isArchivePlayback) {
            val channel = channels.getOrNull(currentChannelIndex)
            val playbackTime = archiveStreamStartMs + (mediaPlayer?.currentPosition ?: 0L)
            val program = if (channel != null && playbackTime > 0L) {
                getProgramsForDisplay(channel).find { playbackTime in it.start until it.stop } ?: currentArchiveProgram
            } else {
                currentArchiveProgram
            }
            currentArchiveProgram = program
            if (program != null) {
                val stamp = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(program.start))
                tvEpg.text = "Архив от $stamp - ${program.title}"
            } else {
                tvEpg.text = "Архив"
            }
            updateTimelineUi()
            return
        }
        val ch = channels.getOrNull(currentChannelIndex) ?: return
        val now = System.currentTimeMillis()
        val programs = getProgramsForDisplay(ch)

        val cur = programs.find { now in it.start until it.stop }
        tvEpg.text = cur?.let {
            "${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.start))} - ${it.title}"
        } ?: "Загрузка программы..."
        updateTimelineUi()
    }

    private fun loadLogoWithGlide(url: String?, target: ImageView) {
        val glideUrl = if (url.isNullOrEmpty()) null else GlideUrl(
            url,
            LazyHeaders.Builder().addHeader("User-Agent", userAgent).build()
        )
        Glide.with(this).load(glideUrl).placeholder(R.mipmap.ic_launcher).into(target)
    }

    private fun refreshLogo() {
        val ch = channels.getOrNull(currentChannelIndex) ?: return
        loadLogoWithGlide(ch.logoFromEpg ?: ch.logoFromPlaylist, ivLogo)
    }

    private fun startClockUpdater() {
        handler.post(object : Runnable {
            override fun run() {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                tvSystemTime.text = time
                tvHomeSystemTime.text = time
                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun setupPlayer(preferSoftwareDecoder: Boolean = false) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(25_000)
            .setDefaultRequestProperties(
                mapOf(
                    "Accept" to "*/*",
                    "Origin" to "https://o.avff.ru",
                    "Referer" to "https://o.avff.ru/",
                    "Connection" to "keep-alive"
                )
            )

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                10_000,
                45_000,
                2_500,
                5_000
            )
            .build()

        val codecSelector = if (preferSoftwareDecoder) {
            MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                MediaCodecSelector.DEFAULT
                    .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                    .sortedBy { it.hardwareAccelerated }
            }
        } else {
            MediaCodecSelector.DEFAULT
        }

        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(codecSelector)

        val tsFlags = if (allowNonIdrKeyframes) {
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
                    DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
        } else {
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        }

        val hlsMediaSourceFactory = HlsMediaSource.Factory(httpFactory)
            .setAllowChunklessPreparation(false)
            .setExtractorFactory(DefaultHlsExtractorFactory(tsFlags, true))

        trackSelector = DefaultTrackSelector(this).apply {
            setParameters(
                buildUponParameters()
                    .clearVideoSizeConstraints()
                    .setMaxVideoBitrate(Int.MAX_VALUE)
                    .setExceedVideoConstraintsIfNecessary(true)
                    .setForceHighestSupportedBitrate(false)
                    .setForceLowestBitrate(false)
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .setAllowAudioMixedMimeTypeAdaptiveness(true)
            )
        }

        mediaPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(trackSelector!!)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(hlsMediaSourceFactory)
            .build()
            .also { player ->
                findViewById<PlayerView>(R.id.videoLayout).player = player
                player.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onRenderedFirstFrame() {
                        firstFrameRendered = true
                        lastPlaybackPositionMs = player.currentPosition
                        lastProgressWallClockMs = System.currentTimeMillis()
                        handler.removeCallbacks(startupFrameTimeoutRunnable)
                        handler.removeCallbacks(playbackFreezeWatchdogRunnable)
                        startupWaitSinceMs = 0L
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        logSelectedPlaybackFormats(tracks)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        handler.post {
                            handler.removeCallbacks(startupFrameTimeoutRunnable)
                            handler.removeCallbacks(playbackFreezeWatchdogRunnable)
                            if (shouldRetryWithoutAudio(error)) {
                                showCenterError("Аудио MPEG не поддерживается устройством, продолжаем без звука", 2500L)
                                retryCurrentStreamWithoutAudio()
                            } else {
                                showPlaybackFailureAndReturn(lastRequestedPlaybackUrl, error.message ?: "PlaybackException")
                            }
                        }
                    }
                })
            }
    }


    private fun shouldRetryWithoutAudio(error: PlaybackException): Boolean {
        if (retriedWithoutAudio || lastRequestedPlaybackUrl.isBlank()) return false
        val text = ((error.message ?: "") + " " + (error.cause?.message ?: "")).lowercase(Locale.ROOT)
        return text.contains("audio") || text.contains("mpga") || text.contains("mpeg") || text.contains("decoder")
    }

    private fun logSelectedPlaybackFormats(tracks: Tracks) {
        for (group in tracks.groups) {
            if (!group.isSelected) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSelected(i)) continue
                val format = group.getTrackFormat(i)
                val type = when (format.sampleMimeType?.substringBefore('/')) {
                    "video" -> "video"
                    "audio" -> "audio"
                    else -> "track"
                }
                Log.i(
                    "PLAYER_FORMAT",
                    "$type codec=${format.codecs ?: format.sampleMimeType.orEmpty()} " +
                            "mime=${format.sampleMimeType.orEmpty()} bitrate=${format.bitrate} " +
                            "size=${format.width}x${format.height} url=$lastRequestedPlaybackUrl"
                )
            }
        }
    }

    private fun retryCurrentStreamWithoutAudio() {
        val url = lastRequestedPlaybackUrl
        if (url.isBlank()) return
        retriedWithoutAudio = true
        disableAudioTrack()
        mediaPlayer?.stop()
        mediaPlayer?.setMediaItem(buildMediaItem(url))
        mediaPlayer?.prepare()
        mediaPlayer?.playWhenReady = true
        firstFrameRendered = false
        startupWaitSinceMs = 0L
        handler.removeCallbacks(startupFrameTimeoutRunnable)
        handler.removeCallbacks(playbackFreezeWatchdogRunnable)
        handler.postDelayed(startupFrameTimeoutRunnable, 8000L)
    }

    private fun disableAudioTrack() {
        trackSelector?.setParameters(
            trackSelector?.buildUponParameters()?.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                ?: return
        )
    }

    private fun enableAudioTrack() {
        trackSelector?.setParameters(
            trackSelector?.buildUponParameters()?.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                ?: return
        )
    }

    private fun applyUnlimitedVideoConstraints() {
        trackSelector?.setParameters(
            trackSelector?.buildUponParameters()
                ?.clearVideoSizeConstraints()
                ?.setMaxVideoBitrate(Int.MAX_VALUE)
                ?.setExceedVideoConstraintsIfNecessary(true)
                ?.setForceHighestSupportedBitrate(false)
                ?.setForceLowestBitrate(false)
                ?: return
        )
    }

    private fun switchDecoderModeAndRestart() {
        if (lastRequestedPlaybackUrl.isBlank()) return
        softwareDecoderMode = !softwareDecoderMode
        restartCurrentStream(recreatePlayer = true)
    }

    private fun restartCurrentStream(recreatePlayer: Boolean) {
        val url = lastRequestedPlaybackUrl
        if (url.isBlank()) return
        if (recreatePlayer) {
            stopPlayback()
            setupPlayer(preferSoftwareDecoder = softwareDecoderMode)
        } else {
            mediaPlayer?.stop()
        }
        mediaPlayer?.setMediaItem(buildMediaItem(url))
        mediaPlayer?.prepare()
        mediaPlayer?.playWhenReady = true
        firstFrameRendered = false
        startupWaitSinceMs = 0L
        handler.removeCallbacks(startupFrameTimeoutRunnable)
        handler.removeCallbacks(playbackFreezeWatchdogRunnable)
        handler.postDelayed(startupFrameTimeoutRunnable, 8000L)
        lastPlaybackPositionMs = -1L
        lastProgressWallClockMs = System.currentTimeMillis()
        handler.postDelayed(playbackFreezeWatchdogRunnable, 4000L)
    }

    private fun showUI() {
        topInfoPanel.visibility = View.VISIBLE
        controlsPanel.visibility = View.VISIBLE
        sbTimeline.isEnabled = true
        handler.removeCallbacks(hideUiRunnable)
        handler.postDelayed(hideUiRunnable, 5000)
    }

    private fun hideUI() {
        topInfoPanel.visibility = View.GONE
        controlsPanel.visibility = View.GONE
        sbTimeline.isEnabled = false
        hideSystemUI()
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    private fun processChannelNumberInput() {
        val idx = inputNumber.toIntOrNull()?.minus(1) ?: -1
        if (idx in channels.indices) {
            currentChannelIndex = idx
            playChannel(forcePlay = true)
        }
        inputNumber = ""
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when {
            keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                inputNumber += (keyCode - KeyEvent.KEYCODE_0).toString()
                handler.removeCallbacks(channelSwitchRunnable)
                showUI()
                handler.postDelayed(channelSwitchRunnable, 1500)
                return true
            }

            keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (channels.isNotEmpty()) {
                    currentChannelIndex = (currentChannelIndex + 1) % channels.size
                    playChannel(forcePlay = true)
                }
                return true
            }

            keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (channels.isNotEmpty()) {
                    currentChannelIndex = (currentChannelIndex - 1 + channels.size) % channels.size
                    playChannel(forcePlay = true)
                }
                return true
            }

            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (controlsPanel.visibility == View.VISIBLE && isArchivePlayback && sbTimeline.isEnabled) {
                    sbTimeline.progress = (sbTimeline.progress + 20).coerceAtMost(1000)
                    return true
                }
                showChannelList()
                return true
            }

            keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (controlsPanel.visibility == View.VISIBLE && isArchivePlayback && sbTimeline.isEnabled) {
                    sbTimeline.progress = (sbTimeline.progress - 20).coerceAtLeast(0)
                    return true
                }
                showCurrentChannelSchedule()
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = mDetector.onTouchEvent(e) || super.onTouchEvent(e)

    override fun onStart() {
        super.onStart()
        startEpgTicker()
        handler.post(timelineTickerRunnable)
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val currentVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
        preferGpuDecoding = prefs.getBoolean(PREF_USE_GPU_DECODER, true)
        softwareDecoderMode = !preferGpuDecoding

        val savedVersion = prefs.getLong(PREF_APP_VERSION_CODE, -1L)
        val versionChanged = savedVersion != currentVersion
        if (versionChanged) {
            prefs.edit().putLong(PREF_APP_VERSION_CODE, currentVersion).apply()
        }
        if (mediaPlayer == null) {
            setupPlayer(preferSoftwareDecoder = softwareDecoderMode)
            if (channels.isNotEmpty() && homePanel.visibility != View.VISIBLE) {
                playChannel(forcePlay = true)
            }
        } else {
            findViewById<PlayerView>(R.id.videoLayout).player = mediaPlayer
        }
        if ((shouldReloadStreamOnStart || versionChanged) && channels.isNotEmpty() && homePanel.visibility != View.VISIBLE) {
            playChannel(forcePlay = true)
            shouldReloadStreamOnStart = false
        }
        val hasIncompleteEpgProgress = epgSourceStatus.values.any { it.contains("Загрузка файла") || it.contains("Распаковка файла") || it.contains("Чтение файла") }
        if (versionChanged || hasIncompleteEpgProgress) ensureEpgLoadedLazy()
        if (mediaPlayer != null && isPlaybackPaused) {
            mediaPlayer?.play()
            handler.postDelayed(startupFrameTimeoutRunnable, 8000L)
            isPlaybackPaused = false
            btnPlayPause.setImageResource(R.drawable.ic_pause)
        }
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(epgTickerRunnable)
        handler.removeCallbacks(timelineTickerRunnable)
        mediaPlayer?.pause()
        shouldReloadStreamOnStart = true
    }

    override fun onDestroy() {
        handler.removeCallbacks(epgTickerRunnable)
        handler.removeCallbacks(timelineTickerRunnable)
        stopPlayback()
        super.onDestroy()
    }

    private fun updateTimelineUi() {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val p = if (isArchivePlayback) currentArchiveProgram else channels.getOrNull(currentChannelIndex)?.let { ch ->
            getProgramsForDisplay(ch).find { System.currentTimeMillis() in it.start until it.stop }
        }
        if (p == null) {
            tvCurrentTime.text = "--:--"
            tvProgramEndTime.text = "--:--"
            if (!timelineUserSeeking) sbTimeline.progress = 0
            return
        }
        tvCurrentTime.text = fmt.format(Date(p.start))
        tvProgramEndTime.text = fmt.format(Date(p.stop))
        if (!timelineUserSeeking) {
            val currentMs = if (isArchivePlayback) archiveStreamStartMs + (mediaPlayer?.currentPosition ?: 0L) else System.currentTimeMillis()
            val progress = (((currentMs - p.start).toDouble() / (p.stop - p.start).coerceAtLeast(1L).toDouble()) * 1000.0).toInt().coerceIn(0, 1000)
            sbTimeline.progress = progress
        }
    }

    private fun seekArchiveTo(targetProgramTimeMs: Long) {
        val p = currentArchiveProgram ?: return
        val previous = mediaPlayer?.currentPosition ?: 0L
        val offset = (targetProgramTimeMs - p.start).coerceAtLeast(0L)
        mediaPlayer?.seekTo(offset)
        val deltaMin = kotlin.math.abs(((offset - previous) / 60_000L).toInt())
        tvEpg.text = "Перемотка архива на $deltaMin минут"
        handler.removeCallbacks(restoreEpgRunnable)
        handler.postDelayed(restoreEpgRunnable, 2200L)
        updateTimelineUi()
    }

    private fun startEpgTicker() {
        handler.removeCallbacks(epgTickerRunnable)
        handler.post(epgTickerRunnable)
    }

    private fun stopPlayback() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        trackSelector = null
        handler.removeCallbacks(startupFrameTimeoutRunnable)
        handler.removeCallbacks(playbackFreezeWatchdogRunnable)

    }

    private fun showLockedMessage() {
        tvEpg.text = "Управление свайпами заблокировано! Разблокируйте для переключения канала!"
        showUI()
        handler.removeCallbacks(restoreEpgRunnable)
        handler.postDelayed(restoreEpgRunnable, 2000)
    }

    private fun extractEpgSourcesFromPlaylist(content: String): List<String> {
        if (!content.contains("x-tvg-url=\"")) return emptyList()
        return content.substringAfter("x-tvg-url=\"", "")
            .substringBefore("\"")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun buildEpgUrlCandidates(url: String): List<String> {
        val clean = url.trim()
        if (clean.isBlank()) return emptyList()
        val result = linkedSetOf(clean)
        if (!clean.endsWith(".xml.gz", true) && !clean.endsWith(".xml", true)) {
            result += "$clean.xml.gz"
            result += "$clean.xml"
            result += clean.trimEnd('/') + "/xmltv.xml.gz"
            result += clean.trimEnd('/') + "/epg.xml.gz"
        }
        return result.toList()
    }

    private fun shouldDailyRefreshEpg(): Boolean {
        val last = prefs.getLong(PREF_EPG_LAST_REFRESH, 0L)
        if (last == 0L) return true
        val next = nextDayAtThree(last)
        return System.currentTimeMillis() >= next
    }


    private fun shouldRefreshEpgNow(): Boolean {
        if (shouldDailyRefreshEpg()) return true
        return getEpgSourceFingerprint() != buildEpgSourceFingerprint(selectedEpgSources.toList())
    }

    private fun buildEpgSourceFingerprint(sources: List<String>): String =
        sources.map { it.trim() }.filter { it.isNotBlank() }.sorted().joinToString("|")

    private fun getEpgSourceFingerprint(): String = prefs.getString(PREF_EPG_SOURCES_FINGERPRINT, "") ?: ""

    private fun saveCurrentEpgSourceFingerprint() {
        prefs.edit().putString(PREF_EPG_SOURCES_FINGERPRINT, buildEpgSourceFingerprint(selectedEpgSources.toList())).apply()
    }

    private fun trimEpgCacheToWeek() {
        val now = System.currentTimeMillis()
        val weekEnd = now + EPG_KEEP_DAYS * 24L * 60L * 60L * 1000L
        synchronized(epgDataLock) {
            epgData.entries.forEach { (_, programs) ->
                programs.removeAll { it.stop < now || it.start > weekEnd }
                programs.sortBy { it.start }
            }
            epgData.entries.removeAll { it.value.isEmpty() }
        }
    }
    private fun nextDayAtThree(fromMillis: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = fromMillis }
        cal.set(Calendar.HOUR_OF_DAY, 3)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= fromMillis) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun restoreLastChannel() {
        currentChannelIndex = prefs.getInt(PREF_LAST_CHANNEL, 0)
    }

    private fun ensureDefaultPlaylistProfile() {
        val profiles = getPlaylistProfiles().toMutableList()
        if (profiles.isEmpty()) {
            profiles += PlaylistProfile("По умолчанию", "token", "")
            savePlaylistProfiles(profiles)
            setSelectedPlaylistName("По умолчанию")
        }
    }

    private fun getPlaylistProfiles(): List<PlaylistProfile> {
        val raw = prefs.getString(PREF_PLAYLISTS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        PlaylistProfile(
                            name = o.optString("name"),
                            type = o.optString("type", "url"),
                            value = o.optString("value")
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun savePlaylistProfiles(profiles: List<PlaylistProfile>) {
        val arr = JSONArray()
        profiles.forEach {
            arr.put(
                JSONObject().apply {
                    put("name", it.name)
                    put("type", it.type)
                    put("value", it.value)
                }
            )
        }
        prefs.edit().putString(PREF_PLAYLISTS, arr.toString()).apply()
    }

    private fun resolveCurrentPlaylistUrl(): String {
        val selected = getSelectedPlaylistName()
        val profile = getPlaylistProfiles().firstOrNull { it.name == selected } ?: getPlaylistProfiles().firstOrNull()
        return when (profile?.type) {
            "token" -> {
                val token = profile.value.trim()
                if (token.isBlank()) "" else "$TOKEN_PREFIX$token$TOKEN_SUFFIX"
            }

            "url" -> profile.value.trim()
            else -> ""
        }
    }

    private fun getSelectedPlaylistName(): String = prefs.getString(PREF_SELECTED_PLAYLIST, "") ?: ""

    private fun setSelectedPlaylistName(name: String) {
        prefs.edit().putString(PREF_SELECTED_PLAYLIST, name).apply()
    }

    private fun getSelectedEpgSources(): MutableSet<String> =
        prefs.getStringSet(PREF_SELECTED_EPG, emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun saveSelectedEpgSources(sources: Set<String>) {
        prefs.edit().putStringSet(PREF_SELECTED_EPG, sources).apply()
    }

    private fun getCustomEpgSources(): List<String> {
        val raw = prefs.getString(PREF_CUSTOM_EPG_SOURCES, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val value = arr.optString(i).trim()
                    if (value.isNotBlank()) add(value)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveCustomEpgSources(sources: List<String>) {
        val arr = JSONArray()
        sources.forEach { arr.put(it) }
        prefs.edit().putString(PREF_CUSTOM_EPG_SOURCES, arr.toString()).apply()
    }

    private fun clearCustomEpgSources() {
        prefs.edit().remove(PREF_CUSTOM_EPG_SOURCES).apply()
    }

    private fun saveEpgStatusCache() {
        val obj = JSONObject()
        epgSourceStatus.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString(PREF_EPG_STATUS, obj.toString()).apply()
    }

    private fun loadEpgStatusCache() {
        val raw = prefs.getString(PREF_EPG_STATUS, "{}") ?: "{}"
        try {
            val obj = JSONObject(raw)
            epgSourceStatus.clear()
            obj.keys().forEach { k -> epgSourceStatus[k] = obj.optString(k) }
        } catch (_: Exception) {
        }
    }

    private fun saveEpgCache() {
        val cache = JSONObject()
        synchronized(epgDataLock) {
            epgData.forEach { (channelId, programs) ->
                val arr = JSONArray()
                programs.sortedBy { it.start }.take(200).forEach { p ->
                    arr.put(JSONObject().apply {
                        put("title", p.title)
                        put("start", p.start)
                        put("stop", p.stop)
                    })
                }
                cache.put(channelId, arr)
            }
        }
        prefs.edit().putString(PREF_EPG_CACHE, cache.toString()).apply()
        saveLogoCacheToPrefs()
        saveEpgStatusCache()
    }

    private fun loadEpgCache() {
        loadEpgStatusCache()
        loadLogoCacheFromPrefs()
        val raw = prefs.getString(PREF_EPG_CACHE, "{}") ?: "{}"
        try {
            val obj = JSONObject(raw)
            synchronized(epgDataLock) { epgData.clear() }
            obj.keys().forEach { key ->
                val arr = obj.optJSONArray(key) ?: JSONArray()
                val list = mutableListOf<Program>()
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    list += Program(
                        title = p.optString("title"),
                        start = p.optLong("start"),
                        stop = p.optLong("stop")
                    )
                }
                synchronized(epgDataLock) { epgData[key] = list }
            }
        } catch (_: Exception) {
            cachedLogos.clear()
        }
    }

    private fun applyCachedLogosToChannels() {
        if (cachedLogos.isEmpty()) return
        channels.forEach { channel ->
            val keys = listOf(channel.tvgId, channel.tvgName, channel.name)
            val logo = keys
                .mapNotNull { it?.lowercase()?.trim() }
                .firstNotNullOfOrNull { cachedLogos[it] }
            if (!logo.isNullOrBlank()) channel.logoFromEpg = logo
        }
    }

    private fun saveLogoCacheToPrefs() {
        val obj = JSONObject()
        channels.forEach { ch ->
            val logo = ch.logoFromEpg ?: return@forEach
            val keys = listOf(ch.tvgId, ch.tvgName, ch.name)
            keys.forEach { key ->
                val normalized = key?.lowercase()?.trim().orEmpty()
                if (normalized.isNotBlank()) obj.put(normalized, logo)
            }
        }
        prefs.edit().putString(PREF_LOGO_CACHE, obj.toString()).apply()
    }

    private fun loadLogoCacheFromPrefs() {
        val raw = prefs.getString(PREF_LOGO_CACHE, "{}") ?: "{}"
        try {
            val obj = JSONObject(raw)
            cachedLogos.clear()
            obj.keys().forEach { key ->
                val url = obj.optString(key)
                if (url.isNotBlank()) cachedLogos[key] = url
            }
        } catch (_: Exception) {
            cachedLogos.clear()
        }
    }

    private data class ChannelItemViewHolder(
        val tvName: TextView,
        val tvEpgItem: TextView,
        val ivLogoItem: ImageView,
        val btnWatch: TextView
    )
}

