package com.twnkos.oportal

import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.widget.PopupWindow
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.util.Log
import android.util.TypedValue
import android.util.Xml
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.graphics.Paint
import android.graphics.Rect
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.view.ScaleGestureDetector
import android.content.Intent
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
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
import androidx.core.view.GestureDetectorCompat
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.UdpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.SimpleTimeZone
import java.util.TimeZone
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
    val groupTitle: String? = null,
    val catchupDays: Int = 0,
    val catchupSource: String? = null,
    var logoFromEpg: String? = null
)

data class Program(val title: String, val start: Long, val stop: Long, val desc: String = "")

data class PlaylistProfile(
    val name: String,
    val type: String, // token|url
    val value: String,
    val enabled: Boolean = true
)

class MainActivity : AppCompatActivity() {
    private enum class PlayerOpenReason { CHANNEL_CLICK, LIVE_RETRY, RECOVERY }

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
    /** Ignore auto-recovery until this time — prevents reload loops after start/recover. */
    private var stallWatchdogGraceUntilMs = 0L
    private var playbackRecoveryActive = false
    private var playbackRecoveryStartedAtMs = 0L
    private var playbackRecoveryAttemptCount = 0
    private var lastPlaybackStallReason = ""
    private val playbackRecoveryRetryRunnable = Runnable { attemptPlaybackRecoveryStep() }
    private var playerEventListener: androidx.media3.common.Player.Listener? = null
    private var playerAnalyticsListener: AnalyticsListener? = null
    private var forceFreshPlayerSession = false
    private var lastReleasedPlayerId: Int? = null
    private lateinit var mDetector: GestureDetectorCompat
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var videoPinchScale = 1f
    private val aspectRatioLabels = listOf("Автоматически", "Вписать в экран", "16:9", "Растянуть", "Обрезать")
    private var aspectRatioIndex = 0

    private var channelListDialog: AlertDialog? = null

    // UI элементы
    private lateinit var controlsPanel: View
    private lateinit var topInfoPanel: View
    private lateinit var topGradientOverlay: View
    private lateinit var tvEpg: TextView
    private lateinit var tvChannelName: TextView
    private lateinit var tvSystemTime: TextView
    private lateinit var tvHomeSystemTime: TextView
    private lateinit var tvHomeAppTitle: TextView
    private lateinit var tvHomeBreadcrumbArrow: TextView
    private lateinit var tvHomeBreadcrumbPill: TextView
    private lateinit var tvHomeBreadcrumbArrow2: TextView
    private lateinit var tvHomeBreadcrumbPill2: TextView
    private lateinit var tvHomeStartTitle: TextView
    private lateinit var tvHomeStartSubtitle: TextView
    private lateinit var homeStartCenterBlock: View
    private lateinit var tvHomeWelcome: TextView
    private lateinit var tvPlaylistPageTitle: TextView
    private lateinit var tvPlaylistPageSubtitle: TextView
    private lateinit var homePlaylistTilesPanel: View
    private lateinit var gvHomeChannelList: GridView
    private lateinit var rvHomeTiles: RecyclerView
    private lateinit var tvHomeCategoryBack: TextView
    private var homeCategoryBackHandler: (() -> Unit)? = null
    private lateinit var liveStatusBadge: View
    private lateinit var tvLiveStatusText: TextView
    private lateinit var liveStatusDot: View
    private lateinit var btnLock: ImageButton
    private lateinit var btnAspectRatio: ImageButton
    private lateinit var btnCcSubtitles: TextView
    private lateinit var btnAudioTrack: TextView
    private lateinit var btnHdQuality: TextView
    private var availableQualities: List<QualityOption> = emptyList()
    private var currentQualityIndex: Int = -1 // -1 = Авто (master URL)
    private var availableSubtitleTracks: List<SubtitleOption> = emptyList()
    private var selectedSubtitleIndex: Int = -1 // -1 = Выкл
    private var availableSubtitleUrl: String? = null
    private var subtitlesEnabled: Boolean = false
    private var availableAudioTracks: List<AudioOption> = emptyList()
    private var selectedAudioIndex: Int = -1 // -1 = Авто
    private var qualityFetchToken: Int = 0
    private var manualQualityOverrideUrl: String? = null
    private var manualQualityOverrideChannelIndex: Int = -1
    private var masterStreamUrl: String? = null
    private var playerTrackMenu: PopupWindow? = null
    private lateinit var playerSubtitlesOverlay: FrameLayout
    private lateinit var tvPlayerSubtitles: TextView
    private var suppressAutoPlayerUiOnce: Boolean = false
    private var tvEpgLoadStatus: TextView? = null

    data class QualityOption(val label: String, val height: Int, val url: String)
    data class SubtitleOption(val label: String, val language: String?, val url: String)
    data class AudioOption(
        val label: String,
        val language: String?,
        val groupIndex: Int = -1,
        val trackIndex: Int = -1
    )
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnSleepTimer: ImageButton
    private lateinit var btnLiveReload: TextView
    private lateinit var sbTimeline: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvProgramEndTime: TextView
    private lateinit var viewTimelineStripe: View
    private lateinit var viewTimelineLive: View
    private lateinit var viewTimelineThumb: View
    private lateinit var timelineTrack: View
    private lateinit var timelineArea: View
    private lateinit var btnBackLeft: ImageButton
    private lateinit var btnBackRight: ImageButton
    private lateinit var btnEpgPlayer: ImageButton
    private lateinit var epgPanel: View
    private lateinit var epgDismissScrim: View
    private lateinit var epgDateRow: View
    private lateinit var tvEpgEmptyState: TextView
    private lateinit var btnEpgDatePrev: TextView
    private lateinit var btnEpgDateNext: TextView
    private lateinit var hsvEpgDates: android.widget.HorizontalScrollView
    private lateinit var epgDateContainer: LinearLayout
    private lateinit var lvEpgPrograms: ListView
    private lateinit var channelListPanel: View
    private lateinit var gvChannelListPanel: GridView
    private lateinit var tvChannelListTitle: TextView
    private lateinit var btnChannelListBackToWatch: TextView
    private lateinit var videoLayout: PlayerView
    private lateinit var tvReloadingStatus: View
    private lateinit var ivReloadingIcon: ImageView
    private lateinit var tvAppToast: TextView
    private lateinit var tvReloadingTitle: TextView
    private lateinit var tvReloadingSubtitle: TextView
    private lateinit var listBackgroundOverlay: View
    private lateinit var timerWarningPanel: View
    private lateinit var btnStopTimer: TextView
    private lateinit var homePanel: View
    private lateinit var ivHomeProfile: ImageView
    private lateinit var ivHomeSettings: ImageView
    private lateinit var ivHomePower: ImageView
    private lateinit var homeSettingsScreen: View
    private lateinit var playerSettingsOverlay: View
    private var settingsOpenedFromPlayer = false
    private var settingsOpenedAsAuthOnly = false
    private var channelListProgramTitles: Map<Int, String> = emptyMap()
    private var homeActionIndex = 0
    private var isSettingsModalVisible = false

    private var lastHomePanelWidth = 0
    private var lastHomePanelHeight = 0

    // Состояние
    private var isLocked = false
    private var isPlaybackPaused = false
    private var currentChannelIndex = 0
    private var hasStartedPlaybackFromChannelClick = false
    private val channels = mutableListOf<Channel>()
    private val epgData = mutableMapOf<String, MutableList<Program>>()
    private val epgDataLock = Any()
    private val handler = Handler(Looper.getMainLooper())
    private var inputNumber = ""
    private var epgDatePickedByUser = false
    private var currentPlaylistText: String = ""
    private var selectedPlaylistDisplayName: String = ""
    private var selectedCategoryName: String = ""
    private enum class HomeReturnTarget { PLAYLISTS, CHANNEL_LIST }
    private var homeReturnTarget: HomeReturnTarget = HomeReturnTarget.PLAYLISTS
    private var lastChannelListCategory: String? = null
    private var settingsOpenedFromHomeChannelList = false
    private var availableEpgSources: List<String> = emptyList()
    private var selectedEpgSources: MutableSet<String> = mutableSetOf()
    private val epgSourceStatus = mutableMapOf<String, String>()
    private val cachedLogos = mutableMapOf<String, String>()

    // Fallback alias for legacy references after refactors (kept to avoid unresolved symbol issues in stale IDE states)
    private var candidates: List<String> = emptyList()

    private var timerEndAtMillis: Long = 0L
    private var suppressReloadOverlayUntilMs: Long = 0L
    private var lastBackPressAt = 0L
    private var lastOkPressAt = 0L

    private var shouldOpenLastChannelOnStart = false
    private var isArchivePlayback = false
    private var currentArchiveProgram: Program? = null
    private var timelineUserSeeking = false
    private var timelineSeekStartProgress = -1
    private val timelineSeekDeadband = 12
    private var seekStatusHoldUntilMs: Long = 0L
    private var shouldReloadStreamOnStart = false
    @Volatile
    private var epgFetchInProgress = false
    @Volatile
    private var epgFetchGeneration: Int = 0
    private var archiveStreamStartMs: Long = 0L
    private var lastRequestedPlaybackUrl: String = ""
    private var startupRecoveryAttempts = 0
    private val maxStartupRecoveryAttempts = 3
    private var startupPlaybackUrlLock: String? = null
    private var videoOnlyMinimalMode = false
    private var runtimeRecoveryAttempted = false
    private var behindLiveWindowRecoveryInProgress = false
    private var audioTrackForcedDisabled = false
    private var videoRendererPossiblyBroken = false
    private var videoOnlyMinimalFirstFrameRendered = false
    private var videoOnlyMinimalTriedSoftwareDecoder = false
    private var videoOnlyMinimalNoFrameRunnable: Runnable? = null
    private val enableTsForensicDump = false
    private val startupSlowStreamRunnable: Runnable = Runnable {
        if (!firstFrameRendered) {
            showCenterError("Поток долго загружается", 3000L)
        }
    }
    private val memoryLogRunnable: Runnable = object : Runnable {
        override fun run() {
            logMemoryStats("periodic")
            logPlaybackProgress("periodic")
            if (mediaPlayer != null && !isArchivePlayback) {
                handler.postDelayed(this, 5000L)
            }
        }
    }

    private val playbackFreezeWatchdogRunnable: Runnable = Runnable {
        val player = mediaPlayer ?: return@Runnable
        if (videoOnlyMinimalMode) {
            handler.postDelayed(playbackFreezeWatchdogRunnable, 2000L)
            return@Runnable
        }
        if (!firstFrameRendered) {
            handler.postDelayed(playbackFreezeWatchdogRunnable, 2000L)
            return@Runnable
        }
        // User intentionally paused — do not treat as a stall.
        if (isPlaybackPaused) {
            lastProgressWallClockMs = System.currentTimeMillis()
            bufferingSinceMs = 0L
            handler.postDelayed(playbackFreezeWatchdogRunnable, 2000L)
            return@Runnable
        }
        if (isPlayerOverlayOpen() || isHomeOrSettingsForeground()) {
            handler.postDelayed(playbackFreezeWatchdogRunnable, 2000L)
            return@Runnable
        }
        val now = System.currentTimeMillis()
        val inGrace = now < stallWatchdogGraceUntilMs
        if (!player.isPlaying) {
            if (player.playbackState == androidx.media3.common.Player.STATE_BUFFERING) {
                if (bufferingSinceMs == 0L) bufferingSinceMs = now
                // Brief IPTV rebuffers are normal — only escalate after a long continuous buffer.
                if (!inGrace && now - bufferingSinceMs > PLAYBACK_STALL_BUFFERING_MS) {
                    logDebug("PLAYER_STATE", "watchdog buffering stall detected")
                    notifyPlaybackStall("Буферизация потока")
                }
            } else if (player.playWhenReady &&
                (player.playbackState == androidx.media3.common.Player.STATE_IDLE ||
                    player.playbackState == androidx.media3.common.Player.STATE_ENDED)
            ) {
                // Backup hard-stop: if the onPlaybackStateChanged handler didn't fire recovery
                // (e.g. grace blocked it), the watchdog catches up after HARD_STOP ms.
                if (lastProgressWallClockMs > 0L &&
                    now - lastProgressWallClockMs > PLAYBACK_STALL_HARD_STOP_MS
                ) {
                    logDebug("PLAYER_STATE", "watchdog hard-stop stall state=${player.playbackState}")
                    notifyPlaybackStall("Воспроизведение остановилось", immediate = true)
                }
            } else {
                // READY + !isPlaying is common during short gaps — do not auto-reload.
                bufferingSinceMs = 0L
            }
            handler.postDelayed(playbackFreezeWatchdogRunnable, 2000L)
            return@Runnable
        }
        bufferingSinceMs = 0L
        if (tvReloadingStatus.visibility == View.VISIBLE && !playbackRecoveryActive) {
            tvReloadingStatus.visibility = View.GONE
        }
        val pos = player.currentPosition
        when {
            pos > lastPlaybackPositionMs + 250L -> {
                lastPlaybackPositionMs = pos
                lastProgressWallClockMs = now
                onPlaybackRecoverySucceeded()
            }
            // Live window can jump backwards — reset baseline, never treat as freeze.
            lastPlaybackPositionMs >= 0L && pos < lastPlaybackPositionMs - 1_000L -> {
                lastPlaybackPositionMs = pos
                lastProgressWallClockMs = now
            }
            !inGrace &&
                lastProgressWallClockMs > 0L &&
                now - lastProgressWallClockMs > PLAYBACK_STALL_PROGRESS_MS -> {
                logDebug("PLAYER_STATE", "watchdog progress stall detected")
                lastProgressWallClockMs = now
                notifyPlaybackStall("Поток завис (нет прогресса)")
            }
        }
        handler.postDelayed(playbackFreezeWatchdogRunnable, 2000L)
    }

    private fun notifyPlaybackStall(reason: String, immediate: Boolean = false) {
        if (isSettingsModalVisible || homePanel.visibility == View.VISIBLE) return
        if (::epgPanel.isInitialized && epgPanel.visibility == View.VISIBLE) return
        if (::channelListPanel.isInitialized && channelListPanel.visibility == View.VISIBLE) return
        val now = System.currentTimeMillis()
        // Grace only blocks slow stalls (buffering/progress); immediate == true bypasses it.
        if (!immediate && now < stallWatchdogGraceUntilMs) return
        if (now < suppressReloadOverlayUntilMs) return
        lastPlaybackStallReason = reason
        if (!playbackRecoveryActive) {
            playbackRecoveryActive = true
            playbackRecoveryStartedAtMs = now
            playbackRecoveryAttemptCount = 0
            schedulePlaybackRecoveryRetry(immediate = immediate)
        } else {
            schedulePlaybackRecoveryRetry(immediate = false)
        }
    }

    private fun schedulePlaybackRecoveryRetry(immediate: Boolean) {
        handler.removeCallbacks(playbackRecoveryRetryRunnable)
        val delay = if (immediate) 0L else PLAYBACK_RECOVERY_RETRY_MS
        handler.postDelayed(playbackRecoveryRetryRunnable, delay)
    }

    private fun attemptPlaybackRecoveryStep() {
        if (!playbackRecoveryActive) return
        if (isSettingsModalVisible || homePanel.visibility == View.VISIBLE || isPlayerOverlayOpen()) {
            resetPlaybackRecoveryState()
            return
        }
        val elapsed = System.currentTimeMillis() - playbackRecoveryStartedAtMs
        if (elapsed >= PLAYBACK_RECOVERY_WINDOW_MS) {
            val reason = lastPlaybackStallReason.ifBlank { "Поток не отвечает" }
            resetPlaybackRecoveryState()
            showPlaybackFreezeFailure(reason)
            return
        }
        playbackRecoveryAttemptCount++
        val remainingSec = ((PLAYBACK_RECOVERY_WINDOW_MS - elapsed) / 1000L).coerceAtLeast(1L)
        showReloadingStatus(
            "Обновление трансляции",
            "Попытка $playbackRecoveryAttemptCount, осталось ~${remainingSec}с"
        )
        showUI(preferFocus = btnLiveReload)
        playChannel(forcePlay = true, reason = PlayerOpenReason.RECOVERY)
        schedulePlaybackRecoveryRetry(immediate = false)
    }

    private fun onPlaybackRecoverySucceeded() {
        if (!playbackRecoveryActive) return
        resetPlaybackRecoveryState()
        stallWatchdogGraceUntilMs =
            System.currentTimeMillis() + PLAYBACK_STALL_GRACE_AFTER_START_MS
        resetPlaybackProgressBaseline()
        if (tvReloadingStatus.visibility == View.VISIBLE) {
            tvReloadingStatus.visibility = View.GONE
        }
    }

    private fun resetPlaybackRecoveryState() {
        playbackRecoveryActive = false
        playbackRecoveryStartedAtMs = 0L
        playbackRecoveryAttemptCount = 0
        handler.removeCallbacks(playbackRecoveryRetryRunnable)
    }

    private fun showPlaybackFreezeFailure(reason: String) {
        showReloadingStatus(
            title = "Не удалось восстановить трансляцию",
            subtitle = reason,
            isError = true
        )
        showUI(preferFocus = btnLiveReload)
        suppressReloadOverlayUntilMs = System.currentTimeMillis() + 12_000L
        handler.postDelayed({ tvReloadingStatus.visibility = View.GONE }, 12_000L)
    }


    private val returnToLiveRunnable = Runnable {
        playChannel(forcePlay = true)
    }

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
    private val prefs by lazy { getSharedPreferences("oportal_settings", Context.MODE_PRIVATE) }
    private val golosTypeface: Typeface? by lazy {
        ResourcesCompat.getFont(
            this,
            R.font.golos_text
        )
    }
    private val golosTypefaceExtraBold: Typeface? by lazy {
        ResourcesCompat.getFont(this, R.font.golostext_extrabold)
    }
    private val golosTypefaceSemiBold: Typeface? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(golosTypeface, 600, false)
        } else {
            golosTypefaceExtraBold
        }
    }

    companion object {
        private const val EXTRA_OPEN_HOME_PLAYLISTS_FRESH = "extra_open_home_playlists_fresh"
        private const val USE_FFMPEG_AUDIO_FOR_MPEG_L2 = true
        private const val PREF_USE_FFMPEG_AUDIO_FOR_MPEG_L2 = "pref_use_ffmpeg_audio_for_mpeg_l2"
        private const val PREF_PLAYLISTS = "playlist_profiles"
        private const val PREF_SELECTED_PLAYLIST = "selected_playlist"
        private const val PREF_SELECTED_EPG = "selected_epg"
        private const val PLAYBACK_RECOVERY_WINDOW_MS = 30_000L
        private const val PLAYBACK_RECOVERY_RETRY_MS = 10_000L
        // Live IPTV often rebuffers for several seconds; 5s caused constant reload loops.
        private const val PLAYBACK_STALL_BUFFERING_MS = 20_000L
        private const val PLAYBACK_STALL_PROGRESS_MS = 15_000L
        // Fast hard-stop for ENDED/IDLE: stream died, we need recovery quickly.
        private const val PLAYBACK_STALL_HARD_STOP_MS = 5_000L
        private const val PLAYBACK_STALL_GRACE_AFTER_START_MS = 25_000L
        private const val PREF_EPG_CACHE = "epg_cache"
        private const val PREF_EPG_STATUS = "epg_status"
        private const val PREF_EPG_LAST_REFRESH = "epg_last_refresh"
        private const val PREF_CUSTOM_EPG_SOURCES = "custom_epg_sources"
        private const val PREF_LOGO_CACHE = "logo_cache"
        private const val PREF_PLAYLIST_CONTENT_CACHE = "playlist_content_cache"
        private const val PREF_START_LAST_CHANNEL = "pref_start_last_channel"
        private const val PREF_LAST_CHANNEL = "last_channel"
        private const val PREF_LAST_CHANNEL_URL = "last_channel_url"
        private const val PREF_LAST_CHANNEL_NAME = "last_channel_name"
        private const val PREF_SUBTITLE_LANGUAGE = "pref_subtitle_language"
        private const val PREF_SUBTITLE_ENABLED = "pref_subtitle_enabled"
        private const val PREF_QUALITY_HEIGHT = "pref_quality_height"
        private const val PREF_AUDIO_LANGUAGE = "pref_audio_language"
        private const val PREF_SERVICES_CACHE = "pref_services_cache"
        private const val PREF_ASPECT_RATIO_MODE = "pref_aspect_ratio_mode"
        private val ASPECT_RATIO_LABEL_BY_KEY = mapOf(
            "auto" to "Автоматически",
            "fit" to "Вписать в экран",
            "aspect_16_9" to "16:9",
            "fill" to "Растянуть",
            "zoom" to "Обрезать"
        )
        private val ASPECT_RATIO_KEY_BY_LABEL = mapOf(
            "Автоматически" to "auto",
            "Вписать в экран" to "fit",
            "16:9" to "aspect_16_9",
            "Растянуть" to "fill",
            "Обрезать" to "zoom"
        )
        private const val PREF_SLEEP_TIMER_MINUTES = "pref_sleep_timer_minutes"
        private const val PREF_SHOW_LOCK_BUTTON = "pref_show_lock_button"
        private const val PREF_APP_VERSION_CODE = "pref_app_version_code"
        private const val PREF_USE_GPU_DECODER = "pref_use_gpu_decoder"
        private const val PREF_EPG_SOURCES_FINGERPRINT = "pref_epg_sources_fingerprint"
        private const val PREF_EPG_REFRESH_INTERVAL_DAYS = "pref_epg_refresh_interval_days"
        private const val PREF_USER_LOGIN = "pref_user_login"
        private const val PREF_KNOWN_SERVICE_NAMES = "pref_known_service_names"
        private val DEFAULT_SERVICE_NAMES = setOf("Wink", "iLook", "Сервис В", "Lime TV", "Only4")
        private const val PREF_USER_TOKEN = "pref_user_token"
        private const val PREF_USER_NAME = "pref_user_name"
        private const val PREF_USER_PLAYLIST = "pref_user_playlist"
        private const val PREF_HLS_ALLOW_NON_IDR = "pref_hls_allow_non_idr"

        private const val TOKEN_PREFIX = "https://o.avff.pw/my/"
        private const val TOKEN_SUFFIX = ".m3u"
        private const val MAX_EPG_COMPRESSED_BYTES = 900L * 1024L * 1024L
        private const val MAX_EPG_UNPACKED_BYTES = 1800L * 1024L * 1024L
        private const val EPG_KEEP_PAST_DAYS = 7
        private const val EPG_KEEP_FUTURE_DAYS = 7
        private const val EPG_PANEL_PAST_DAYS = 3
        private const val EPG_PANEL_FUTURE_DAYS = 3
        private const val MAX_PROGRAMS_PER_CHANNEL = 500

        private const val HOME_BASE_WIDTH = 1280f
        private const val HOME_BASE_HEIGHT = 720f
        private const val HOME_BOTTOM_TILE_TEXT_SP = 18f
        private val SLEEP_TIMER_OPTIONS = intArrayOf(0, 10, 30, 60, 90, 120)
    }

    private val hideUiRunnable = Runnable { hideUI() }
    private var pendingSeekDeltaSec: Int = 0
    private var liveTimelineAnchorMs: Long = 0L
    private val applySeekDeltaRunnable = Runnable {
        val deltaSec = pendingSeekDeltaSec
        pendingSeekDeltaSec = 0
        if (deltaSec == 0) return@Runnable
        applyRelativeSeekSeconds(deltaSec)
    }
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

    private fun buildPlaceholderPrograms(
        pastDays: Int = EPG_PANEL_PAST_DAYS,
        futureDays: Int = EPG_PANEL_FUTURE_DAYS,
        title: String = "Программа передач недоступна"
    ): List<Program> {
        val result = mutableListOf<Program>()
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -pastDays)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val end = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, futureDays + 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        while (cal.timeInMillis < end) {
            val start = cal.timeInMillis
            cal.add(Calendar.HOUR_OF_DAY, 1)
            result += Program(title, start, cal.timeInMillis)
        }
        return result
    }

    private fun buildHourlyUnavailableProgramsForDate(dateKey: String): List<Program> {
        val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        val dayStart = dateFmt.parse(dateKey)?.time ?: return emptyList()
        val cal = Calendar.getInstance().apply { timeInMillis = dayStart }
        val result = mutableListOf<Program>()
        repeat(24) {
            val start = cal.timeInMillis
            cal.add(Calendar.HOUR_OF_DAY, 1)
            result += Program("Программа передач недоступна", start, cal.timeInMillis)
        }
        return result
    }

    private fun buildEpgPanelDateModel(realPrograms: List<Program>): Pair<List<String>, Map<String, List<Program>>> {
        val dateFmt = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        val programsByDate = realPrograms
            .distinctBy { Triple(it.title.trim(), it.start, it.stop) }
            .sortedBy { it.start }
            .groupBy { dateFmt.format(Date(it.start)) }
        val baseKeys = mutableListOf<String>()
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -EPG_PANEL_PAST_DAYS)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        repeat(EPG_PANEL_PAST_DAYS + EPG_PANEL_FUTURE_DAYS + 1) {
            baseKeys += dateFmt.format(cal.time)
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        val dateKeys = (baseKeys + programsByDate.keys).distinct().sortedBy { key ->
            dateFmt.parse(key)?.time ?: 0L
        }
        val enriched = dateKeys.associateWith { key ->
            val dayPrograms = programsByDate[key].orEmpty()
            if (dayPrograms.isNotEmpty()) dayPrograms else buildHourlyUnavailableProgramsForDate(key)
        }
        return dateKeys to enriched
    }

    /**
     * Почасовая заглушка для каналов, у которых нет реальных данных EPG (пока не загрузились
     * или отсутствуют), но при этом доступен архив по плейлисту. Позволяет пользователю всё
     * равно перемотать в архив, даже без настоящей программы передач.
     */
    private fun buildArchivePlaceholderPrograms(ch: Channel): List<Program> {
        if (ch.catchupDays <= 0 || ch.catchupSource.isNullOrBlank()) return emptyList()
        val result = mutableListOf<Program>()
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -ch.catchupDays)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val end = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val title = "Программа канала ${ch.name}"
        while (cal.timeInMillis < end) {
            val start = cal.timeInMillis
            cal.add(Calendar.HOUR_OF_DAY, 1)
            result += Program(title, start, cal.timeInMillis)
        }
        return result
    }

    /** Реальные данные EPG, если есть; иначе — архивная почасовая заглушка (если архив доступен). */
    private fun getProgramsWithArchiveFallback(ch: Channel): List<Program> {
        val real = getProgramsForChannel(ch)
        if (real.isNotEmpty()) return real
        return buildArchivePlaceholderPrograms(ch)
    }

    private fun getCurrentProgramTitleForChannelList(ch: Channel): String {
        val now = System.currentTimeMillis()
        getProgramsForChannel(ch).find { now in it.start until it.stop }?.title?.let { return it }
        return epgUnavailableMessage()
    }

    private fun getProgramsForDisplay(ch: Channel): List<Program> {
        val real = getProgramsForChannel(ch)
        return if (real.isNotEmpty()) real else buildPlaceholderPrograms()
    }

    private fun isEpgDataEmpty(): Boolean = synchronized(epgDataLock) { epgData.isEmpty() }

    override fun attachBaseContext(newBase: Context) {
        // Keep app typography stable regardless of system font size.
        val config = Configuration(newBase.resources.configuration)
        if (abs(config.fontScale - 1f) > 0.001f) {
            config.fontScale = 1f
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            super.attachBaseContext(newBase)
        }
    }

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
        startClockUpdater()
        startEpgTicker()
        applyLockButtonVisibility()
        loadPlaylist(showErrors = true, autoPlay = true)
        if (!shouldOpenLastChannelOnStart) {
            showDefaultStartupScreen()
        }
    }

    private fun showDefaultStartupScreen() {
        val isAuthorizedUser = (prefs.getString(PREF_USER_NAME, "") ?: "").isNotBlank()
        val hasEnabledThirdParty = hasEnabledThirdPartyPlaylists()
        if (isAuthorizedUser || hasEnabledThirdParty) showPlaylistPageOnHome(source = "cold_start") else showStartPage()
    }

    private fun hasEnabledThirdPartyPlaylists(): Boolean =
        getThirdPartyPlaylistProfiles().any { it.enabled && it.value.isNotBlank() }

    private fun isAuthorizedUser(): Boolean =
        (prefs.getString(PREF_USER_NAME, "") ?: "").isNotBlank()

    private fun initViews() {
        controlsPanel = findViewById(R.id.controlsPanel)
        topInfoPanel = findViewById(R.id.topInfoPanel)
        epgPanel = findViewById(R.id.epgPanel)
        epgDismissScrim = findViewById(R.id.epgDismissScrim)
        epgDateRow = findViewById(R.id.epgDateRow)
        tvEpgEmptyState = findViewById(R.id.tvEpgEmptyState)
        btnEpgDatePrev = findViewById(R.id.btnEpgDatePrev)
        btnEpgDateNext = findViewById(R.id.btnEpgDateNext)
        hsvEpgDates = findViewById(R.id.hsvEpgDates)
        epgDateContainer = findViewById(R.id.epgDateContainer)
        lvEpgPrograms = findViewById(R.id.lvEpgPrograms)
        epgDismissScrim.setOnClickListener { hideEpgPanel() }
        findViewById<View>(R.id.btnEpgBackToWatch).setOnClickListener { hideEpgPanel() }
        lvEpgPrograms.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: android.widget.AbsListView, scrollState: Int) {
                if (scrollState == android.widget.AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
                    logMemoryStats("epg_list_scroll_idle")
                }
            }

            override fun onScroll(
                view: android.widget.AbsListView,
                firstVisibleItem: Int,
                visibleItemCount: Int,
                totalItemCount: Int
            ) {
            }
        })
        val epgBoundsLayoutListener =
            View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (epgPanel.visibility == View.VISIBLE) syncEpgPanelBounds()
                if (channelListPanel.visibility == View.VISIBLE) syncChannelListPanelBounds()
            }
        topInfoPanel.addOnLayoutChangeListener(epgBoundsLayoutListener)
        controlsPanel.addOnLayoutChangeListener(epgBoundsLayoutListener)
        channelListPanel = findViewById(R.id.channelListPanel)
        gvChannelListPanel = findViewById(R.id.gvChannelListPanel)
        tvChannelListTitle = findViewById(R.id.tvChannelListTitle)
        btnChannelListBackToWatch = findViewById(R.id.btnChannelListBackToWatch)
        btnChannelListBackToWatch.setOnClickListener { hideChannelListPanel() }
        videoLayout = findViewById(R.id.videoLayout)
        playerSubtitlesOverlay = findViewById(R.id.playerSubtitlesOverlay)
        tvPlayerSubtitles = findViewById(R.id.tvPlayerSubtitles)
        playerSubtitlesOverlay.isClickable = false
        playerSubtitlesOverlay.isFocusable = false
        tvPlayerSubtitles.isClickable = false
        tvPlayerSubtitles.isFocusable = false
        videoLayout.subtitleView?.visibility = View.GONE
        layoutPlayerSubtitlesOverlay()
        playerSubtitlesOverlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            layoutPlayerSubtitlesOverlay()
        }
        tvEpg = findViewById(R.id.tvEpgInfo)
        tvChannelName = findViewById(R.id.tvChannelNameInfo)
        tvSystemTime = findViewById(R.id.tvSystemTime)
        tvHomeSystemTime = findViewById(R.id.tvHomeSystemTime)
        tvHomeAppTitle = findViewById(R.id.tvHomeAppTitle)
        tvHomeAppTitle.isClickable = true
        tvHomeAppTitle.isFocusable = false
        tvHomeAppTitle.setOnClickListener { goHomeFromLogoClick() }
        tvHomeBreadcrumbArrow = findViewById(R.id.tvHomeBreadcrumbArrow)
        tvHomeBreadcrumbPill = findViewById(R.id.tvHomeBreadcrumbPill)
        tvHomeBreadcrumbArrow2 = findViewById(R.id.tvHomeBreadcrumbArrow2)
        tvHomeBreadcrumbPill2 = findViewById(R.id.tvHomeBreadcrumbPill2)
        tvHomeBreadcrumbPill.isClickable = true
        tvHomeBreadcrumbPill.isFocusable = false
        tvHomeBreadcrumbPill.setOnClickListener { onBreadcrumbClick() }
        tvHomeBreadcrumbPill2.isClickable = true
        tvHomeBreadcrumbPill2.isFocusable = false
        tvHomeBreadcrumbPill2.setOnClickListener { onCategoryBreadcrumbClick() }
        tvHomeAppTitle.text = SpannableString("O.Portal").apply {
            setSpan(StyleSpan(Typeface.BOLD), 2, length, 0)
            tvHomeAppTitle.typeface = Typeface.create(tvHomeAppTitle.typeface, 800, false)
        }
        tvHomeStartTitle = findViewById(R.id.tvHomeStartTitle)
        tvHomeStartSubtitle = findViewById(R.id.tvHomeStartSubtitle)
        homeStartCenterBlock = findViewById(R.id.homeStartCenterBlock)
        tvHomeWelcome = findViewById(R.id.tvHomeWelcome)
        tvPlaylistPageTitle = findViewById(R.id.tvPlaylistPageTitle)
        tvPlaylistPageSubtitle = findViewById(R.id.tvPlaylistPageSubtitle)
        homePlaylistTilesPanel = findViewById(R.id.homePlaylistTilesPanel)
        gvHomeChannelList = findViewById(R.id.gvHomeChannelList)
        rvHomeTiles = findViewById(R.id.rvHomeTiles)
        tvHomeCategoryBack = findViewById(R.id.tvHomeCategoryBack)
        liveStatusBadge = findViewById(R.id.liveStatusBadge)
        tvLiveStatusText = findViewById(R.id.tvLiveStatusText)
        liveStatusDot = findViewById(R.id.liveStatusDot)
        btnLock = findViewById(R.id.btnLock)
        topGradientOverlay = findViewById(R.id.topGradientOverlay)
        btnAspectRatio = findViewById(R.id.btnAspectRatio)
        btnCcSubtitles = findViewById(R.id.btnCcSubtitles)
        btnAudioTrack = findViewById(R.id.btnAudioTrack)
        btnHdQuality = findViewById(R.id.btnHdQuality)
        tvEpgLoadStatus = findViewById(R.id.tvEpgLoadStatus)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnSleepTimer = findViewById(R.id.btnSleepTimer)
        btnLiveReload = findViewById(R.id.btnLiveReload)
        sbTimeline = findViewById(R.id.sbTimeline)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvProgramEndTime = findViewById(R.id.tvProgramEndInfo)
        viewTimelineStripe = findViewById(R.id.viewTimelineStripe)
        viewTimelineLive = findViewById(R.id.viewTimelineLive)
        viewTimelineThumb = findViewById(R.id.viewTimelineThumb)
        timelineTrack = findViewById(R.id.timelineTrack)
        timelineArea = findViewById(R.id.timelineArea)
        btnBackLeft = findViewById(R.id.btnBackLeft)
        btnBackRight = findViewById(R.id.btnBackRight)
        btnEpgPlayer = findViewById(R.id.btnEpgPlayer)
        tvReloadingStatus = findViewById(R.id.tvReloadingStatus)
        ivReloadingIcon = findViewById(R.id.ivReloadingIcon)
        tvAppToast = findViewById(R.id.tvAppToast)
        tvReloadingTitle = findViewById(R.id.tvReloadingTitle)
        tvReloadingSubtitle = findViewById(R.id.tvReloadingSubtitle)
        listBackgroundOverlay = findViewById(R.id.listBackgroundOverlay)
        timerWarningPanel = findViewById(R.id.timerWarningPanel)
        btnStopTimer = findViewById(R.id.btnStopTimer)
        homePanel = findViewById(R.id.homePanel)
        ivHomeProfile = findViewById(R.id.ivHomeProfile)
        ivHomeSettings = findViewById(R.id.ivHomeSettings)
        ivHomePower = findViewById(R.id.ivHomePower)
        homeSettingsScreen = findViewById(R.id.homeSettingsScreen)
        playerSettingsOverlay = findViewById(R.id.playerSettingsOverlay)
        tvEpg.isSelected = true
        prefs.edit().putInt(PREF_SLEEP_TIMER_MINUTES, 0).apply()
        applyGolosTypeface(window.decorView)
        applyHomeAppTitleStyle()
        setupHomeBottomActionTiles()
        initAspectRatioState()
        homePanel.addOnLayoutChangeListener { _, _, _, right, bottom, _, _, oldRight, oldBottom ->
            if (right != oldRight || bottom != oldBottom) {
                applyHomeScreenScale(force = true)
            }
        }
        homePanel.post { applyHomeScreenScale(force = true) }
    }

    private fun updatePlayPauseButton() {
        btnPlayPause.setImageResource(if (isPlaybackPaused) R.drawable.play else R.drawable.pause)
        btnPlayPause.alpha = 1.0f
    }

    private fun applyHomeAppTitleStyle(
        settingsMode: Boolean = false,
        settingsTitle: String = "Настройки",
        settingsTitle2: String? = null
    ) {
        val logo = SpannableString("O.Portal")
        val medium = golosTypeface
        val portalFace = golosTypefaceSemiBold ?: golosTypefaceExtraBold ?: Typeface.create(golosTypeface, Typeface.BOLD)
        if (medium != null) {
            logo.setSpan(CustomTypefaceSpan(medium), 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (portalFace != null) {
            logo.setSpan(CustomTypefaceSpan(portalFace), 2, logo.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tvHomeAppTitle.text = logo
        tvHomeSystemTime.typeface = golosTypeface ?: Typeface.DEFAULT
        tvHomeBreadcrumbArrow.visibility = if (settingsMode) View.VISIBLE else View.GONE
        tvHomeBreadcrumbPill.visibility = if (settingsMode) View.VISIBLE else View.GONE
        if (settingsMode) {
            tvHomeBreadcrumbPill.text = settingsTitle.lowercase(Locale.getDefault())
        }
        val showSecondLevel = settingsMode && !settingsTitle2.isNullOrBlank()
        tvHomeBreadcrumbArrow2.visibility = if (showSecondLevel) View.VISIBLE else View.GONE
        tvHomeBreadcrumbPill2.visibility = if (showSecondLevel) View.VISIBLE else View.GONE
        if (showSecondLevel) {
            tvHomeBreadcrumbPill2.text = settingsTitle2!!.lowercase(Locale.getDefault())
        }
    }

    private fun computeContentDpScale(): Float {
        val dm = resources.displayMetrics
        val widthDp = dm.widthPixels / dm.density
        val heightDp = dm.heightPixels / dm.density
        val rawScale = minOf(widthDp / 1280f, heightDp / 720f)
        return rawScale.coerceIn(0.45f, 1.35f)
    }

    private fun scalePx(baseDp: Float, scale: Float): Int =
        (baseDp * scale * resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private fun applySettingsContentScale() {
        val scale = computeContentDpScale()

        fun height(id: Int, baseDp: Float) {
            val v = findViewById<View>(id)
            val lp = v.layoutParams ?: return
            lp.height = scalePx(baseDp, scale)
            v.layoutParams = lp
        }
        fun textSize(id: Int, baseSp: Float) {
            findViewById<TextView>(id).setTextSize(TypedValue.COMPLEX_UNIT_PX, baseSp * scale * resources.displayMetrics.density)
        }

        // Карточка профиля — равные вертикальные отступы, без minHeight-перекоса
        (findViewById<View>(R.id.userProfileHeaderCard).layoutParams as? ConstraintLayout.LayoutParams)?.let { lp ->
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        val profileCard = findViewById<View>(R.id.userProfileHeaderCard)
        val profilePadV = resources.getDimensionPixelSize(R.dimen.profile_card_padding_v)
        profileCard.minimumHeight = 0
        profileCard.setPadding(profileCard.paddingLeft, profilePadV, profileCard.paddingRight, profilePadV)
        height(R.id.profileAvatarFrame, 84f)
        (findViewById<View>(R.id.profileAvatarFrame).layoutParams as? ViewGroup.LayoutParams)?.let { lp ->
            lp.width = scalePx(84f, scale)
        }
        (findViewById<ImageView>(R.id.ivProfileAvatar).layoutParams as? ViewGroup.LayoutParams)?.let { lp ->
            lp.width = scalePx(38f, scale)
            lp.height = scalePx(38f, scale)
        }
        textSize(R.id.tvProfileName, 30f)
        textSize(R.id.tvProfileNickname, 20f)
        textSize(R.id.tvProfileTokenLabel, 20f)
        height(R.id.tvProfileTokenValue, 44f)
        textSize(R.id.tvProfileTokenValue, 18f)

        // Сетка настроек — высота как у плиток сервисов/категорий (без уменьшения scale)
        val tileHeight = resources.getDimensionPixelSize(R.dimen.home_tile_min_height)
        val startModeHeight = resources.getDimensionPixelSize(R.dimen.settings_start_mode_row_height)
        listOf(
            R.id.btnPlaylistSettings, R.id.btnEpgSelect, R.id.btnSleepTimerSettings,
            R.id.btnAdvancedSettings, R.id.btnAppInfo, R.id.btnRefreshServices,
            R.id.btnResetSettings, R.id.btnLogoutProfile
        ).forEach { id ->
            findViewById<View>(id).layoutParams?.let { lp ->
                lp.height = tileHeight
                findViewById<View>(id).layoutParams = lp
            }
        }
        findViewById<View>(R.id.itemStartMode).layoutParams?.let { lp ->
            lp.height = startModeHeight
            findViewById<View>(R.id.itemStartMode).layoutParams = lp
        }
        findViewById<ToggleButton>(R.id.tbStartMode).layoutParams?.let { lp ->
            lp.height = scalePx(36f, scale)
            findViewById<ToggleButton>(R.id.tbStartMode).layoutParams = lp
        }

        // Красные кнопки — уже заданы выше

        // Нижний ряд (свои плейлисты / избранные) — только геометрия, текст как у плиток сервисов
        height(R.id.btnOwnPlaylistsTile, 66f)
        height(R.id.btnFavoritesTile, 66f)
        setupHomeBottomActionTiles(scale, HOME_BOTTOM_TILE_TEXT_SP)

        // Поля EPG/плейлистов
        listOf(R.id.etEpgUrl1, R.id.etEpgUrl2, R.id.etEpgUrl3, R.id.etPlaylistUrl1, R.id.etPlaylistUrl2, R.id.etPlaylistUrl3)
            .forEach { id ->
                height(id, 52f)
                textSize(id, 17f)
            }
        listOf(R.id.ivPlaylistToggle1, R.id.ivPlaylistToggle2, R.id.ivPlaylistToggle3).forEach { height(it, 52f) }

        // Кнопки-действия подпанелей (EPG: 4 в ряд, плейлист: 2 в ряд)
        listOf(R.id.btnSavePlaylistSettings, R.id.btnRefreshPlaylistSettings).forEach { id ->
            height(id, 60f)
            textSize(id, 21f)
        }
        listOf(R.id.btnResetEpgCache, R.id.tbEpgSourceMode, R.id.btnSaveEpgSettings, R.id.btnRefreshEpgSettings).forEach { id ->
            height(id, 60f)
            textSize(id, 21f)
        }

        // Поля авторизации
        listOf(R.id.etUserLoginInline, R.id.etUserTokenInline).forEach { id ->
            height(id, 58f)
            textSize(id, 20f)
        }
        listOf(R.id.tvUserSectionState, R.id.tvUserTokenLabel).forEach { id ->
            textSize(id, 20f)
        }
        height(R.id.btnUserAuthInline, 48f)
        textSize(R.id.btnUserAuthInline, 24f)

        applySettingsViewportLayout()
    }

    private fun applySettingsViewportLayout() {
        homeSettingsScreen.post {
            val viewportHeight = homeSettingsScreen.height
            if (viewportHeight <= 0) return@post

            val settingsRoot = findViewById<View>(R.id.settingsRootLayout) ?: return@post
            settingsRoot.minimumHeight = viewportHeight

            listOf(R.id.playlistSettingsPanel, R.id.epgSettingsPanel).forEach { panelId ->
                val panel = findViewById<View>(panelId)
                (panel.layoutParams as? ConstraintLayout.LayoutParams)?.let { lp ->
                    lp.height = 0
                    lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    panel.layoutParams = lp
                }
            }

            settingsRoot.requestLayout()
        }
    }

    private fun applyHomeScreenScale(force: Boolean = false) {
        val panelWidth = homePanel.width.takeIf { it > 0 } ?: return
        val panelHeight = homePanel.height.takeIf { it > 0 } ?: return
        if (!force && panelWidth == lastHomePanelWidth && panelHeight == lastHomePanelHeight) return

        lastHomePanelWidth = panelWidth
        lastHomePanelHeight = panelHeight

        val widthScale = panelWidth.toFloat() / HOME_BASE_WIDTH
        val heightScale = panelHeight.toFloat() / HOME_BASE_HEIGHT
        val scale = minOf(widthScale, heightScale).coerceIn(0.55f, 1.4f)

        fun scaledSp(baseSp: Float): Float = baseSp * scale

        tvHomeAppTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp(18f))
        tvHomeBreadcrumbArrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp(12f))
        tvHomeBreadcrumbPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp(10f))
        tvHomeBreadcrumbArrow2.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp(12f))
        tvHomeBreadcrumbPill2.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp(10f))
        tvHomeSystemTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp(14f))
        tvHomeWelcome.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp(11f))
        tvHomeCategoryBack.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp(12f))
        tvPlaylistPageTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp(18f))
        tvPlaylistPageSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp(11f))
        tvHomeStartTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp(26f))
        tvHomeStartSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, scaledSp(15f))

        val iconSize = scalePx(18f, scale)
        listOf(ivHomeProfile, ivHomeSettings, ivHomePower).forEach { icon ->
            icon.layoutParams = icon.layoutParams.apply {
                width = iconSize
                height = iconSize
            }
        }

        val bottomTileHeight = scalePx(66f, scale)
        findViewById<View>(R.id.btnOwnPlaylistsTile).layoutParams.height = bottomTileHeight
        findViewById<View>(R.id.btnFavoritesTile).layoutParams.height = bottomTileHeight
        setupHomeBottomActionTiles(scale, HOME_BOTTOM_TILE_TEXT_SP)
        applyHomeBottomTilesGeometry()
    }

    private fun setupHomeBottomActionTiles(scale: Float = 1f, textSizeSp: Float = HOME_BOTTOM_TILE_TEXT_SP) {
        bindHomeBottomActionTile(
            findViewById(R.id.btnOwnPlaylistsTile),
            R.drawable.ic_play,
            getString(R.string.home_own_playlists),
            textSizeSp,
            scale
        )
        bindHomeBottomActionTile(
            findViewById(R.id.btnFavoritesTile),
            R.drawable.ic_star,
            getString(R.string.home_favorites),
            textSizeSp,
            scale
        )
    }

    private fun bindHomeBottomActionTile(
        container: View,
        iconRes: Int,
        label: String,
        textSizeSp: Float,
        scale: Float = 1f
    ) {
        val icon = container.findViewById<ImageView>(R.id.ivBottomActionIcon)
        val labelView = container.findViewById<TextView>(R.id.tvBottomActionLabel)
        icon?.setImageResource(iconRes)
        labelView?.text = label
        labelView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        labelView?.includeFontPadding = false
        golosTypeface?.let { labelView?.typeface = Typeface.create(it, Typeface.NORMAL) }
        val iconSize = scalePx(22f, scale)
        icon?.layoutParams = icon?.layoutParams?.apply {
            width = iconSize
            height = iconSize
        }
        val iconGap = scalePx(10f, scale)
        labelView?.layoutParams = (labelView?.layoutParams as? LinearLayout.LayoutParams)?.apply {
            marginStart = iconGap
        }
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
        updateHomeHeaderActions()
        ivHomePower.setOnClickListener { closeAppCompletely() }

        btnLiveReload.setOnClickListener {
            logPathState("LIVE_PATH before_reload_click")
            if (System.currentTimeMillis() >= suppressReloadOverlayUntilMs) {
                showReloadingStatus(
                    title = "Выполняется обновление трансляции! Ожидайте...",
                    subtitle = ""
                )
            }
            playChannel(forcePlay = true, reason = PlayerOpenReason.LIVE_RETRY)
            logPathState("LIVE_PATH after_reload_click")
            handler.postDelayed({
                tvReloadingStatus.visibility = View.GONE
            }, 1200)
        }
        bindRealPlayerExitButtonListener()
        sbTimeline.max = 1000
        sbTimeline.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val clamped = clampTimelineProgress(progress)
                if (fromUser && clamped != progress) {
                    seekBar?.progress = clamped
                }
                applyTimelineProgressUi(clamped)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                timelineUserSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                applyTimelineSeekFromProgress(seekBar?.progress ?: 0)
                timelineUserSeeking = false
            }
        })

        timelineTrack.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    timelineUserSeeking = true
                    timelineSeekStartProgress = sbTimeline.progress
                    val progress = clampTimelineProgress(timelineProgressFromTouchX(view, event.x))
                    sbTimeline.progress = progress
                    applyTimelineProgressUi(progress)
                    previewTimelineSeekText(progress)
                    showUI()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val progress = clampTimelineProgress(timelineProgressFromTouchX(view, event.x))
                    sbTimeline.progress = progress
                    applyTimelineProgressUi(progress)
                    previewTimelineSeekText(progress)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val progress = clampTimelineProgress(timelineProgressFromTouchX(view, event.x))
                    if (timelineSeekStartProgress >= 0 &&
                        kotlin.math.abs(progress - timelineSeekStartProgress) < timelineSeekDeadband
                    ) {
                        sbTimeline.progress = timelineSeekStartProgress
                        applyTimelineProgressUi(timelineSeekStartProgress)
                        timelineUserSeeking = false
                        timelineSeekStartProgress = -1
                        updateTimelineUi()
                    } else {
                        sbTimeline.progress = progress
                        applyTimelineSeekFromProgress(progress)
                        timelineUserSeeking = false
                        timelineSeekStartProgress = -1
                    }
                    true
                }
                else -> false
            }
        }

        btnAspectRatio.setOnClickListener { cycleAspectRatioMode() }
        btnAspectRatio.setOnLongClickListener {
            val next = !prefs.getBoolean(PREF_HLS_ALLOW_NON_IDR, false)
            prefs.edit().putBoolean(PREF_HLS_ALLOW_NON_IDR, next).apply()
            logDebug("PLAYER_HLS", "runtime toggle allowNonIdr=$next")
            showCenterError("HLS allowNonIdr=${if (next) "ON" else "OFF"}", 1500L)
            restartCurrentStream(recreatePlayer = false)
            true
        }
        btnSleepTimer.setOnClickListener { showTimerDialog() }

        btnPlayPause.setOnClickListener {
            if (isPlaybackPaused) {
                mediaPlayer?.play()
                if (!videoOnlyMinimalMode) handler.postDelayed(startupSlowStreamRunnable, 45_000L)
                isPlaybackPaused = false
                if (isArchivePlayback) {
                    liveTimelineAnchorMs = 0L
                }
                updateTimelineUi()
            } else {
                mediaPlayer?.pause()
                isPlaybackPaused = true
                if (!isArchivePlayback) {
                    liveTimelineAnchorMs = System.currentTimeMillis()
                    updateTimelineUi()
                }
            }
            updatePlayPauseButton()
            showUI(preferFocus = btnPlayPause)
        }

        btnBackLeft.setOnClickListener {
            pendingSeekDeltaSec -= 60
            tvEpg.text =
                "Перематываем передачу на ${formatMinutesRu(kotlin.math.abs(pendingSeekDeltaSec) / 60)}"
            seekStatusHoldUntilMs = System.currentTimeMillis() + 2200L
            handler.removeCallbacks(applySeekDeltaRunnable)
            handler.postDelayed(applySeekDeltaRunnable, 900L)
            showSeekSpinner()
            showUI(preferFocus = btnBackLeft)
        }
        btnBackRight.setOnClickListener {
            if (!isArchivePlayback) {
                showAppToast("Перемотка вперёд недоступна в прямом эфире")
                showUI(preferFocus = btnBackRight)
                return@setOnClickListener
            }
            pendingSeekDeltaSec += 60
            tvEpg.text =
                "Перематываем передачу на ${formatMinutesRu(kotlin.math.abs(pendingSeekDeltaSec) / 60)}"
            seekStatusHoldUntilMs = System.currentTimeMillis() + 2200L
            handler.removeCallbacks(applySeekDeltaRunnable)
            handler.postDelayed(applySeekDeltaRunnable, 900L)
            showSeekSpinner()
            showUI(preferFocus = btnBackRight)
        }
        btnEpgPlayer.setOnClickListener {
            toggleEpgPanel()
        }

        btnLock.setOnClickListener {
            isLocked = !isLocked
            btnLock.alpha = if (isLocked) 0.72f else 1.0f
            tvEpg.text =
                if (isLocked) "Управление свайпами заблокировано." else "Управление свайпами разблокировано."
            handler.removeCallbacks(restoreEpgRunnable)
            handler.postDelayed(restoreEpgRunnable, 2000)
            showUI()
        }

        btnCcSubtitles.setOnClickListener { showSubtitleTrackMenu() }
        btnAudioTrack.setOnClickListener { showAudioTrackMenu() }
        btnHdQuality.setOnClickListener { showQualityTrackMenu() }

        updatePlayerControlFocusChain()

        btnStopTimer.setOnClickListener {
            cancelSleepTimer()
            showAppToast("Таймер остановлен")
        }

        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (homePanel.visibility == View.VISIBLE || isLocked) return false
                videoPinchScale = (videoPinchScale * detector.scaleFactor).coerceIn(0.85f, 2.6f)
                applyVideoPinchScale()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                finalizePinchAspectRatio()
            }
        })

        mDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                if (homePanel.visibility == View.VISIBLE) return true
                if (isLocked) {
                    showLockedMessage()
                    return false
                }

                val dx = e2.x - e1.x
                val dy = e2.y - e1.y

                if (abs(dy) > abs(dx)) {
                    if (channels.isNotEmpty()) {
                        currentChannelIndex =
                            (currentChannelIndex + if (dy < 0) 1 else -1 + channels.size) % channels.size
                        playChannel()
                    }
                    return true
                }

                if (homeSettingsScreen.visibility == View.VISIBLE && abs(dx) > abs(dy) && abs(dx) > 120) {
                    if (dx < -120) hideSettingsScreen()
                    return true
                }

                if (dx > 120 && abs(dx) > abs(dy)) {
                    showEpgPanel()
                    return true
                }

                if (dx < -120 && abs(dx) > abs(dy)) {
                    showChannelListPanel()
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
        homePlaylistTilesPanel.visibility = View.GONE
        homeStartCenterBlock.visibility = View.VISIBLE
        tvHomeStartTitle.visibility = View.VISIBLE
        tvHomeStartSubtitle.visibility = View.VISIBLE
        tvHomeAppTitle.visibility = View.VISIBLE
        tvHomeSystemTime.visibility = View.VISIBLE
        ivHomePower.visibility = View.VISIBLE
        disableHomeCategoryBack("showStartPage")
        homePanel.post { applyHomeScreenScale(force = true) }
        topInfoPanel.visibility = View.GONE
        topGradientOverlay.visibility = View.GONE
        controlsPanel.visibility = View.GONE
        updateHomeHeaderActions()
    }

    private fun isOnUnauthorizedStartPage(): Boolean {
        return !isAuthorizedUser() &&
            tvHomeStartTitle.visibility == View.VISIBLE &&
            !hasEnabledThirdPartyPlaylists()
    }

    private fun updateHomeHeaderActions() {
        val authorized = isAuthorizedUser()
        ivHomeProfile.visibility = if (authorized) View.GONE else View.VISIBLE
        ivHomeSettings.visibility = View.VISIBLE
        if (authorized) {
            homeActionIndex = 0
        }
        if (!authorized) {
            ivHomeProfile.setImageResource(R.drawable.profile)
            ivHomeProfile.imageTintList = ColorStateList.valueOf(Color.WHITE)
            ivHomeProfile.contentDescription = "Авторизация"
            ivHomeProfile.setOnClickListener {
                if (isSettingsModalVisible && findViewById<View>(R.id.userSettingsPanel).visibility == View.VISIBLE) {
                    hideSettingsScreen()
                } else {
                    openHomeAuthScreen()
                }
            }
        } else {
            ivHomeProfile.setOnClickListener(null)
        }
        ivHomeSettings.setImageResource(R.drawable.fibssettings)
        ivHomeSettings.imageTintList = null
        ivHomeSettings.contentDescription = getString(R.string.home_settings_button)
        ivHomeSettings.setOnClickListener {
            if (homeSettingsScreen.visibility == View.VISIBLE &&
                findViewById<View>(R.id.userSettingsPanel).visibility != View.VISIBLE
            ) {
                hideSettingsScreen()
            } else {
                showSettingsDialog()
            }
        }
    }

    private fun openHomeAuthScreen() {
        showSettingsDialog()
        openProfileAuthScreen()
    }



    private data class HomeTileItem(val title: String, val onClick: () -> Unit)
    private var homeTilesAdapter: HomeTilesAdapter? = null
    private var homeTilesColumnsApplied: Int = -1
    private var homeTilesWidthApplied: Int = -1
    private var homeTilesHeightApplied: Int = -1
    private var homeTilesTitleSizeApplied: Float = -1f
    private var homeTilesSpacingApplied: Int = -1
    private var homeTilesSpacingDecoration: RecyclerView.ItemDecoration? = null
    private var currentHomeTilesItems: List<HomeTileItem> = emptyList()
    private var cachedCategoryGroups: Map<String, List<Channel>> = emptyMap()
    private var categoryOpenInProgress = false

    private fun computeHomeTileColumns(): Int {
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val uiMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK
        val isTv = uiMode == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        return when {
            isTv -> 3
            widthDp >= 500f -> 3
            widthDp >= 360f -> 2
            else -> 2
        }
    }

    private inner class HomeGridSpacingDecoration(
        private val spacingPx: Int,
        private val columns: Int
    ) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val position = parent.getChildAdapterPosition(view)
            if (position == RecyclerView.NO_POSITION) return
            val lm = parent.layoutManager as? GridLayoutManager
            val spanCount = lm?.spanCount ?: columns
            val lookup = lm?.spanSizeLookup
            val spanIndex = lookup?.getSpanIndex(position, spanCount) ?: (position % columns)
            val spanSize = lookup?.getSpanSize(position) ?: 1
            outRect.left = 0
            outRect.right = if (spanIndex + spanSize >= spanCount) 0 else spacingPx
            outRect.bottom = spacingPx
        }
    }

    /**
     * Последняя неполная строка сетки плиток растягивается на всю ширину, а не остаётся
     * "прибитой" к левому краю — если категорий не кратно числу колонок, последние 1-2
     * плитки делят строку между собой (или одна занимает её целиком).
     */
    private inner class HomeTileSpanSizeLookup(
        private val columns: Int,
        private val itemCountProvider: () -> Int
    ) : GridLayoutManager.SpanSizeLookup() {
        init { isSpanIndexCacheEnabled = true }
        override fun getSpanSize(position: Int): Int {
            val spanCount = columns * 2
            val total = itemCountProvider()
            if (columns <= 0 || total <= 0) return spanCount
            val leftover = total % columns
            val lastRowStart = total - leftover
            return if (leftover != 0 && position >= lastRowStart) {
                spanCount / leftover
            } else {
                spanCount / columns
            }
        }
    }

    private inner class HomeTilesAdapter(
        private val tileWidth: Int,
        private val tileHeight: Int,
        private val spacing: Int,
        private val columns: Int,
        private val titleSizeSp: Float = 17f
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var tileItems: List<HomeTileItem> = emptyList()

        fun submit(list: List<HomeTileItem>) {
            tileItems = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val root = FrameLayout(parent.context)
            root.isFocusable = true
            root.isFocusableInTouchMode = false
            root.isClickable = true
            root.isEnabled = true
            root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            val tv = TextView(parent.context)
            tv.setTextColor(Color.WHITE)
            tv.gravity = Gravity.CENTER
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSizeSp)
            tv.typeface = golosTypeface?.let { Typeface.create(it, Typeface.NORMAL) } ?: Typeface.DEFAULT
            tv.setTypeface(tv.typeface, Typeface.NORMAL)
            tv.isFocusable = false
            tv.isFocusableInTouchMode = false
            tv.isClickable = false
            tv.isEnabled = false
            tv.setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
            root.setBackgroundResource(R.drawable.bg_playlist_tile)
            root.addView(
                tv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            val lp = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, tileHeight)
            lp.leftMargin = 0
            lp.rightMargin = 0
            lp.bottomMargin = 0
            root.layoutParams = lp
            return object : RecyclerView.ViewHolder(root) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val root = holder.itemView as FrameLayout
            val tv = root.getChildAt(0) as TextView
            val item = tileItems[position]
            tv.text = item.title
            val lpDebug = root.layoutParams as RecyclerView.LayoutParams
            logDebug(
                "NAV",
                "HOME_TILE_LAYOUT_DEBUG pos=$position width=${lpDebug.width} height=${lpDebug.height} marginStart=${lpDebug.marginStart} marginEnd=${lpDebug.marginEnd} leftMargin=${lpDebug.leftMargin} rightMargin=${lpDebug.rightMargin} adapterSpacing=$spacing"
            )
            root.setOnClickListener {
                logDebug("NAV", "HOME_TILE_ROOT_CLICK_RECEIVED name=${item.title}")
                item.onClick()
            }
        }

        override fun getItemCount(): Int = tileItems.size
    }

    private fun logHomeGridRealCoords(source: String, tileWidth: Int, columns: Int) {
        rvHomeTiles.post {
            val rootView = findViewById<View>(android.R.id.content)
            fun View.globalLeft(): Int {
                val loc = IntArray(2)
                getLocationOnScreen(loc)
                return loc[0]
            }
            fun View.globalRight(): Int {
                val loc = IntArray(2)
                getLocationOnScreen(loc)
                return loc[0] + width
            }
            val portalLeft = tvHomeAppTitle.globalLeft()
            val portalRight = tvHomeAppTitle.globalRight()
            val powerLeft = ivHomePower.globalLeft()
            val powerRight = ivHomePower.globalRight()
            val recyclerLeft = rvHomeTiles.globalLeft()
            val recyclerRight = rvHomeTiles.globalRight()
            val first = rvHomeTiles.getChildAt(0)
            val last = rvHomeTiles.getChildAt((rvHomeTiles.childCount - 1).coerceAtLeast(0))
            val firstLeft = first?.globalLeft() ?: -1
            val firstRight = first?.globalRight() ?: -1
            val lastLeft = last?.globalLeft() ?: -1
            val lastRight = last?.globalRight() ?: -1
            val rowLast = if (columns > 0) rvHomeTiles.getChildAt((columns - 1).coerceAtMost((rvHomeTiles.childCount - 1).coerceAtLeast(0))) else null
            val rowFirstTileLeft = first?.globalLeft() ?: -1
            val rowLastTileRight = rowLast?.globalRight() ?: -1

            val rowChildren = (0 until rvHomeTiles.childCount)
                .mapNotNull { rvHomeTiles.getChildAt(it) }
                .filter { it.top == (first?.top ?: Int.MIN_VALUE) }
                .sortedBy { it.left }
            val gap1 = if (rowChildren.size >= 2) rowChildren[1].left - rowChildren[0].right else -1
            val gap2 = if (rowChildren.size >= 3) rowChildren[2].left - rowChildren[1].right else -1
            val gap3 = if (rowChildren.size >= 4) rowChildren[3].left - rowChildren[2].right else -1

            logDebug(
                "NAV",
                "HOME_GRID_REAL_COORDS source=$source rootWidth=${rootView.width} portalLeft=$portalLeft portalRight=$portalRight powerLeft=$powerLeft powerRight=$powerRight recyclerLeft=$recyclerLeft recyclerRight=$recyclerRight recyclerWidth=${rvHomeTiles.width} recyclerPaddingLeft=${rvHomeTiles.paddingLeft} recyclerPaddingRight=${rvHomeTiles.paddingRight} firstTileLeft=$firstLeft firstTileRight=$firstRight lastTileLeft=$lastLeft lastTileRight=$lastRight rowFirstTileLeft=$rowFirstTileLeft rowLastTileRight=$rowLastTileRight tileWidth=$tileWidth gap1=$gap1 gap2=$gap2 gap3=$gap3 columns=$columns adapterCount=${rvHomeTiles.adapter?.itemCount ?: -1} childCount=${rvHomeTiles.childCount}"
            )
        }
    }

    private fun applyHomeGridContainerGeometry(source: String) {
        val edgeMargin = resources.getDimensionPixelSize(R.dimen.home_edge_margin)
        (homePlaylistTilesPanel.layoutParams as? ConstraintLayout.LayoutParams)?.let { lp ->
            lp.startToStart = ConstraintSet.PARENT_ID
            lp.endToEnd = ConstraintSet.PARENT_ID
            lp.width = 0
            lp.marginStart = edgeMargin
            lp.marginEnd = edgeMargin
            homePlaylistTilesPanel.layoutParams = lp
        }

        (rvHomeTiles.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.leftMargin = 0
            lp.rightMargin = 0
            lp.marginStart = 0
            lp.marginEnd = 0
            rvHomeTiles.layoutParams = lp
        }

        rvHomeTiles.setPadding(0, rvHomeTiles.paddingTop, 0, rvHomeTiles.paddingBottom)
    }

    private data class HomeGridGeometry(
        val columns: Int,
        val rootWidth: Int,
        val leftAnchor: Int,
        val safeRight: Int,
        val availableWidth: Int,
        val spacing: Int,
        val tileWidth: Int,
        val tileHeight: Int,
        val leftInset: Int,
        val rightInset: Int
    )

    private fun computeHomeGridGeometry(): HomeGridGeometry {
        val columns = computeHomeTileColumns()
        val edgeMargin = resources.getDimensionPixelSize(R.dimen.home_edge_margin)
        val panelWidth = when {
            homePlaylistTilesPanel.width > 0 -> homePlaylistTilesPanel.width
            homePanel.width > 0 -> homePanel.width - edgeMargin * 2
            else -> resources.displayMetrics.widthPixels - edgeMargin * 2
        }.coerceAtLeast(dpToPx(320))
        val availableWidth = panelWidth
        val spacing = resources.getDimensionPixelSize(R.dimen.home_tile_spacing)
        val tileHeight = resources.getDimensionPixelSize(R.dimen.home_tile_min_height)
        val tileWidth = if (columns > 1) {
            ((availableWidth - spacing * (columns - 1)) / columns).coerceAtLeast(dpToPx(100))
        } else {
            availableWidth
        }

        return HomeGridGeometry(
            columns = columns,
            rootWidth = panelWidth,
            leftAnchor = edgeMargin,
            safeRight = edgeMargin + availableWidth,
            availableWidth = availableWidth,
            spacing = spacing,
            tileWidth = tileWidth,
            tileHeight = tileHeight,
            leftInset = 0,
            rightInset = 0
        )
    }

    private fun applyHomeBottomTilesGeometry() {
        val bottomRow = findViewById<View>(R.id.homeBottomTilesRow)
        if (bottomRow.visibility != View.VISIBLE) return

        val geometry = computeHomeGridGeometry()
        val btnOwn = findViewById<View>(R.id.btnOwnPlaylistsTile)
        val btnFav = findViewById<View>(R.id.btnFavoritesTile)
        val showBoth = btnOwn.visibility == View.VISIBLE && btnFav.visibility == View.VISIBLE

        fun applyTileSize(view: View, width: Int, height: Int) {
            view.layoutParams = view.layoutParams.apply {
                this.width = width
                this.height = height
            }
        }

        when {
            showBoth -> {
                val halfWidth = (geometry.availableWidth - geometry.spacing) / 2
                applyTileSize(btnOwn, halfWidth, geometry.tileHeight)
                applyTileSize(btnFav, halfWidth, geometry.tileHeight)
                (btnFav.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                    lp.marginStart = geometry.spacing
                    btnFav.layoutParams = lp
                }
            }
            btnFav.visibility == View.VISIBLE -> {
                applyTileSize(btnFav, geometry.availableWidth, geometry.tileHeight)
                (btnFav.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                    lp.marginStart = 0
                    btnFav.layoutParams = lp
                }
            }
            btnOwn.visibility == View.VISIBLE -> {
                applyTileSize(btnOwn, geometry.availableWidth, geometry.tileHeight)
            }
        }
        val contentScale = computeContentDpScale()
        setupHomeBottomActionTiles(contentScale, HOME_BOTTOM_TILE_TEXT_SP)
    }

    private fun bindHomeTiles(items: List<HomeTileItem>, source: String = "generic", titleSizeSp: Float = 18f) {
        currentHomeTilesItems = items
        applyHomeGridContainerGeometry(source)
        if (rvHomeTiles.width <= 0) {
            rvHomeTiles.post {
                if (currentHomeTilesItems === items || currentHomeTilesItems == items) {
                    bindHomeTiles(currentHomeTilesItems, source, titleSizeSp)
                }
            }
            return
        }

        val geometry = computeHomeGridGeometry()
        val columns = geometry.columns
        val tileWidth = geometry.tileWidth
        val tileHeight = geometry.tileHeight
        val spacing = geometry.spacing

        rvHomeTiles.setPadding(0, rvHomeTiles.paddingTop, 0, rvHomeTiles.paddingBottom)
        rvHomeTiles.clipToPadding = false

        if (homeTilesColumnsApplied != columns) {
            val gridLayoutManager = GridLayoutManager(this, columns * 2)
            gridLayoutManager.spanSizeLookup = HomeTileSpanSizeLookup(columns) { currentHomeTilesItems.size }
            rvHomeTiles.layoutManager = gridLayoutManager
            homeTilesColumnsApplied = columns
        }

        if (homeTilesSpacingApplied != spacing || homeTilesSpacingDecoration == null) {
            homeTilesSpacingDecoration?.let { rvHomeTiles.removeItemDecoration(it) }
            homeTilesSpacingDecoration = HomeGridSpacingDecoration(spacing, columns)
            rvHomeTiles.addItemDecoration(homeTilesSpacingDecoration!!)
            homeTilesSpacingApplied = spacing
        }
        logDebug("NAV", "HOME_GRID_DECORATION_COUNT source=$source count=${rvHomeTiles.itemDecorationCount} spacing=$spacing")

        if (homeTilesAdapter == null || homeTilesWidthApplied != tileWidth || homeTilesHeightApplied != tileHeight || homeTilesTitleSizeApplied != titleSizeSp) {
            rvHomeTiles.setHasFixedSize(true)
            rvHomeTiles.itemAnimator = null
            homeTilesAdapter = HomeTilesAdapter(tileWidth, tileHeight, spacing, columns, titleSizeSp)
            homeTilesWidthApplied = tileWidth
            homeTilesHeightApplied = tileHeight
            homeTilesTitleSizeApplied = titleSizeSp
            rvHomeTiles.adapter = homeTilesAdapter
        }

        homeTilesAdapter?.submit(items)

        rvHomeTiles.post {
            val first = rvHomeTiles.getChildAt(0)
            val last = rvHomeTiles.getChildAt((rvHomeTiles.childCount - 1).coerceAtLeast(0))
            val firstLeft = first?.left?.plus(rvHomeTiles.left) ?: -1
            val lastRight = last?.right?.plus(rvHomeTiles.left) ?: -1
            logDebug(
                "NAV",
                "HOME_GRID_GEOMETRY source=$source leftAnchor=${geometry.leftAnchor} rightAnchor=${geometry.safeRight} availableWidth=${geometry.availableWidth} columns=${geometry.columns} tileWidth=${geometry.tileWidth} spacing=${geometry.spacing} firstTileLeft=$firstLeft lastTileRight=$lastRight rootWidth=${geometry.rootWidth}"
            )
            logHomeGridRealCoords(source, tileWidth, columns)
            applyHomeBottomTilesGeometry()
            if (!rvHomeTiles.hasFocus()) {
                first?.requestFocus()
            }
        }
    }

    private fun showThirdPartyTilesOnHome(thirdParty: List<PlaylistProfile>) {
        homePanel.visibility = View.VISIBLE
        homeStartCenterBlock.visibility = View.GONE
        showPlaylistPageHeader(false)
        findViewById<View>(R.id.homeBottomTilesRow).visibility = View.GONE
        tvHomeStartTitle.visibility = View.GONE
        tvHomeStartSubtitle.visibility = View.GONE
        homePlaylistTilesPanel.visibility = View.VISIBLE
        disableHomeCategoryBack("showThirdPartyTilesOnHome_before_show")
        applyHomeAppTitleStyle(settingsMode = true, settingsTitle = "категории", settingsTitle2 = "Свои плейлисты")
        enableHomeCategoryBack { showPlaylistPageOnHome() }
        updateHomeHeaderActions()
        val list = thirdParty.filter { it.enabled && it.value.isNotBlank() }
        bindHomeTiles(list.map { p -> HomeTileItem(p.name) {
            logDebug("NAV", "playlist_click name=${p.name}")
            hasStartedPlaybackFromChannelClick = false
            setSelectedPlaylistName(p.name)
            loadPlaylist(forceReload = false, showErrors = true, autoPlay = false)
        } }, source = "third_party")
    }

    private fun onBreadcrumbClick() {
        if (isSettingsModalVisible) {
            handleSettingsBackPress()
            return
        }
        val categoryBack = findViewById<View>(R.id.tvHomeCategoryBack)
        if (categoryBack.hasOnClickListeners()) {
            categoryBack.performClick()
        }
    }

    private fun onCategoryBreadcrumbClick() {
        if (isSettingsModalVisible) {
            handleSettingsBackPress()
            return
        }
        if (::gvHomeChannelList.isInitialized && gvHomeChannelList.visibility == View.VISIBLE) {
            gvHomeChannelList.setSelection(0)
            gvHomeChannelList.smoothScrollToPosition(0)
            gvHomeChannelList.post {
                gvHomeChannelList.getChildAt(0)?.requestFocus()
            }
            return
        }
        onBreadcrumbClick()
    }

    private fun goHomeFromLogoClick() {
        if (::channelListPanel.isInitialized && channelListPanel.visibility == View.VISIBLE) hideChannelListPanel()
        if (::epgPanel.isInitialized && epgPanel.visibility == View.VISIBLE) hideEpgPanel()
        if (isSettingsModalVisible) {
            hideSettingsScreen()
            return
        }
        val isAuthorizedUser = isAuthorizedUser()
        val hasEnabledThirdParty = hasEnabledThirdPartyPlaylists()
        if (isAuthorizedUser || hasEnabledThirdParty) showPlaylistPageOnHome() else showStartPage()
    }

    private fun showPlaylistPageHeader(showWelcome: Boolean, showTitle: Boolean = showWelcome) {
        val name = prefs.getString(PREF_USER_NAME, "") ?: ""
        tvHomeWelcome.visibility = if (showWelcome && name.isNotBlank()) View.VISIBLE else View.GONE
        tvHomeWelcome.text = "Добро пожаловать, $name!"
        tvPlaylistPageTitle.visibility = if (showTitle) View.VISIBLE else View.GONE
        tvPlaylistPageSubtitle.visibility = if (showTitle) View.VISIBLE else View.GONE
        (homePlaylistTilesPanel.layoutParams as? ConstraintLayout.LayoutParams)?.let { lp ->
            if (showTitle) {
                lp.topToBottom = R.id.tvPlaylistPageSubtitle
                lp.topMargin = resources.getDimensionPixelSize(R.dimen.home_grid_margin_top)
            } else {
                lp.topToBottom = R.id.tvHomeWelcome
                lp.topMargin = dpToPx(24)
            }
            homePlaylistTilesPanel.layoutParams = lp
        }
    }

    private fun showPlaylistPageOnHome(source: String = "playlist_page") {
        homePanel.visibility = View.VISIBLE
        homeStartCenterBlock.visibility = View.GONE
        hidePlayerChromeFully()
        showPlaylistPageHeader(true)
        tvHomeStartTitle.visibility = View.GONE
        tvHomeStartSubtitle.visibility = View.GONE
        tvHomeAppTitle.visibility = View.VISIBLE
        tvHomeSystemTime.visibility = View.VISIBLE
        ivHomePower.visibility = View.VISIBLE
        listOf(ivHomeProfile, ivHomeSettings, ivHomePower).forEach { icon ->
            icon.alpha = 1f
            icon.scaleX = 1f
            icon.scaleY = 1f
            icon.isFocusable = false
        }
        homePlaylistTilesPanel.visibility = View.VISIBLE
        gvHomeChannelList.visibility = View.GONE
        gvHomeChannelList.adapter = null
        applyHomeAppTitleStyle(settingsMode = false)
        disableHomeCategoryBack("showPlaylistPageOnHome_end")
        updateHomeHeaderActions()
        val token = (prefs.getString(PREF_USER_TOKEN, "") ?: "").trim()
        val thirdParty = getThirdPartyPlaylistProfiles().filter { it.enabled && it.value.isNotBlank() }
        val known = getKnownServiceNames() + "Избранные"
        val services = if (token.isNotBlank()) {
            getPlaylistProfiles().filter { it.name in known && it.name != "Избранные" && it.enabled && it.value.isNotBlank() }
        } else {
            emptyList()
        }
        bindHomeTiles(services.map { p ->
            HomeTileItem(p.name) {
                logDebug("NAV", "playlist_click name=${p.name}")
                hasStartedPlaybackFromChannelClick = false
                setSelectedPlaylistName(p.name)
                loadPlaylist(forceReload = false, showErrors = true, autoPlay = false)
            }
        }, source = source, titleSizeSp = 18f)

        val favoritesProfile = getPlaylistProfiles().firstOrNull { it.name == "Избранные" && it.enabled && it.value.isNotBlank() }
        val bottomRow = findViewById<View>(R.id.homeBottomTilesRow)
        val btnOwnPlaylistsTile = findViewById<View>(R.id.btnOwnPlaylistsTile)
        val btnFavoritesTile = findViewById<View>(R.id.btnFavoritesTile)
        val showOwnPlaylists = thirdParty.isNotEmpty()
        val showFavorites = favoritesProfile != null
        bottomRow.visibility = if (showOwnPlaylists || showFavorites) View.VISIBLE else View.GONE
        btnOwnPlaylistsTile.visibility = if (showOwnPlaylists) View.VISIBLE else View.GONE
        btnFavoritesTile.visibility = if (showFavorites) View.VISIBLE else View.GONE
        btnOwnPlaylistsTile.setOnClickListener { showThirdPartyTilesOnHome(thirdParty) }
        btnFavoritesTile.setOnClickListener {
            logDebug("NAV", "playlist_click name=Избранные")
            hasStartedPlaybackFromChannelClick = false
            setSelectedPlaylistName("Избранные")
            loadPlaylist(forceReload = false, showErrors = true, autoPlay = false)
        }
        if (homePlaylistTilesPanel.width > 0) {
            applyHomeBottomTilesGeometry()
        } else {
            homePlaylistTilesPanel.post { applyHomeBottomTilesGeometry() }
        }
    }


    private fun computeCategoryGridColumnCount(): Int {
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val uiMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK
        val isTv = uiMode == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
        return when {
            isTv -> (widthDp / 180f).toInt().coerceIn(5, 7)
            widthDp >= 900f -> 4
            widthDp >= 600f -> 3
            else -> 2
        }
    }

    private fun showCategoryTilesOnHome(playlistName: String, sourceChannels: List<Channel>) {
        val grouped = sourceChannels.groupBy { it.groupTitle?.trim().takeUnless { g -> g.isNullOrBlank() } ?: "Без категории" }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
            .filterKeys { it != "{region_name}" }
        showCategoryTilesOnHome(playlistName, grouped)
    }

    private fun showCategoryTilesOnHome(
        playlistName: String,
        groupedCategories: Map<String, List<Channel>>
    ) {
        logDebug("PLAYLIST_FLOW", "OPEN_CATEGORY_SCREEN playlist=$playlistName")
        homePanel.visibility = View.VISIBLE
        homeStartCenterBlock.visibility = View.GONE
        topInfoPanel.visibility = View.GONE
        topGradientOverlay.visibility = View.GONE
        controlsPanel.visibility = View.GONE
        showPlaylistPageHeader(showWelcome = true, showTitle = false)
        findViewById<View>(R.id.homeBottomTilesRow).visibility = View.GONE
        tvHomeStartTitle.visibility = View.GONE
        tvHomeStartSubtitle.visibility = View.GONE
        tvHomeAppTitle.visibility = View.VISIBLE
        tvHomeSystemTime.visibility = View.VISIBLE
        ivHomePower.visibility = View.VISIBLE
        gvHomeChannelList.visibility = View.GONE
        gvHomeChannelList.adapter = null
        homePlaylistTilesPanel.visibility = View.VISIBLE
        applyHomeAppTitleStyle(settingsMode = true, settingsTitle = "категории", settingsTitle2 = playlistName)
        enableHomeCategoryBack { showPlaylistPageOnHome() }
        updateHomeHeaderActions()

        val allChannels = groupedCategories.values.flatten()
        fun categoryGroupOrder(name: String): Int {
            val ch = name.firstOrNull() ?: return 2
            return when {
                ch in 'А'..'я' || ch == 'Ё' || ch == 'ё' -> 0
                ch in 'A'..'Z' || ch in 'a'..'z' -> 1
                else -> 2
            }
        }
        val grouped = linkedMapOf<String, List<Channel>>()
        grouped["Все каналы"] = allChannels
        groupedCategories
            .filterKeys { it != "Все каналы" }
            .entries
            .sortedWith(compareBy<Map.Entry<String, List<Channel>>> { categoryGroupOrder(it.key) }
                .thenBy { it.key.lowercase(Locale.getDefault()) })
            .forEach { (key, value) -> grouped[key] = value }
        cachedCategoryGroups = grouped
        logDebug("PLAYLIST_FLOW", "CATEGORY_GROUPS count=${grouped.size}")
        logDebug("PLAYLIST_FLOW", "CATEGORY_GROUPS names=${grouped.keys.joinToString(separator = " | ")}")
        bindCategoryTilesOnHome()
    }

    private fun bindCategoryTilesOnHome() {
        val categoryNames = cachedCategoryGroups.keys.toList()
        bindHomeTiles(categoryNames.map { category ->
            HomeTileItem(category) {
                logDebug("NAV", "CATEGORY_TILE_CLICK_RECEIVED name=$category")
                if (categoryOpenInProgress) {
                    logDebug("NAV", "CLICK_BLOCKED reason=category_open_in_progress")
                    return@HomeTileItem
                }
                categoryOpenInProgress = true
                selectedCategoryName = category
                logDebug("NAV", "CATEGORY_OPEN_CHANNELS_START name=$category")
                val startedAt = System.currentTimeMillis()
                showAppLoadingSpinner()
                val filtered = cachedCategoryGroups[category].orEmpty()
                homePlaylistTilesPanel.visibility = View.GONE
                val remaining = (220L - (System.currentTimeMillis() - startedAt)).coerceAtLeast(0L)
                handler.postDelayed({
                    showHomeChannelList(category, filtered)
                    hideAppLoadingSpinner()
                    logDebug("NAV", "CATEGORY_OPEN_CHANNELS_DONE channelsCount=${filtered.size}")
                    categoryOpenInProgress = false
                }, remaining)
            }
        }, source = "categories")
    }

    private fun returnToCategoryTilesOnHome() {
        showPlaylistPageHeader(false)
        gvHomeChannelList.visibility = View.GONE
        gvHomeChannelList.adapter = null
        homePlaylistTilesPanel.visibility = View.VISIBLE
        // Favorites / own-playlists row belongs only on the main playlist home.
        findViewById<View>(R.id.homeBottomTilesRow).visibility = View.GONE
        val playlistName = getSelectedPlaylistName()
        applyHomeAppTitleStyle(settingsMode = true, settingsTitle = "категории", settingsTitle2 = playlistName)
        enableHomeCategoryBack { showPlaylistPageOnHome() }
        bindCategoryTilesOnHome()
    }

    private fun findNextProgram(programs: List<Program>, afterMs: Long): Program? =
        programs.filter { it.start >= afterMs }.minByOrNull { it.start }

    private fun showHomeChannelList(category: String, channelsForCategory: List<Channel>) {
        hidePlayerChromeFully()
        showPlaylistPageHeader(showWelcome = true, showTitle = false)
        val lastUrl = prefs.getString(PREF_LAST_CHANNEL_URL, null)
        val lastName = prefs.getString(PREF_LAST_CHANNEL_NAME, null)
        channels.clear()
        channels.addAll(channelsForCategory)
        val highlightIdx = when {
            !lastUrl.isNullOrBlank() -> channelsForCategory.indexOfFirst {
                it.url == lastUrl && (lastName.isNullOrBlank() || it.name == lastName)
            }
            else -> -1
        }
        selectedCategoryName = category
        lastChannelListCategory = category
        applyHomeAppTitleStyle(
            settingsMode = true,
            settingsTitle = getSelectedPlaylistName(),
            settingsTitle2 = category
        )
        enableHomeCategoryBack { returnToCategoryTilesOnHome() }
        homePlaylistTilesPanel.visibility = View.GONE
        findViewById<View>(R.id.homeBottomTilesRow).visibility = View.GONE
        gvHomeChannelList.visibility = View.VISIBLE

        if (selectedEpgSources.isNotEmpty() && !epgFetchInProgress) {
            val hasCoverage = synchronized(epgDataLock) {
                channelsForCategory.any { ch ->
                    listOfNotNull(ch.tvgId, ch.tvgName, ch.name).any { key ->
                        epgData.containsKey(key.lowercase().trim())
                    }
                }
            }
            if (!hasCoverage) {
                logDebug("EPG_DEBUG", "showHomeChannelList: no EPG coverage for category=$category, forcing fetch")
                fetchEpgSources(selectedEpgSources.toList())
            } else {
                ensureEpgLoadedLazy()
            }
        }

        gvHomeChannelList.adapter = object : ArrayAdapter<Channel>(this, 0, channelsForCategory) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val itemView = convertView
                    ?: layoutInflater.inflate(R.layout.item_home_channel_card, parent, false)
                val channel = channelsForCategory[position]
                val ivLogo = itemView.findViewById<ImageView>(R.id.cardLogo)
                val tvName = itemView.findViewById<TextView>(R.id.cardName)
                val tvCurrent = itemView.findViewById<TextView>(R.id.cardCurrentProgram)
                val archiveBadge = itemView.findViewById<View>(R.id.cardArchiveBadge)

                tvName.text = channel.name
                tvName.isSelected = true
                loadLogoWithGlide(channel.logoFromEpg ?: channel.logoFromPlaylist, ivLogo)

                archiveBadge.visibility =
                    if (channel.catchupDays > 0 && !channel.catchupSource.isNullOrBlank()) View.VISIBLE else View.GONE

                itemView.setBackgroundResource(
                    if (highlightIdx >= 0 && position == highlightIdx) R.drawable.channel_grid_tile_bg_current
                    else R.drawable.channel_grid_tile_bg
                )

                val now = System.currentTimeMillis()
                val realPrograms = getProgramsForChannel(channel)
                val displayPrograms = realPrograms.ifEmpty { buildArchivePlaceholderPrograms(channel) }
                val cur = displayPrograms.find { now in it.start until it.stop }
                if (cur != null) {
                    tvCurrent.text = cur.title
                    tvCurrent.visibility = View.VISIBLE
                } else {
                    tvCurrent.text = epgUnavailableMessage()
                    tvCurrent.visibility = View.VISIBLE
                }

                itemView.setOnClickListener {
                    logDebug("NAV", "home_channel_card_click name=${channel.name}")
                    homeReturnTarget = HomeReturnTarget.CHANNEL_LIST
                    lastChannelListCategory = category
                    currentChannelIndex = position
                    playChannel(forcePlay = true, reason = PlayerOpenReason.CHANNEL_CLICK)
                }
                return itemView
            }
        }
        gvHomeChannelList.onItemClickListener =
            AdapterView.OnItemClickListener { _, view, position, _ ->
                logDebug("NAV", "DPAD_OK_onItemClick gvHomeChannelList position=$position")
                view.performClick()
            }
        gvHomeChannelList.post {
            val focusIdx = if (highlightIdx >= 0) highlightIdx else 0
            gvHomeChannelList.setSelection(focusIdx)
            gvHomeChannelList.requestFocus()
            gvHomeChannelList.post {
                val child = gvHomeChannelList.getChildAt(
                    focusIdx - gvHomeChannelList.firstVisiblePosition
                )
                child?.requestFocus()
            }
        }
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
            showAppToast("Добавьте плейлист в настройках")
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
                loadPlaylist(forceReload = false, showErrors = true, autoPlay = false)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun openFavoritesByToken() {
        val selected = getPlaylistProfiles().firstOrNull { it.name == getSelectedPlaylistName() }
            ?: getPlaylistProfiles().firstOrNull()
        if (selected == null || selected.type != "token" || selected.value.isBlank()) {
            showAppToast("Введите токен в настройках", 3500L)
            showSettingsDialog()
            return
        }
        hideStartPage()
        loadPlaylist(forceReload = true, showErrors = true, autoPlay = false)
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this) {
            if (::epgPanel.isInitialized && epgPanel.visibility == View.VISIBLE) {
                hideEpgPanel()
                return@addCallback
            }
            if (::channelListPanel.isInitialized && channelListPanel.visibility == View.VISIBLE) {
                hideChannelListPanel()
                return@addCallback
            }
            if (::playerSettingsOverlay.isInitialized && playerSettingsOverlay.visibility == View.VISIBLE) {
                handleSettingsBackPress()
                return@addCallback
            }
            if (homeSettingsScreen.visibility == View.VISIBLE) {
                handleSettingsBackPress()
                return@addCallback
            }
            if (homePanel.visibility == View.VISIBLE) {
                if (::gvHomeChannelList.isInitialized && gvHomeChannelList.visibility == View.VISIBLE) {
                    returnToCategoryTilesOnHome()
                    return@addCallback
                }
                if (homePlaylistTilesPanel.visibility == View.VISIBLE && homeCategoryBackHandler != null) {
                    homeCategoryBackHandler?.invoke()
                    return@addCallback
                }
                if (tvHomeCategoryBack.visibility == View.VISIBLE && tvHomeCategoryBack.isClickable) {
                    tvHomeCategoryBack.performClick()
                    return@addCallback
                }
                // Самый верхний экран (список плейлистов) — сразу спрашиваем про выход,
                // без промежуточного пустого экрана (он и вызывал "пропадание" плиток).
            } else {
                if (inputNumber.isNotEmpty()) {
                    inputNumber = ""
                    handler.removeCallbacks(channelSwitchRunnable)
                    seekStatusHoldUntilMs = 0L
                    restoreChannelHeaderAfterNumberInput()
                    return@addCallback
                }
                if (controlsPanel.visibility == View.VISIBLE || topInfoPanel.visibility == View.VISIBLE) {
                    hideUI()
                    return@addCallback
                }
                exitPlayerToPlaylist()
                return@addCallback
            }
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
        showAppToast("Нажмите ещё раз для выхода!", 1800L)
    }

    private fun closeAppCompletely() {
        cancelSleepTimer()
        stopPlayback()
        finish()
    }

    private fun showTimerDialog() {
        val options = arrayOf(10, 30, 60, 90, 120)
        val view = layoutInflater.inflate(R.layout.dialog_timer, null)
        val spinner = view.findViewById<Spinner>(R.id.spTimerMinutes)
        val btnApply = view.findViewById<TextView>(R.id.btnApplyTimer)
        val btnClose = view.findViewById<TextView>(R.id.btnCloseTimer)

        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            options.map { "$it минут" }
        )

        val dialog =
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
                .setView(view)
                .create()

        btnApply.setOnClickListener {
            val idx = spinner.selectedItemPosition.coerceIn(options.indices)
            val minutes = options[idx]
            prefs.edit().putInt(PREF_SLEEP_TIMER_MINUTES, minutes).apply()
            startSleepTimer(minutes)
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
        cancelSleepTimer(clearPreference = false)
        timerEndAtMillis = System.currentTimeMillis() + minutes * 60_000L
        handler.postDelayed(timerFinishRunnable, minutes * 60_000L)
        handler.postDelayed(timerWarnRunnable, (minutes * 60_000L - 30_000L).coerceAtLeast(0L))
    }

    private fun showTimerWarning() {
        timerWarningPanel.visibility = View.VISIBLE
        handler.postDelayed({ timerWarningPanel.visibility = View.GONE }, 30_000L)
    }

    private fun cancelSleepTimer(clearPreference: Boolean = true) {
        timerEndAtMillis = 0L
        handler.removeCallbacks(timerFinishRunnable)
        handler.removeCallbacks(timerWarnRunnable)
        timerWarningPanel.visibility = View.GONE
        if (clearPreference) {
            prefs.edit().putInt(PREF_SLEEP_TIMER_MINUTES, 0).apply()
        }
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

        val dialog =
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
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
                golosTypeface?.let { holder.tvName.typeface = Typeface.create(it, Typeface.NORMAL) }

                val pList = getProgramsForDisplay(channel)
                val cur = pList.find { System.currentTimeMillis() in it.start until it.stop }
                holder.tvEpgItem.text = cur?.title ?: "Нет программы"
                holder.tvEpgItem.isSelected = true

                loadLogoWithGlide(
                    channel.logoFromEpg ?: channel.logoFromPlaylist,
                    holder.ivLogoItem
                )

                val startChannel = View.OnClickListener {
                    logDebug("NAV", "channel_click name=${channel.name}")
                    currentChannelIndex = position
                    playChannel(forcePlay = true, reason = PlayerOpenReason.CHANNEL_CLICK)
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

    private var epgPanelChannel: Channel? = null

    private fun toggleChannelListPanel() {
        if (channelListPanel.visibility == View.VISIBLE) {
            hideChannelListPanel()
        } else {
            showChannelListPanel()
        }
    }

    private fun setPlayerVideoVisible(visible: Boolean) {
        if (::videoLayout.isInitialized) {
            videoLayout.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    private fun setPlayerOverlayScrimVisible(visible: Boolean) {
        if (!::epgDismissScrim.isInitialized) return
        if (visible) {
            epgDismissScrim.visibility = View.VISIBLE
        } else if (!::epgPanel.isInitialized || epgPanel.visibility != View.VISIBLE) {
            epgDismissScrim.visibility = View.GONE
        }
    }

    private fun isPlayerOverlayOpen(): Boolean {
        return (::channelListPanel.isInitialized && channelListPanel.visibility == View.VISIBLE) ||
            (::epgPanel.isInitialized && epgPanel.visibility == View.VISIBLE)
    }

    private fun pausePlaybackStallWatchdogForOverlay() {
        handler.removeCallbacks(playbackFreezeWatchdogRunnable)
    }

    private fun resumePlaybackStallWatchdogIfNeeded() {
        if (mediaPlayer != null && homePanel.visibility != View.VISIBLE && !isPlayerOverlayOpen()) {
            // Closing EPG/channel list must not look like a multi-second progress stall.
            resetPlaybackProgressBaseline()
            handler.removeCallbacks(playbackFreezeWatchdogRunnable)
            handler.postDelayed(playbackFreezeWatchdogRunnable, 2000L)
        }
    }

    private fun resetPlaybackProgressBaseline(extendGrace: Boolean = false) {
        val player = mediaPlayer
        lastPlaybackPositionMs = player?.currentPosition ?: lastPlaybackPositionMs
        lastProgressWallClockMs = System.currentTimeMillis()
        bufferingSinceMs = 0L
        if (extendGrace) {
            stallWatchdogGraceUntilMs =
                System.currentTimeMillis() + PLAYBACK_STALL_GRACE_AFTER_START_MS
        }
    }

    private fun armPlaybackFreezeWatchdog(delayMs: Long = 4000L, withStartGrace: Boolean = false) {
        if (withStartGrace) {
            stallWatchdogGraceUntilMs =
                System.currentTimeMillis() + PLAYBACK_STALL_GRACE_AFTER_START_MS
        }
        handler.removeCallbacks(playbackFreezeWatchdogRunnable)
        handler.postDelayed(playbackFreezeWatchdogRunnable, delayMs)
    }

    private fun setupStartModeRemoteToggle(tbStartMode: ToggleButton, itemStartModeRow: View) {
        tbStartMode.isFocusable = true
        tbStartMode.isFocusableInTouchMode = false
        fun toggleStartMode() {
            tbStartMode.isChecked = !tbStartMode.isChecked
        }
        itemStartModeRow.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    toggleStartMode()
                    true
                }
                else -> false
            }
        }
        tbStartMode.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    toggleStartMode()
                    true
                }
                else -> false
            }
        }
    }

    private fun hideChannelListPanel() {
        channelListPanel.visibility = View.GONE
        gvChannelListPanel.adapter = null
        channelListProgramTitles = emptyMap()
        setPlayerOverlayScrimVisible(false)
        hideUI()
        resumePlaybackStallWatchdogIfNeeded()
    }

    private fun bindChannelListPanelAdapter() {
        gvChannelListPanel.adapter = object : ArrayAdapter<Channel>(this@MainActivity, 0, channels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val holder: ChannelGridItemViewHolder
                val itemView: View
                if (convertView == null) {
                    itemView = layoutInflater.inflate(R.layout.item_channel_grid, parent, false)
                    holder = ChannelGridItemViewHolder(
                        tvNumber = itemView.findViewById(R.id.itemNumber),
                        tvName = itemView.findViewById(R.id.itemName),
                        tvCurrentProgram = itemView.findViewById(R.id.itemCurrentProgram),
                        ivLogo = itemView.findViewById(R.id.itemLogo),
                        archiveBadge = itemView.findViewById(R.id.itemArchiveBadge)
                    )
                    itemView.tag = holder
                } else {
                    itemView = convertView
                    holder = convertView.tag as ChannelGridItemViewHolder
                }

                val channel = channels[position]
                holder.tvNumber.text = (position + 1).toString()
                holder.tvName.text = channel.name
                holder.tvName.isSelected = true
                golosTypeface?.let { holder.tvName.typeface = Typeface.create(it, 500, false) }
                loadLogoWithGlide(
                    channel.logoFromEpg ?: channel.logoFromPlaylist,
                    holder.ivLogo
                )

                itemView.setBackgroundResource(
                    if (position == currentChannelIndex) R.drawable.channel_grid_tile_bg_current
                    else R.drawable.channel_grid_tile_bg
                )

                holder.tvCurrentProgram.text = channelListProgramTitles[position].orEmpty()
                holder.tvCurrentProgram.visibility = View.VISIBLE
                holder.archiveBadge.visibility =
                    if (channel.catchupDays > 0 && !channel.catchupSource.isNullOrBlank()) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }

                itemView.setOnClickListener {
                    logDebug("NAV", "channel_grid_click name=${channel.name}")
                    currentChannelIndex = position
                    playChannel(forcePlay = true, reason = PlayerOpenReason.CHANNEL_CLICK)
                    hideChannelListPanel()
                }
                return itemView
            }
        }
        gvChannelListPanel.onItemClickListener =
            AdapterView.OnItemClickListener { _, view, position, _ ->
                logDebug("NAV", "DPAD_OK_onItemClick gvChannelListPanel position=$position")
                view.performClick()
            }
        syncChannelListPanelBounds()
        gvChannelListPanel.setSelection(currentChannelIndex)
        gvChannelListPanel.requestFocus()
    }

    private fun showChannelListPanel() {
        if (channels.isEmpty()) return
        if (::epgPanel.isInitialized && epgPanel.visibility == View.VISIBLE) {
            hideEpgPanel(restorePlayerUi = false)
        }
        tvChannelListTitle.text = "Список каналов: ${getSelectedPlaylistName()}"
        setPlayerOverlayScrimVisible(true)
        topInfoPanel.visibility = View.GONE
        topGradientOverlay.visibility = View.GONE
        controlsPanel.visibility = View.GONE
        handler.removeCallbacks(hideUiRunnable)
        pausePlaybackStallWatchdogForOverlay()
        channelListPanel.visibility = View.VISIBLE
        channelListPanel.post {
            thread(name = "channel-list-prep") {
                val titles = channels.mapIndexed { index, ch ->
                    index to getCurrentProgramTitleForChannelList(ch)
                }.toMap()
                handler.post {
                    if (channelListPanel.visibility != View.VISIBLE) return@post
                    channelListProgramTitles = titles
                    bindChannelListPanelAdapter()
                }
            }
        }
    }


    private var epgPanelDateKeys: List<String> = emptyList()
    private var epgPanelSelectedDate: String = ""
    private var epgPanelProgramsByDate: Map<String, List<Program>> = emptyMap()

    private fun hideEpgPanel(restorePlayerUi: Boolean = true) {
        logMemoryStats("epg_panel_hide")
        epgPanel.visibility = View.GONE
        epgDatePickedByUser = false
        if (::epgDismissScrim.isInitialized) epgDismissScrim.visibility = View.GONE
        lvEpgPrograms.adapter = null
        // Reset stall baseline BEFORE arming watchdog — otherwise closing EPG looks like a freeze.
        resetPlaybackProgressBaseline()
        if (restorePlayerUi) {
            // Same as channel-list dismiss: return to clean watching chrome, not a forced reload UI.
            hideUI()
        }
        resumePlaybackStallWatchdogIfNeeded()
    }

    private fun toggleEpgPanel() {
        if (epgPanel.visibility == View.VISIBLE) {
            hideEpgPanel()
        } else {
            epgDatePickedByUser = false
            showEpgPanel()
        }
    }

    private fun syncOverlayPanelBounds(panel: View) {
        // EPG и список каналов — почти fullscreen с фиксированными отступами из XML.
        if (panel.id == R.id.epgPanel || panel.id == R.id.channelListPanel) return
        val lp = panel.layoutParams as? FrameLayout.LayoutParams ?: return
        val gap = (8 * resources.displayMetrics.density).toInt()
        val topMargin = topInfoPanel.bottom + gap
        val bottomMargin = controlsPanel.height + gap
        if (topMargin <= 0 || bottomMargin <= 0) return
        if (lp.topMargin == topMargin && lp.bottomMargin == bottomMargin) return
        lp.topMargin = topMargin
        lp.bottomMargin = bottomMargin
        panel.layoutParams = lp
    }

    private fun syncChannelListPanelBounds() {
        if (!::channelListPanel.isInitialized) return
        val margin = resources.getDimensionPixelSize(R.dimen.player_epg_panel_margin)
        val lp = channelListPanel.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.topMargin == margin && lp.bottomMargin == margin &&
            lp.marginStart == margin && lp.marginEnd == margin
        ) return
        lp.topMargin = margin
        lp.bottomMargin = margin
        lp.marginStart = margin
        lp.marginEnd = margin
        channelListPanel.layoutParams = lp
    }

    private fun resolveEpgDefaultDateKey(dateKeys: List<String>): String {
        if (dateKeys.isEmpty()) return ""
        val fmt = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        val todayKey = fmt.format(Date())
        dateKeys.firstOrNull { it == todayKey }?.let { return it }
        val todayMs = runCatching { fmt.parse(todayKey)?.time }.getOrNull() ?: return dateKeys.last()
        return dateKeys
            .mapNotNull { key -> fmt.parse(key)?.time?.let { key to it } }
            .filter { (_, ms) -> ms <= todayMs }
            .maxByOrNull { (_, ms) -> ms }
            ?.first
            ?: dateKeys.last()
    }

    private fun refreshOpenOverlayPanelsAfterEpgUpdate() {
        if (::epgPanel.isInitialized && epgPanel.visibility == View.VISIBLE) {
            val keepUserDate = epgDatePickedByUser
            val keepDate = epgPanelSelectedDate
            showEpgPanel()
            if (keepUserDate && keepDate.isNotEmpty() && epgPanelDateKeys.contains(keepDate)) {
                epgPanelSelectedDate = keepDate
                renderEpgDateChips()
                renderEpgProgramsForSelectedDate()
            }
        }
        if (::channelListPanel.isInitialized && channelListPanel.visibility == View.VISIBLE) {
            showChannelListPanel()
        }
    }

    private fun syncEpgPanelBounds() {
        if (!::epgPanel.isInitialized) return
        val margin = resources.getDimensionPixelSize(R.dimen.player_epg_panel_margin)
        val lp = epgPanel.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.topMargin == margin && lp.bottomMargin == margin &&
            lp.marginStart == margin && lp.marginEnd == margin
        ) return
        lp.topMargin = margin
        lp.bottomMargin = margin
        lp.marginStart = margin
        lp.marginEnd = margin
        epgPanel.layoutParams = lp
    }

    private fun showEpgPanel() {
        logMemoryStats("epg_panel_show_start")
        if (::channelListPanel.isInitialized && channelListPanel.visibility == View.VISIBLE) {
            channelListPanel.visibility = View.GONE
            gvChannelListPanel.adapter = null
        }
        val ch = channels.getOrNull(currentChannelIndex) ?: return
        epgPanelChannel = ch
        if (!epgDatePickedByUser) {
            epgPanelSelectedDate = ""
        }

        if (::epgDismissScrim.isInitialized) epgDismissScrim.visibility = View.VISIBLE
        epgPanel.visibility = View.VISIBLE
        topInfoPanel.visibility = View.GONE
        topGradientOverlay.visibility = View.GONE
        controlsPanel.visibility = View.GONE
        handler.removeCallbacks(hideUiRunnable)
        pausePlaybackStallWatchdogForOverlay()

        epgPanel.post {
            thread(name = "epg-panel-prep") {
                val realPrograms = getProgramsForChannel(ch)
                val programsSource = when {
                    realPrograms.isNotEmpty() -> realPrograms
                    else -> {
                        val archive = buildArchivePlaceholderPrograms(ch)
                        if (archive.isNotEmpty()) archive else buildPlaceholderPrograms()
                    }
                }
                val (dateKeys, programsByDate) = buildEpgPanelDateModel(programsSource)
                val selectedDate = if (epgPanelSelectedDate.isEmpty() || !dateKeys.contains(epgPanelSelectedDate)) {
                    resolveEpgDefaultDateKey(dateKeys)
                } else {
                    epgPanelSelectedDate
                }

                handler.post {
                    if (epgPanel.visibility != View.VISIBLE) return@post
                    tvEpgEmptyState.visibility = View.GONE
                    epgDateRow.visibility = View.VISIBLE
                    lvEpgPrograms.visibility = View.VISIBLE
                    epgPanelProgramsByDate = programsByDate
                    epgPanelDateKeys = dateKeys
                    epgPanelSelectedDate = selectedDate
                    renderEpgDateChips()
                    renderEpgProgramsForSelectedDate()
                    syncEpgPanelBounds()
                    scrollToSelectedEpgDateChip()
                    lvEpgPrograms.requestFocus()
                }
            }
        }
    }

    private fun renderEpgDateChips() {
        epgDateContainer.removeAllViews()
        val density = resources.displayMetrics.density
        val paddingH = (16 * density).toInt()
        val marginEnd = (10 * density).toInt()
        val chipHeight = (36 * density).toInt()
        epgPanelDateKeys.forEach { dateKey ->
            val chip = TextView(this).apply {
                text = dateKey
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(paddingH, 0, paddingH, 0)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = golosTypefaceSemiBold ?: Typeface.create(typeface, Typeface.NORMAL)
                background = getDrawable(R.drawable.epg_date_chip_bg)
                isFocusable = true
                isClickable = true
                isSelected = dateKey == epgPanelSelectedDate
                setOnClickListener {
                    epgDatePickedByUser = true
                    epgPanelSelectedDate = dateKey
                    for (i in 0 until epgDateContainer.childCount) {
                        epgDateContainer.getChildAt(i).isSelected = false
                    }
                    isSelected = true
                    renderEpgProgramsForSelectedDate()
                    updateEpgDateNavButtons()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                chipHeight
            )
            lp.marginEnd = marginEnd
            epgDateContainer.addView(chip, lp)
        }

        btnEpgDatePrev.setOnClickListener { shiftEpgDate(-1) }
        btnEpgDateNext.setOnClickListener { shiftEpgDate(1) }
        updateEpgDateNavButtons()
        scrollToSelectedEpgDateChip()
    }

    private fun scrollToSelectedEpgDateChip() {
        val selectedIdx = epgPanelDateKeys.indexOf(epgPanelSelectedDate)
        if (selectedIdx < 0) return
        epgDateContainer.post {
            val selectedChip = epgDateContainer.getChildAt(selectedIdx) ?: return@post
            val scrollX =
                (selectedChip.left - (hsvEpgDates.width - selectedChip.width) / 2).coerceAtLeast(0)
            hsvEpgDates.smoothScrollTo(scrollX, 0)
        }
    }

    private fun updateEpgDateNavButtons() {
        val selectedIdx = epgPanelDateKeys.indexOf(epgPanelSelectedDate)
        btnEpgDatePrev.alpha = if (selectedIdx <= 0) 0.4f else 1f
        btnEpgDateNext.alpha =
            if (selectedIdx < 0 || selectedIdx >= epgPanelDateKeys.lastIndex) 0.4f else 1f
    }

    private fun shiftEpgDate(step: Int) {
        logMemoryStats("epg_shift_date")
        val currentIdx = epgPanelDateKeys.indexOf(epgPanelSelectedDate)
        val nextIdx = currentIdx + step
        if (nextIdx !in epgPanelDateKeys.indices) return
        epgDatePickedByUser = true
        epgPanelSelectedDate = epgPanelDateKeys[nextIdx]
        for (i in 0 until epgDateContainer.childCount) {
            val chip = epgDateContainer.getChildAt(i) as? TextView ?: continue
            chip.isSelected = chip.text == epgPanelSelectedDate
        }
        renderEpgProgramsForSelectedDate()
        updateEpgDateNavButtons()
        scrollToSelectedEpgDateChip()
    }

    private class EpgProgramRowHolder(row: View) {
        val tvTime: TextView = row.findViewById(R.id.tvProgramTime)
        val tvTitle: TextView = row.findViewById(R.id.tvProgramTitle)
        val tvDesc: TextView = row.findViewById(R.id.tvProgramDesc)
        val tvBadge: TextView = row.findViewById(R.id.tvNowOnAirBadge)
        val tvArchiveBadge: TextView = row.findViewById(R.id.tvArchiveBadge)
    }

    private fun renderEpgProgramsForSelectedDate() {
        val ch = epgPanelChannel ?: return
        val items = epgPanelProgramsByDate[epgPanelSelectedDate].orEmpty().sortedBy { it.start }
        logDebug("EPG_DEBUG", "renderEpgProgramsForSelectedDate itemsCount=${items.size} date=$epgPanelSelectedDate")
        logMemoryStats("epg_render_list")
        val now = System.currentTimeMillis()
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        lvEpgPrograms.adapter = object : ArrayAdapter<Program>(this, 0, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row: View
                val holder: EpgProgramRowHolder
                if (convertView == null) {
                    row = layoutInflater.inflate(R.layout.item_epg_program, parent, false)
                    holder = EpgProgramRowHolder(row)
                    row.tag = holder
                } else {
                    row = convertView
                    holder = row.tag as EpgProgramRowHolder
                }
                val item = getItem(position) ?: return row
                holder.tvTime.text = timeFormat.format(Date(item.start))
                holder.tvTitle.text = item.title
                holder.tvTitle.isSelected = true
                golosTypeface?.let {
                    holder.tvTitle.typeface = Typeface.create(it, 500, false)
                    holder.tvTime.typeface = Typeface.create(it, 500, false)
                    holder.tvDesc.typeface = Typeface.create(it, 400, false)
                }
                if (item.desc.isNotBlank()) {
                    holder.tvDesc.text = item.desc
                    holder.tvDesc.visibility = View.VISIBLE
                } else {
                    holder.tvDesc.visibility = View.GONE
                }
                val isNow = now in item.start until item.stop
                val archiveAvailable = isArchiveAvailable(ch, item)
                holder.tvBadge.visibility = if (isNow) View.VISIBLE else View.GONE
                holder.tvArchiveBadge.visibility = if (archiveAvailable) View.VISIBLE else View.GONE
                row.alpha = if (isNow) 1f else 0.9f
                row.setOnClickListener {
                    if (!archiveAvailable) {
                        showAppToast("Архив недоступен для этой передачи")
                        return@setOnClickListener
                    }
                    playArchiveProgram(ch, item)
                    hideEpgPanel()
                }
                return row
            }
        }
        lvEpgPrograms.onItemClickListener =
            AdapterView.OnItemClickListener { _, view, position, _ ->
                logDebug("NAV", "DPAD_OK_onItemClick lvEpgPrograms position=$position")
                view.performClick()
            }

        val currentIdx = items.indexOfFirst { now in it.start until it.stop }
        if (currentIdx >= 0) lvEpgPrograms.post {
            lvEpgPrograms.setSelection(currentIdx.coerceAtLeast(0))
        }
    }


    private fun isSettingsSubPanelOpen(): Boolean =
        findViewById<View>(R.id.playlistSettingsPanel).visibility == View.VISIBLE ||
            findViewById<View>(R.id.epgSettingsPanel).visibility == View.VISIBLE ||
            findViewById<View>(R.id.userSettingsPanel).visibility == View.VISIBLE ||
            findViewById<View>(R.id.appInfoPanel).visibility == View.VISIBLE

    private fun applySettingsSubScreenProfileHeader() {
        val profileCard = findViewById<View>(R.id.userProfileHeaderCard)
        if (settingsOpenedFromPlayer) {
            profileCard.visibility = View.GONE
        } else {
            profileCard.visibility = View.VISIBLE
            updateProfileHeaderCard()
        }
        findViewById<View>(R.id.btnProfileChangeToken).apply {
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
        }
        // Pull playlist/EPG/About content closer under the profile card.
        applySettingsSubScreenContentInsets(tightUnderProfile = true)
    }

    /** Tight top inset for sub-screens under the profile; restore normal insets on the main grid. */
    private fun applySettingsSubScreenContentInsets(tightUnderProfile: Boolean) {
        if (settingsOpenedFromPlayer) {
            val inset = resources.getDimensionPixelSize(R.dimen.settings_content_padding_v)
            homeSettingsScreen.setPadding(inset, inset, inset, inset)
            return
        }
        val bottom = resources.getDimensionPixelSize(R.dimen.settings_content_padding_v)
        val top = if (tightUnderProfile) {
            0
        } else {
            bottom
        }
        homeSettingsScreen.setPadding(0, top, 0, bottom)
    }

    private fun restoreSettingsProfileHeaderInteractivity() {
        findViewById<View>(R.id.btnProfileChangeToken).apply {
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = false
        }
    }

    private fun returnToSettingsRowList() {
        findViewById<View>(R.id.userProfileHeaderCard).visibility = View.VISIBLE
        restoreSettingsProfileHeaderInteractivity()
        findViewById<View>(R.id.playlistSettingsPanel).visibility = View.GONE
        findViewById<View>(R.id.epgSettingsPanel).visibility = View.GONE
        findViewById<View>(R.id.userSettingsPanel).visibility = View.GONE
        findViewById<View>(R.id.appInfoPanel).visibility = View.GONE
        applySettingsSubScreenContentInsets(tightUnderProfile = false)
        val settingsRowIds = intArrayOf(
            R.id.btnPlaylistSettings, R.id.btnEpgSelect, R.id.btnSleepTimerSettings,
            R.id.btnAdvancedSettings, R.id.btnAppInfo, R.id.btnRefreshServices, R.id.itemStartMode,
            R.id.btnResetSettings, R.id.btnLogoutProfile
        )
        settingsRowIds.forEach { findViewById<View>(it).visibility = View.VISIBLE }
        val authorized = isAuthorizedUser()
        findViewById<View>(R.id.btnRefreshServices).visibility = if (authorized) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnLogoutProfile).visibility = if (authorized) View.VISIBLE else View.GONE
        applyResetLogoutBottomLayout(authorized)
        findViewById<View>(R.id.tvSettingsBack).visibility = View.GONE
        applyHomeAppTitleStyle(settingsMode = true, settingsTitle = "Настройки")
        if (settingsOpenedFromPlayer) {
            tunePlayerSettingsRows()
        } else {
            restoreDefaultSettingsRows()
        }
        val btnPlaylistSettingsRow = findViewById<View>(R.id.btnPlaylistSettings)
        btnPlaylistSettingsRow.post { btnPlaylistSettingsRow.requestFocus() }
    }

    private fun handleSettingsBackPress() {
        if (settingsOpenedAsAuthOnly) {
            settingsOpenedAsAuthOnly = false
            hideSettingsScreen()
            return
        }
        if (isSettingsSubPanelOpen()) {
            returnToSettingsRowList()
        } else {
            hideSettingsScreen()
        }
    }

    private fun configureBackButtonsForSettings(stage: String) {
        val tvSettingsBack = findViewById<TextView>(R.id.tvSettingsBack)
        val btnBackToMenu = findViewById<View>(R.id.btnBackToMenu)
        val tvHomeCategoryBack = findViewById<View>(R.id.tvHomeCategoryBack)

        tvSettingsBack.visibility = View.GONE
        tvSettingsBack.isEnabled = false
        tvSettingsBack.isClickable = false
        tvSettingsBack.setOnClickListener { handleSettingsBackPress() }

        btnBackToMenu.visibility = View.GONE
        btnBackToMenu.isEnabled = false
        btnBackToMenu.isClickable = false
        btnBackToMenu.setOnClickListener(null)

        tvHomeCategoryBack.visibility = View.GONE
        tvHomeCategoryBack.isEnabled = false
        tvHomeCategoryBack.isClickable = false
        tvHomeCategoryBack.setOnClickListener(null)

        logVisibleBackButtonIds(stage)
    }

    private fun performLogout() {
        prefs.edit()
            .remove(PREF_USER_NAME)
            .remove(PREF_USER_TOKEN)
            .remove(PREF_USER_LOGIN)
            .remove(PREF_USER_PLAYLIST)
            .putBoolean(PREF_START_LAST_CHANNEL, false)
            .apply()
        shouldOpenLastChannelOnStart = false
        findViewById<ToggleButton>(R.id.tbStartMode)?.isChecked = false
        val known = getKnownServiceNames() + "Избранные"
        val profiles = getPlaylistProfiles().filterNot { it.name in known }
        savePlaylistProfiles(profiles)
        prefs.edit().remove(PREF_KNOWN_SERVICE_NAMES).apply()
        setSelectedPlaylistName(profiles.firstOrNull { it.enabled && it.value.isNotBlank() }?.name ?: "")
        currentPlaylistText = ""
        channels.clear()
        cachedCategoryGroups = emptyMap()
        synchronized(epgDataLock) { epgData.clear() }
        isSettingsModalVisible = false
        settingsOpenedAsAuthOnly = false
        settingsOpenedFromPlayer = false
        homeSettingsScreen.visibility = View.GONE
        findViewById<View>(R.id.settingsMainPanel).visibility = View.GONE
        findViewById<View>(R.id.userProfileHeaderCard).visibility = View.GONE
        findViewById<View>(R.id.playlistSettingsPanel).visibility = View.GONE
        findViewById<View>(R.id.epgSettingsPanel).visibility = View.GONE
        findViewById<View>(R.id.userSettingsPanel).visibility = View.GONE
        findViewById<View>(R.id.appInfoPanel).visibility = View.GONE
        applyHomeAppTitleStyle(settingsMode = false)
        showPlaylistPageHeader(showWelcome = false, showTitle = false)
        if (hasEnabledThirdPartyPlaylists()) {
            showPlaylistPageOnHome(source = "logout")
        } else {
            showStartPage()
        }
    }

    private fun performFullReset() {
        prefs.edit().clear().apply()
        shouldOpenLastChannelOnStart = false
        currentPlaylistText = ""
        channels.clear()
        selectedEpgSources.clear()
        cachedCategoryGroups = emptyMap()
        synchronized(epgDataLock) { epgData.clear() }
        hideSettingsScreen()
        showStartPage()
    }

    private fun updateProfileHeaderCard() {
        val name = (prefs.getString(PREF_USER_NAME, "") ?: "").ifBlank { "Гость" }
        val login = prefs.getString(PREF_USER_LOGIN, "") ?: ""
        val token = prefs.getString(PREF_USER_TOKEN, "") ?: ""
        findViewById<TextView>(R.id.tvProfileName).text = name
        val tvNickname = findViewById<TextView>(R.id.tvProfileNickname)
        tvNickname.text = if (login.isNotBlank()) "@$login" else "не авторизован"
        val tokenRowViews = listOf(
            findViewById<View>(R.id.tvProfileTokenLabel),
            findViewById<View>(R.id.tvProfileTokenValue),
            findViewById<View>(R.id.btnProfileChangeToken)
        )
        val tvTokenValue = findViewById<TextView>(R.id.tvProfileTokenValue)
        if (token.isNotBlank()) {
            tokenRowViews.forEach { it.visibility = View.VISIBLE }
            tvTokenValue.text = token
            (tvNickname.layoutParams as? ConstraintLayout.LayoutParams)?.let { lp ->
                lp.bottomToTop = R.id.tvProfileTokenValue
                lp.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
                tvNickname.layoutParams = lp
            }
            (tvTokenValue.layoutParams as? ConstraintLayout.LayoutParams)?.let { lp ->
                lp.topToBottom = R.id.tvProfileNickname
                lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                tvTokenValue.layoutParams = lp
            }
            (findViewById<View>(R.id.tvProfileTokenLabel).layoutParams as? ConstraintLayout.LayoutParams)?.let { lp ->
                lp.topToBottom = ConstraintLayout.LayoutParams.UNSET
                lp.topToTop = R.id.tvProfileTokenValue
                lp.bottomToBottom = R.id.tvProfileTokenValue
                findViewById<View>(R.id.tvProfileTokenLabel).layoutParams = lp
            }
            tvTokenValue.setOnLongClickListener {
                copyTextToClipboard("token", token, "Токен скопирован")
                true
            }
            tvTokenValue.isLongClickable = true
            tvTokenValue.isClickable = true
            tvTokenValue.isFocusable = true
        } else {
            tokenRowViews.forEach { it.visibility = View.GONE }
            (tvNickname.layoutParams as? ConstraintLayout.LayoutParams)?.let { lp ->
                lp.bottomToTop = ConstraintLayout.LayoutParams.UNSET
                lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                tvNickname.layoutParams = lp
            }
            (tvTokenValue.layoutParams as? ConstraintLayout.LayoutParams)?.let { lp ->
                lp.topToBottom = ConstraintLayout.LayoutParams.UNSET
                lp.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
                tvTokenValue.layoutParams = lp
            }
            (findViewById<View>(R.id.tvProfileTokenLabel).layoutParams as? ConstraintLayout.LayoutParams)?.let { lp ->
                lp.topToTop = ConstraintLayout.LayoutParams.UNSET
                lp.topToBottom = ConstraintLayout.LayoutParams.UNSET
                lp.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
                findViewById<View>(R.id.tvProfileTokenLabel).layoutParams = lp
            }
            tvTokenValue.setOnLongClickListener(null)
            tvTokenValue.isLongClickable = false
        }
        findViewById<View>(R.id.btnProfileChangeToken).setOnClickListener {
            openProfileAuthScreen()
        }
    }

    private fun copyTextToClipboard(label: String, text: String, toast: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        showAppToast(toast)
    }

    private fun openProfileAuthScreen() {
        if (settingsOpenedFromPlayer) return
        val settingsRows = listOf(
            findViewById<View>(R.id.btnPlaylistSettings),
            findViewById<View>(R.id.btnEpgSelect),
            findViewById<View>(R.id.btnSleepTimerSettings),
            findViewById<View>(R.id.btnAdvancedSettings),
            findViewById<View>(R.id.btnAppInfo),
            findViewById<View>(R.id.btnRefreshServices),
            findViewById<View>(R.id.itemStartMode),
            findViewById<View>(R.id.btnResetSettings),
            findViewById<View>(R.id.btnLogoutProfile)
        )
        settingsRows.forEach { it.visibility = View.GONE }
        findViewById<View>(R.id.appInfoPanel).visibility = View.GONE
        findViewById<View>(R.id.playlistSettingsPanel).visibility = View.GONE
        findViewById<View>(R.id.epgSettingsPanel).visibility = View.GONE
        findViewById<View>(R.id.userSettingsPanel).visibility = View.VISIBLE
        findViewById<View>(R.id.btnProfileChangeToken).apply {
            isClickable = false
            isFocusable = false
        }
        val isAuthorizedUser = (prefs.getString(PREF_USER_NAME, "") ?: "").isNotBlank()
        // Для экрана авторизации гостя блок профиля не показываем.
        findViewById<View>(R.id.userProfileHeaderCard).visibility =
            if (isAuthorizedUser) View.VISIBLE else View.GONE
        applyHomeAppTitleStyle(
            settingsMode = true,
            settingsTitle = if (isAuthorizedUser) "профиль" else "авторизация"
        )
        bindInlineUserSettings(findViewById(R.id.userSettingsPanel))
    }

    private fun showSettingsDialog() {
        findViewById<View>(R.id.userProfileHeaderCard).visibility = View.VISIBLE
        updateProfileHeaderCard()
        restoreSettingsProfileHeaderInteractivity()
        applySettingsContentScale()
        showPlaylistPageHeader(showWelcome = false, showTitle = false)
        findViewById<View>(R.id.tvSettingsBack).visibility = View.GONE
        if (::epgPanel.isInitialized && epgPanel.visibility == View.VISIBLE) {
            hideEpgPanel()
        }
        if (::channelListPanel.isInitialized && channelListPanel.visibility == View.VISIBLE) {
            hideChannelListPanel()
        }
        if (::gvHomeChannelList.isInitialized && gvHomeChannelList.visibility == View.VISIBLE) {
            gvHomeChannelList.visibility = View.GONE
            gvHomeChannelList.adapter = null
            settingsOpenedFromHomeChannelList = true
        } else {
            settingsOpenedFromHomeChannelList = false
        }
        settingsOpenedFromPlayer = homePanel.visibility != View.VISIBLE
        isSettingsModalVisible = true
        homePlaylistTilesPanel.visibility = View.GONE
        logVisibleBackButtonIds("hideSettingsScreen_before_return")
        if (settingsOpenedFromPlayer) {
            playerSettingsOverlay.visibility = View.GONE
            hideUI()
            timerWarningPanel.visibility = View.GONE
            // Не ставим плеер на паузу и не сбрасываем таймер сна при открытии настроек.
            if (mediaPlayer != null && !isPlaybackPaused) {
                mediaPlayer?.playWhenReady = true
            }
            homePanel.setBackgroundResource(R.drawable.bg_home_screen)
            tvHomeAppTitle.visibility = View.GONE
            tvHomeSystemTime.visibility = View.GONE
            ivHomeProfile.visibility = View.GONE
            ivHomeSettings.visibility = View.GONE
            ivHomePower.visibility = View.GONE
            (homeSettingsScreen.layoutParams as? ConstraintLayout.LayoutParams)?.let { lp ->
                lp.topToTop = ConstraintSet.PARENT_ID
                lp.startToStart = ConstraintSet.PARENT_ID
                lp.endToEnd = ConstraintSet.PARENT_ID
                lp.bottomToBottom = ConstraintSet.PARENT_ID
                lp.topMargin = dpToPx(20)
                lp.marginStart = dpToPx(19)
                lp.marginEnd = dpToPx(19)
                lp.bottomMargin = dpToPx(20)
                lp.width =
                    (resources.displayMetrics.widthPixels - dpToPx(38)).coerceAtMost(dpToPx(1242))
                lp.height =
                    (resources.displayMetrics.heightPixels - dpToPx(40)).coerceAtMost(dpToPx(680))
                homeSettingsScreen.layoutParams = lp
            }
            homePanel.setBackgroundColor(Color.TRANSPARENT)
            homeSettingsScreen.setBackgroundResource(R.drawable.bg_player_settings_modal)
            val inset = resources.getDimensionPixelSize(R.dimen.settings_content_padding_v)
            homeSettingsScreen.setPadding(inset, inset, inset, inset)
            tunePlayerSettingsRows()
        } else {
            (homeSettingsScreen.layoutParams as? ConstraintLayout.LayoutParams)?.let { lp ->
                lp.topToTop = ConstraintSet.UNSET
                lp.topToBottom = R.id.userProfileHeaderCard
                lp.startToStart = ConstraintSet.PARENT_ID
                lp.endToEnd = ConstraintSet.PARENT_ID
                lp.bottomToBottom = ConstraintSet.PARENT_ID
                lp.topMargin = 0
                lp.marginStart = 0
                lp.marginEnd = 0
                lp.bottomMargin = 0
                lp.width = 0
                lp.height = 0
                homeSettingsScreen.layoutParams = lp
            }
            homePanel.setBackgroundResource(R.drawable.bg_home_screen)
            homeSettingsScreen.setBackgroundColor(Color.TRANSPARENT)
            val inset = resources.getDimensionPixelSize(R.dimen.settings_content_padding_v)
            homeSettingsScreen.setPadding(0, inset, 0, inset)
            restoreDefaultSettingsRows()
        }
        homePanel.visibility = View.VISIBLE
        homePanel.post { applyHomeScreenScale(force = true) }
        homeSettingsScreen.post { applySettingsViewportLayout() }
        tvHomeStartTitle.visibility = View.GONE
        tvHomeStartSubtitle.visibility = View.GONE
        applyHomeAppTitleStyle(settingsMode = true)
        homeSettingsScreen.visibility = View.VISIBLE
        findViewById<View>(R.id.settingsMainPanel).visibility = View.VISIBLE

        val btnPlaylistSettings = findViewById<View>(R.id.btnPlaylistSettings)
        btnPlaylistSettings.post { btnPlaylistSettings.requestFocus() }
        val tvSettingsBack = findViewById<TextView>(R.id.tvSettingsBack)
        val userSettingsPanel = findViewById<View>(R.id.userSettingsPanel)
        val btnEpgSelect = findViewById<View>(R.id.btnEpgSelect)
        val tbStartMode = findViewById<ToggleButton>(R.id.tbStartMode)
        val sleepRow = findViewById<View>(R.id.btnSleepTimerSettings)
        val btnAdvancedSettings = findViewById<View>(R.id.btnAdvancedSettings)
        val btnExportDebugLog = findViewById<View>(R.id.btnExportDebugLog)
        val btnAppInfo = findViewById<View>(R.id.btnAppInfo)
        val btnRefreshServices = findViewById<View>(R.id.btnRefreshServices)
        val itemStartModeRow = findViewById<View>(R.id.itemStartMode)
        val btnLogoutProfile = findViewById<View>(R.id.btnLogoutProfile)
        val settingsRows = listOf(
            btnPlaylistSettings, btnEpgSelect, sleepRow, btnAdvancedSettings, btnAppInfo,
            btnRefreshServices, itemStartModeRow, findViewById<View>(R.id.btnResetSettings), btnLogoutProfile
        )

        configureBackButtonsForSettings("showSettingsDialog_after_back_config")
        userSettingsPanel.visibility = View.GONE

        tbStartMode.isChecked = prefs.getBoolean(PREF_START_LAST_CHANNEL, false)
        tbStartMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREF_START_LAST_CHANNEL, isChecked).apply()
            shouldOpenLastChannelOnStart = isChecked
        }
        setupStartModeRemoteToggle(tbStartMode, itemStartModeRow)

        val tbAspectRatio = findViewById<ToggleButton>(R.id.tbAspectRatio)
        val aspectRatioLabels = listOf("Автоматически", "Вписать в экран", "16:9", "Растянуть", "Обрезать")
        var aspectRatioIndex = aspectRatioLabels.indexOf(
            prefs.getString(PREF_ASPECT_RATIO_MODE, "auto").let { ASPECT_RATIO_LABEL_BY_KEY[it] ?: "Автоматически" }
        ).coerceAtLeast(0)
        fun applyAspectRatioLabel() {
            val label = aspectRatioLabels[aspectRatioIndex]
            tbAspectRatio.textOn = label
            tbAspectRatio.textOff = label
            tbAspectRatio.text = label
        }
        applyAspectRatioLabel()
        tbAspectRatio.setOnClickListener {
            aspectRatioIndex = (aspectRatioIndex + 1) % aspectRatioLabels.size
            applyAspectRatioLabel()
            val key = ASPECT_RATIO_KEY_BY_LABEL[aspectRatioLabels[aspectRatioIndex]] ?: "auto"
            prefs.edit().putString(PREF_ASPECT_RATIO_MODE, key).apply()
            applyAspectRatioMode()
        }

        val playlistSettingsPanel = findViewById<View>(R.id.playlistSettingsPanel)
        val epgSettingsPanel = findViewById<View>(R.id.epgSettingsPanel)
        playlistSettingsPanel.visibility = View.GONE
        epgSettingsPanel.visibility = View.GONE
        userSettingsPanel.visibility = View.GONE
        settingsRows.forEach { it.visibility = View.VISIBLE }
        val authorized = isAuthorizedUser()
        btnRefreshServices.visibility = if (authorized) View.VISIBLE else View.GONE
        btnLogoutProfile.visibility = if (authorized) View.VISIBLE else View.GONE
        applyResetLogoutBottomLayout(authorized)
        btnExportDebugLog.visibility = View.GONE
        btnExportDebugLog.isEnabled = false
        btnExportDebugLog.isClickable = false
        btnPlaylistSettings.setOnClickListener { openPlaylistSettingsScreen() }
        btnEpgSelect.setOnClickListener { openEpgSettingsScreen() }
        btnRefreshServices.setOnClickListener { confirmRefreshServices() }

        val tvSleepTimerValue = findViewById<TextView>(R.id.tvSleepTimerValue)
        var sleepIndex =
            SLEEP_TIMER_OPTIONS.indexOf(prefs.getInt(PREF_SLEEP_TIMER_MINUTES, 0)).takeIf { it >= 0 } ?: 0

        fun updateSleepButtonVisual() {
            val selected = SLEEP_TIMER_OPTIONS[sleepIndex]
            val active = selected > 0
            sleepRow.setBackgroundResource(
                if (active) R.drawable.bg_settings_grid_card_focused else R.drawable.bg_settings_grid_card_normal
            )
            tvSleepTimerValue.text = formatSleepTimerValue(selected)
            tvSleepTimerValue.setTextColor(
                if (active) Color.parseColor("#FFFFFF") else Color.parseColor("#99FFFFFF")
            )
        }

        fun applySleepSelection() {
            val selected = SLEEP_TIMER_OPTIONS[sleepIndex]
            applySleepTimerMinutes(selected)
            tvSleepTimerValue.text = formatSleepTimerValue(selected)
            updateSleepButtonVisual()
            showAppToast(
                if (selected <= 0) "Таймер сна: выключен" else "Таймер сна: $selected мин",
                1800L
            )
        }

        fun changeSleep() {
            sleepIndex = (sleepIndex + 1) % SLEEP_TIMER_OPTIONS.size
            applySleepSelection()
        }

        sleepIndex = SLEEP_TIMER_OPTIONS.indexOf(prefs.getInt(PREF_SLEEP_TIMER_MINUTES, 0)).takeIf { it >= 0 } ?: 0
        updateSleepButtonVisual()
        sleepRow.isFocusable = true
        sleepRow.setOnClickListener { changeSleep() }
        sleepRow.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    changeSleep()
                    true
                }
                else -> false
            }
        }
        btnAdvancedSettings.setOnClickListener { exportDebugLogToDownloads() }
        btnAppInfo.setOnClickListener { showAppInfoScreen() }

        findViewById<View>(R.id.btnLogoutProfile).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Выйти из профиля?")
                .setMessage("Вы перестанете быть авторизованы, но ваши сторонние плейлисты сохранятся.")
                .setPositiveButton("Выйти") { _, _ -> performLogout() }
                .setNegativeButton("Отмена", null)
                .show()
        }

        findViewById<View>(R.id.btnResetSettings).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Сбросить все настройки?")
                .setMessage("Приложение вернётся в состояние первой установки: пропадут все плейлисты, авторизация и настройки. Это необратимо.")
                .setPositiveButton("Сбросить") { _, _ -> performFullReset() }
                .setNegativeButton("Отмена", null)
                .show()
        }
        // Guest profile card is informational only — no navigation.
        findViewById<View>(R.id.userProfileHeaderCard).setOnClickListener(null)
        findViewById<View>(R.id.userProfileHeaderCard).isClickable = false
        findViewById<View>(R.id.userProfileHeaderCard).isFocusable = false
        configureBackButtonsForSettings("showSettingsDialog_final")
    }

    private fun applyResetLogoutBottomLayout(authorized: Boolean) {
        val reset = findViewById<View>(R.id.btnResetSettings) ?: return
        val logout = findViewById<View>(R.id.btnLogoutProfile) ?: return
        val halfGuide = R.id.settingsGridGuideHalf
        (reset.layoutParams as? ConstraintLayout.LayoutParams)?.let { lp ->
            if (authorized) {
                lp.endToStart = halfGuide
                lp.endToEnd = ConstraintLayout.LayoutParams.UNSET
                lp.marginEnd = resources.getDimensionPixelSize(R.dimen.settings_grid_column_gap)
            } else {
                lp.endToStart = ConstraintLayout.LayoutParams.UNSET
                lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                lp.marginEnd = 0
            }
            reset.layoutParams = lp
        }
        logout.visibility = if (authorized) View.VISIBLE else View.GONE
    }

    private fun confirmRefreshServices() {
        AlertDialog.Builder(this)
            .setTitle("Обновить сервисы?")
            .setMessage("Источники сервисов и плейлистов будут загружены заново. Продолжить?")
            .setPositiveButton("Обновить") { _, _ -> refreshServicesFromNetwork() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun refreshServicesFromNetwork() {
        val token = (prefs.getString(PREF_USER_TOKEN, "") ?: "").trim()
        if (token.isBlank()) {
            showAppToast("Сначала авторизуйтесь")
            return
        }
        showAppLoadingSpinner()
        clearPlaylistContentCache()
        cachedCategoryGroups = emptyMap()
        lastChannelListCategory = null
        settingsOpenedFromHomeChannelList = false
        syncPortalPlaylistsForAuthorizedUser(token)
        // После обновления — только главный экран сервисов, без входа в категории.
        handler.postDelayed({
            hideAppLoadingSpinner()
            isSettingsModalVisible = false
            settingsOpenedAsAuthOnly = false
            settingsOpenedFromPlayer = false
            homeSettingsScreen.visibility = View.GONE
            findViewById<View>(R.id.settingsMainPanel).visibility = View.GONE
            findViewById<View>(R.id.userProfileHeaderCard).visibility = View.GONE
            findViewById<View>(R.id.playlistSettingsPanel).visibility = View.GONE
            findViewById<View>(R.id.epgSettingsPanel).visibility = View.GONE
            findViewById<View>(R.id.userSettingsPanel).visibility = View.GONE
            findViewById<View>(R.id.appInfoPanel).visibility = View.GONE
            applyHomeAppTitleStyle(settingsMode = false)
            showPlaylistPageHeader(showWelcome = false, showTitle = false)
            showPlaylistPageOnHome(source = "refresh_services")
            showAppToast("Сервисы обновлены")
        }, 1500L)
    }

    private fun formatSleepTimerValue(minutes: Int): String =
        if (minutes <= 0) "выключено" else "$minutes мин"

    private fun applySleepTimerMinutes(minutes: Int) {
        prefs.edit().putInt(PREF_SLEEP_TIMER_MINUTES, minutes).apply()
        if (minutes <= 0) {
            cancelSleepTimer()
        } else {
            startSleepTimer(minutes)
        }
        val sleepRow = findViewById<View>(R.id.btnSleepTimerSettings)
        val active = minutes > 0
        sleepRow?.setBackgroundResource(
            if (active) R.drawable.bg_settings_grid_card_focused else R.drawable.bg_settings_grid_card_normal
        )
    }

    private fun openPlaylistSettingsScreen() {
        applySettingsSubScreenProfileHeader()
        val tvSettingsBack = findViewById<TextView>(R.id.tvSettingsBack)
        val playlistPanel = findViewById<View>(R.id.playlistSettingsPanel)
        val userSettingsPanel = findViewById<View>(R.id.userSettingsPanel)
        val settingsRows = listOf(
            findViewById<View>(R.id.btnPlaylistSettings), findViewById<View>(R.id.btnEpgSelect),
            findViewById<View>(R.id.btnSleepTimerSettings),
            findViewById<View>(R.id.btnAdvancedSettings), findViewById<View>(R.id.btnAppInfo),
            findViewById<View>(R.id.btnRefreshServices), findViewById<View>(R.id.itemStartMode),
            findViewById<View>(R.id.btnResetSettings), findViewById<View>(R.id.btnLogoutProfile)
        )
        settingsRows.forEach { it.visibility = View.GONE }
        userSettingsPanel.visibility = View.GONE
        findViewById<View>(R.id.appInfoPanel).visibility = View.GONE
        playlistPanel.visibility = View.VISIBLE
        tvSettingsBack.visibility = View.GONE
        applyHomeAppTitleStyle(settingsMode = true, settingsTitle = "настройки", settingsTitle2 = "настройки плейлистов")

        fun deriveNameFromUrl(url: String): String {
            val afterScheme = url.substringAfter("://", url)
            return afterScheme.substringBefore("/").ifBlank { url }
        }

        val urls = listOf<EditText>(findViewById(R.id.etPlaylistUrl1), findViewById(R.id.etPlaylistUrl2), findViewById(R.id.etPlaylistUrl3))
        val toggles = listOf<View>(findViewById(R.id.ivPlaylistToggle1), findViewById(R.id.ivPlaylistToggle2), findViewById(R.id.ivPlaylistToggle3))
        val dots = listOf<View>(findViewById(R.id.dotPlaylistActive1), findViewById(R.id.dotPlaylistActive2), findViewById(R.id.dotPlaylistActive3))
        val states = MutableList(3) { false }

        fun updateDot(i: Int) {
            dots[i].visibility = if (states[i]) View.VISIBLE else View.GONE
        }

        fun bindData() {
            val profiles = getThirdPartyPlaylistProfiles()
            for (i in 0..2) {
                val p = profiles.getOrNull(i)
                urls[i].setText(p?.value ?: "")
                states[i] = p?.enabled == true && !p.value.isNullOrBlank()
                updateDot(i)
            }
        }
        toggles.forEachIndexed { i, v ->
            v.setOnClickListener {
                states[i] = !states[i]
                updateDot(i)
            }
        }
        urls.forEachIndexed { i, et ->
            et.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (!s.isNullOrBlank() && !states[i]) {
                        states[i] = true
                        updateDot(i)
                    }
                }
            })
        }

        findViewById<View>(R.id.btnSavePlaylistSettings).setOnClickListener {
            val items = (0..2).mapNotNull { i ->
                val url = urls[i].text.toString().trim()
                if (url.isBlank()) return@mapNotNull null
                PlaylistProfile(deriveNameFromUrl(url), "url", url, states[i])
            }
            saveThirdPartyPlaylistProfiles(items)
            showAppToast("Сторонние плейлисты сохранены")
        }
        findViewById<View>(R.id.btnRefreshPlaylistSettings).setOnClickListener {
            handleSettingsBackPress()
        }

        configureBackButtonsForSettings("openPlaylistSettingsScreen")
        bindData()
        applySettingsViewportLayout()
    }


    private fun showAppInfoScreen() {
        applySettingsSubScreenProfileHeader()
        val appInfoPanel = findViewById<View>(R.id.appInfoPanel)
        val playlistPanel = findViewById<View>(R.id.playlistSettingsPanel)
        val epgPanel = findViewById<View>(R.id.epgSettingsPanel)
        val userSettingsPanel = findViewById<View>(R.id.userSettingsPanel)
        val settingsRows = listOf(
            findViewById<View>(R.id.btnPlaylistSettings), findViewById<View>(R.id.btnEpgSelect),
            findViewById<View>(R.id.btnSleepTimerSettings),
            findViewById<View>(R.id.btnAdvancedSettings), findViewById<View>(R.id.btnAppInfo),
            findViewById<View>(R.id.btnRefreshServices), findViewById<View>(R.id.itemStartMode),
            findViewById<View>(R.id.btnResetSettings), findViewById<View>(R.id.btnLogoutProfile)
        )
        settingsRows.forEach { it.visibility = View.GONE }
        playlistPanel.visibility = View.GONE
        epgPanel.visibility = View.GONE
        findViewById<View>(R.id.appInfoPanel).visibility = View.GONE
        userSettingsPanel.visibility = View.GONE
        appInfoPanel.visibility = View.VISIBLE
        appInfoPanel.isFocusable = true
        appInfoPanel.isFocusableInTouchMode = true
        appInfoPanel.post { appInfoPanel.requestFocus() }
        applyHomeAppTitleStyle(settingsMode = true, settingsTitle = "О приложении")

        configureBackButtonsForSettings("showAppInfoScreen")
    }

    private fun openEpgSettingsScreen() {
        applySettingsSubScreenProfileHeader()
        val tvSettingsBack = findViewById<TextView>(R.id.tvSettingsBack)
        val epgPanel = findViewById<View>(R.id.epgSettingsPanel)
        val playlistPanel = findViewById<View>(R.id.playlistSettingsPanel)
        val userSettingsPanel = findViewById<View>(R.id.userSettingsPanel)
        val settingsRows = listOf(
            findViewById<View>(R.id.btnPlaylistSettings), findViewById<View>(R.id.btnEpgSelect),
            findViewById<View>(R.id.btnSleepTimerSettings), findViewById<View>(R.id.btnAdvancedSettings),
            findViewById<View>(R.id.btnAppInfo), findViewById<View>(R.id.btnRefreshServices),
            findViewById<View>(R.id.itemStartMode),
            findViewById<View>(R.id.btnResetSettings), findViewById<View>(R.id.btnLogoutProfile)
        )
        settingsRows.forEach { it.visibility = View.GONE }
        playlistPanel.visibility = View.GONE
        userSettingsPanel.visibility = View.GONE
        findViewById<View>(R.id.appInfoPanel).visibility = View.GONE
        epgPanel.visibility = View.VISIBLE
        epgPanel.isClickable = true
        epgPanel.isFocusable = true
        tvSettingsBack.visibility = View.GONE
        applyHomeAppTitleStyle(settingsMode = true, settingsTitle = "настройки", settingsTitle2 = "настройки EPG")

        val urls = listOf<EditText>(findViewById(R.id.etEpgUrl1), findViewById(R.id.etEpgUrl2), findViewById(R.id.etEpgUrl3))
        val toggles = listOf<ImageView>(findViewById(R.id.ivEpgToggle1), findViewById(R.id.ivEpgToggle2), findViewById(R.id.ivEpgToggle3))
        val states = MutableList(3) { false }
        val tbInterval = findViewById<ToggleButton>(R.id.tbEpgRefreshInterval)
        val intervals = listOf(1,3,5,7)
        var intervalIndex = intervals.indexOf(prefs.getInt(PREF_EPG_REFRESH_INTERVAL_DAYS, 1)).takeIf { it >= 0 } ?: 0
        var pendingApply: Runnable? = null

        val current = getCustomEpgSources()
            .ifEmpty { getSelectedEpgSources().toList() }
            .ifEmpty { extractEpgSourcesFromPlaylist(currentPlaylistText) }
            .take(3)
        val selected = getSelectedEpgSources()
        urls.forEachIndexed { i, et ->
            val value = current.getOrNull(i) ?: ""
            et.setText(value)
            states[i] = value.isNotBlank() && (selected.isEmpty() || selected.contains(value))
        }
        toggles.forEachIndexed { i, v ->
            v.setOnClickListener {
                if (!v.isEnabled) return@setOnClickListener
                states[i] = !states[i]
                v.setImageResource(if (states[i]) R.drawable.toggleright else R.drawable.toggleleft)
            }
        }
        urls.forEachIndexed { i, et ->
            et.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (!s.isNullOrBlank() && !states[i]) {
                        states[i] = true
                        toggles[i].setImageResource(R.drawable.toggleright)
                    }
                }
            })
        }

        val tbSourceMode = findViewById<ToggleButton>(R.id.tbEpgSourceMode)
        val tvSourceHint = findViewById<TextView>(R.id.tvEpgSourceHint)
        val playlistOwnSources = extractEpgSourcesFromPlaylist(currentPlaylistText)
        val hasManualSaved = getCustomEpgSources().isNotEmpty()

        fun applyEpgSourceModeLock(manual: Boolean) {
            urls.forEach { et ->
                et.isEnabled = manual
                et.alpha = if (manual) 1f else 0.5f
                et.isFocusable = manual
                et.isFocusableInTouchMode = manual
                et.isClickable = manual
                if (manual) {
                    et.setOnClickListener(null)
                } else {
                    et.setOnClickListener { /* consume click on locked field */ }
                }
            }
            toggles.forEach { toggle ->
                toggle.isEnabled = manual
                toggle.alpha = if (manual) 1f else 0.5f
                toggle.isFocusable = manual
                toggle.isClickable = manual
            }
            tvSourceHint.text = when {
                manual -> "Ссылки указаны вручную и не зависят от плейлиста."
                playlistOwnSources.isNotEmpty() -> "Ссылка берётся из самого плейлиста: ${playlistOwnSources.first()}"
                else -> "У этого плейлиста нет своей ссылки на EPG. Переключите на \"Указать вручную\", чтобы добавить свою."
            }
        }

        fun fillFromPlaylistOwnSources() {
            urls.forEachIndexed { i, et -> et.setText(playlistOwnSources.getOrNull(i) ?: "") }
            toggles.forEachIndexed { i, v ->
                states[i] = playlistOwnSources.getOrNull(i)?.isNotBlank() == true
                v.setImageResource(if (states[i]) R.drawable.toggleright else R.drawable.toggleleft)
            }
        }

        tbSourceMode.isChecked = hasManualSaved
        applyEpgSourceModeLock(hasManualSaved)
        if (!hasManualSaved) fillFromPlaylistOwnSources()

        tbSourceMode.setOnCheckedChangeListener { _, isChecked ->
            applyEpgSourceModeLock(isChecked)
            if (!isChecked) {
                fillFromPlaylistOwnSources()
                clearCustomEpgSources()
                selectedEpgSources = playlistOwnSources.toMutableSet()
                saveSelectedEpgSources(selectedEpgSources)
            }
        }

        fun updateIntervalText() {
            val d = intervals[intervalIndex]
            tbInterval.textOn = when (d) {
                1 -> "1 день"
                3 -> "3 дня"
                5 -> "5 дней"
                else -> "7 дней"
            }
            tbInterval.textOff = tbInterval.textOn
            tbInterval.text = tbInterval.textOn
        }
        fun scheduleIntervalSave() {
            pendingApply?.let { handler.removeCallbacks(it) }
            pendingApply = Runnable { prefs.edit().putInt(PREF_EPG_REFRESH_INTERVAL_DAYS, intervals[intervalIndex]).apply() }
            handler.postDelayed(pendingApply!!, 7000L)
        }
        updateIntervalText()
        tbInterval.setOnClickListener {
            intervalIndex = (intervalIndex + 1) % intervals.size
            updateIntervalText()
            scheduleIntervalSave()
        }

        findViewById<View>(R.id.btnSaveEpgSettings).setOnClickListener {
            val links = urls.mapIndexedNotNull { i, et -> et.text.toString().trim().takeIf { it.isNotBlank() && states[i] } }.distinct()
            if (links.isNotEmpty()) {
                saveCustomEpgSources(links)
                selectedEpgSources = links.toMutableSet()
                saveSelectedEpgSources(selectedEpgSources)
            }
            if (selectedEpgSources.isNotEmpty()) {
                synchronized(epgDataLock) { epgData.clear() }
                fetchEpgSources(selectedEpgSources.toList(), mutableMapOf())
                showAppToast("Настройки сохранены, обновление EPG запущено")
            } else {
                showAppToast(
                    "Нет выбранных источников EPG — включите переключатель у нужной ссылки",
                    3500L
                )
            }
        }
        findViewById<View>(R.id.btnRefreshEpgSettings).setOnClickListener {
            handleSettingsBackPress()
        }
        findViewById<View>(R.id.btnResetEpgCache).setOnClickListener {
            cancelAndClearEpgCache()
            showAppToast("Кэш EPG очищен, загрузка отменена")
            updateEpgLoadStatusUi()
        }
        updateEpgLoadStatusUi()

        tbSourceMode.post { tbSourceMode.requestFocus() }
        configureBackButtonsForSettings("openEpgSettingsScreen")
        applySettingsViewportLayout()
    }

    private fun getKnownServiceNames(): Set<String> =
        prefs.getStringSet(PREF_KNOWN_SERVICE_NAMES, null) ?: DEFAULT_SERVICE_NAMES

    private fun getThirdPartyPlaylistProfiles(): List<PlaylistProfile> {
        val known = getKnownServiceNames() + setOf("Избранные", "Пользователь", "По умолчанию")
        return getPlaylistProfiles().filter { it.name !in known }
    }

    private fun saveThirdPartyPlaylistProfiles(thirdParty: List<PlaylistProfile>) {
        val known = getKnownServiceNames() + setOf("Избранные", "Пользователь", "По умолчанию")
        val systemProfiles = getPlaylistProfiles().filter { it.name in known }
        savePlaylistProfiles(systemProfiles + thirdParty.take(3))
    }


    private fun syncPortalPlaylistsForAuthorizedUser(token: String) {
        val cleanToken = token.trim()
        if (cleanToken.isBlank()) return
        val login = prefs.getString(PREF_USER_LOGIN, "") ?: ""
        thread {
            val url = "https://o.avff.pw/api.php?module=app&action=services&token=${
                Uri.encode(cleanToken)
            }&login=${Uri.encode(login)}"
            fun applyServicesJson(json: JSONObject, fromCache: Boolean) {
                if (!json.optBoolean("success", false)) {
                    if (!fromCache) logDebug("PLAYLIST_FLOW", "SERVICES_SYNC_FAILED message=${json.optString("message")}")
                    return
                }
                val servicesArray = json.optJSONArray("services")
                val portal = mutableListOf<PlaylistProfile>()
                if (servicesArray != null) {
                    for (i in 0 until servicesArray.length()) {
                        val svc = servicesArray.optJSONObject(i) ?: continue
                        val code = svc.optString("code").trim()
                        val title = svc.optString("title").trim()
                        if (code.isBlank() || title.isBlank()) continue
                        portal.add(
                            PlaylistProfile(
                                title, "url",
                                "https://o.avff.pw/list/$code.m3u8?token=$cleanToken", true
                            )
                        )
                    }
                }
                portal.add(PlaylistProfile("Избранные", "url", "https://o.avff.pw/my/$cleanToken.m3u", true))
                logDebug(
                    "PLAYLIST_FLOW",
                    "SERVICES_RAW_RESPONSE fromCache=$fromCache names=${portal.map { it.name }} rawServicesCount=${servicesArray?.length() ?: 0}"
                )
                handler.post {
                    prefs.edit().putStringSet(
                        PREF_KNOWN_SERVICE_NAMES,
                        portal.map { it.name }.toSet() - "Избранные"
                    ).apply()
                    val known = getKnownServiceNames() + setOf("Избранные", "Пользователь", "По умолчанию")
                    val existing = getPlaylistProfiles().filter { it.name !in known }
                    savePlaylistProfiles((portal + existing).distinctBy { it.name })
                    logDebug("PLAYLIST_FLOW", "SERVICES_SYNCED count=${portal.size} fromCache=$fromCache")
                    if (homePlaylistTilesPanel.visibility == View.VISIBLE) {
                        showPlaylistPageOnHome()
                    }
                }
            }
            // Prefer fresh network, fall back to cached services JSON.
            runCatching { JSONObject(URL(url).readText()) }
                .onSuccess { json ->
                    prefs.edit().putString(PREF_SERVICES_CACHE, json.toString()).apply()
                    applyServicesJson(json, fromCache = false)
                }
                .onFailure { e ->
                    logDebug("PLAYLIST_FLOW", "SERVICES_SYNC_ERROR ${e.message}")
                    val cached = prefs.getString(PREF_SERVICES_CACHE, null)
                    if (!cached.isNullOrBlank()) {
                        runCatching { JSONObject(cached) }
                            .onSuccess { applyServicesJson(it, fromCache = true) }
                    }
                }
        }
    }

    private fun logVisibleBackButtonIds(stage: String) {
        val candidates = listOf(
            R.id.tvSettingsBack,
            R.id.btnBackToMenu,
            R.id.tvHomeCategoryBack
        )
        val visible = candidates.mapNotNull { id ->
            val v = findViewById<View?>(id) ?: return@mapNotNull null
            if (v.visibility == View.VISIBLE) resources.getResourceEntryName(id) else null
        }
        logDebug("SETTINGS_UI", "VISIBLE BACK BUTTON IDS [$stage]: ${visible.joinToString(",")}")
    }

    private fun hideSettingsScreen() {
        isSettingsModalVisible = false
        settingsOpenedAsAuthOnly = false
        homeSettingsScreen.visibility = View.GONE
        findViewById<View>(R.id.settingsMainPanel).visibility = View.GONE
        findViewById<View>(R.id.userProfileHeaderCard).visibility = View.GONE
        findViewById<View>(R.id.playlistSettingsPanel).visibility = View.GONE
        findViewById<View>(R.id.epgSettingsPanel).visibility = View.GONE
        findViewById<View>(R.id.userSettingsPanel).visibility = View.GONE
        findViewById<View>(R.id.appInfoPanel).visibility = View.GONE
        applyHomeAppTitleStyle(settingsMode = false)
        playerSettingsOverlay.visibility = View.GONE
        homePanel.setBackgroundResource(R.drawable.bg_home_screen)
        findViewById<View>(R.id.tvHomeCategoryBack).visibility = View.GONE
        findViewById<View>(R.id.tvHomeCategoryBack).isEnabled = false
        findViewById<View>(R.id.tvHomeCategoryBack).isClickable = false
        findViewById<View>(R.id.btnBackToMenu).visibility = View.VISIBLE
        findViewById<View>(R.id.btnBackToMenu).isEnabled = true
        findViewById<View>(R.id.btnBackToMenu).isClickable = true
        bindRealPlayerExitButtonListener()
        if (settingsOpenedFromPlayer) {
            logDebug("NAV", "SETTINGS_CLOSED_FROM_PLAYER")
            playerSettingsOverlay.visibility = View.GONE
            homeSettingsScreen.visibility = View.GONE
            findViewById<View>(R.id.settingsMainPanel).visibility = View.GONE
            homePanel.visibility = View.GONE
            showUI()
            return
        }
        val isAuthorizedUser = isAuthorizedUser()
        val hasEnabledThirdParty = hasEnabledThirdPartyPlaylists()
        if (isAuthorizedUser || hasEnabledThirdParty) {
            val category = lastChannelListCategory
            if (settingsOpenedFromHomeChannelList && category != null &&
                cachedCategoryGroups.containsKey(category)
            ) {
                showPlaylistPageOnHome()
                returnToCategoryTilesOnHome()
                showHomeChannelList(category, cachedCategoryGroups[category].orEmpty())
            } else {
                showPlaylistPageOnHome()
            }
            settingsOpenedFromHomeChannelList = false
            homePanel.post { applyHomeScreenScale(force = true) }
        } else {
            showStartPage()
        }
    }



    private fun restoreDefaultSettingsRows() {
        val tvSettingsBack = findViewById<TextView>(R.id.tvSettingsBack)
        (tvSettingsBack.layoutParams as? ConstraintLayout.LayoutParams)?.let { lp ->
            lp.topMargin = dpToPx(4)
            lp.marginStart = 0
            tvSettingsBack.layoutParams = lp
        }
    }

    private fun tunePlayerSettingsRows() {
        val dm = resources.displayMetrics
        val scale = minOf(dm.widthPixels / 1280f, dm.heightPixels / 720f).coerceAtLeast(0.65f)
        val rowHeight = (78f * scale).toInt()
        val rowMargin = (10f * scale).toInt()
        val backLabel = findViewById<TextView>(R.id.tvSettingsBack)
        val rowIds = intArrayOf(
            R.id.btnPlaylistSettings,
            R.id.btnEpgSelect,
            R.id.btnSleepTimerSettings,
            R.id.btnAdvancedSettings,
            R.id.btnAppInfo,
        )
        val containerHeight = (homeSettingsScreen.layoutParams?.height ?: 0).takeIf { it > 0 }
            ?: (resources.displayMetrics.heightPixels - dpToPx(40))
        val contentHeight = rowIds.size * rowHeight + (rowIds.size - 1) * rowMargin
        val centeredTopMargin =
            (((containerHeight - contentHeight) / 2) - dpToPx(13)).coerceAtLeast(0)

        (backLabel.layoutParams as? ConstraintLayout.LayoutParams)?.let { lp ->
            lp.marginStart = dpToPx(12)
            lp.topMargin = (centeredTopMargin - dpToPx(28)).coerceAtLeast(0)
            backLabel.layoutParams = lp
        }

        (backLabel.layoutParams as? ConstraintLayout.LayoutParams)?.let { lp ->
            lp.marginStart = dpToPx(12)
            lp.topMargin = (centeredTopMargin - dpToPx(26)).coerceAtLeast(0)
            backLabel.layoutParams = lp
        }

        (backLabel.layoutParams as? ConstraintLayout.LayoutParams)?.let { lp ->
            lp.marginStart = dpToPx(12)
            lp.topMargin = (centeredTopMargin - dpToPx(27)).coerceAtLeast(0)
            backLabel.layoutParams = lp
        }

        (backLabel.layoutParams as? ConstraintLayout.LayoutParams)?.let { lp ->
            lp.marginStart = dpToPx(12)
            lp.topMargin = (centeredTopMargin - dpToPx(28)).coerceAtLeast(0)
            backLabel.layoutParams = lp
        }

        (backLabel.layoutParams as? ConstraintLayout.LayoutParams)?.let { lp ->
            lp.marginStart = dpToPx(12)
            lp.topMargin = (centeredTopMargin - dpToPx(28)).coerceAtLeast(0)
            backLabel.layoutParams = lp
        }

        (backLabel.layoutParams as? ConstraintLayout.LayoutParams)?.let { lp ->
            lp.marginStart = dpToPx(12)
            lp.topMargin = (centeredTopMargin - dpToPx(28)).coerceAtLeast(0)
            backLabel.layoutParams = lp
        }

        (backLabel.layoutParams as? ConstraintLayout.LayoutParams)?.let { lp ->
            lp.marginStart = dpToPx(12)
            lp.topMargin = (centeredTopMargin - dpToPx(28)).coerceAtLeast(0)
            backLabel.layoutParams = lp
        }

        (backLabel.layoutParams as? ConstraintLayout.LayoutParams)?.let { lp ->
            lp.marginStart = dpToPx(12)
            lp.topMargin = (centeredTopMargin - dpToPx(28)).coerceAtLeast(0)
            backLabel.layoutParams = lp
        }

        (backLabel.layoutParams as? ConstraintLayout.LayoutParams)?.let { lp ->
            lp.marginStart = dpToPx(12)
            lp.topMargin = (centeredTopMargin - dpToPx(28)).coerceAtLeast(0)
            backLabel.layoutParams = lp
        }

        (backLabel.layoutParams as? ConstraintLayout.LayoutParams)?.let { lp ->
            lp.marginStart = dpToPx(12)
            lp.topMargin = (centeredTopMargin - dpToPx(28)).coerceAtLeast(0)
            backLabel.layoutParams = lp
        }

    }

    private fun dpToPx(value: Int): Int = (resources.displayMetrics.density * value).toInt()

    private fun showSettingsPlaceholderDialog() {
        AlertDialog.Builder(this)
            .setTitle("Дополнительные настройки")
            .setMessage("Экспорт debug лога")
            .setPositiveButton("Экспорт") { _, _ -> exportDebugLogToDownloads() }
            .setNeutralButton("FFmpeg audio toggle") { _, _ ->
                val current = prefs.getBoolean(PREF_USE_FFMPEG_AUDIO_FOR_MPEG_L2, USE_FFMPEG_AUDIO_FOR_MPEG_L2)
                val next = !current
                prefs.edit().putBoolean(PREF_USE_FFMPEG_AUDIO_FOR_MPEG_L2, next).apply()
                showAppToast("FFmpeg audio mode: ${if (next) "PREFER" else "OFF"}", 3500L)
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun showUserSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_user_settings, null)
        val tvStatus = view.findViewById<TextView>(R.id.tvUserAuthStatus)
        val etLogin = view.findViewById<EditText>(R.id.etUserLogin)
        val etToken = view.findViewById<EditText>(R.id.etUserToken)
        val btnApply = view.findViewById<Button>(R.id.btnUserAuthApply)

        etLogin.setText(prefs.getString(PREF_USER_LOGIN, "") ?: "")
        etToken.setText(prefs.getString(PREF_USER_TOKEN, "") ?: "")
        val cachedName = prefs.getString(PREF_USER_NAME, "") ?: ""
        if (cachedName.isNotBlank()) tvStatus.text = "Вы авторизованы как $cachedName"

        val dialog =
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
                .setView(view)
                .create()

        fun applyAuthorizedProfile(name: String, token: String, playlist: String) {
            prefs.edit()
                .putString(PREF_USER_NAME, name)
                .putString(PREF_USER_TOKEN, token)
                .putString(PREF_USER_PLAYLIST, playlist)
                .apply()
            val profiles = getPlaylistProfiles().toMutableList()
            val idx = profiles.indexOfFirst { it.name == "Пользователь" }
            val profile = PlaylistProfile("Пользователь", "url", playlist, true)
            if (idx >= 0) profiles[idx] = profile else profiles.add(profile)
            savePlaylistProfiles(profiles)
            syncPortalPlaylistsForAuthorizedUser(token)
            hideSettingsScreen()
            showPlaylistPageOnHome()
            tvStatus.text = "Вы авторизованы как $name"
        }

        btnApply.setOnClickListener {
            val login = etLogin.text.toString().trim()
            val token = etToken.text.toString().trim()
            if (login.isBlank() || token.isBlank()) {
                AlertDialog.Builder(this)
                    .setTitle("Ошибка авторизации")
                    .setMessage("Необходимо передать login и token.")
                    .setPositiveButton("ОК", null)
                    .show()
                return@setOnClickListener
            }
            btnApply.isEnabled = false
            thread {
                try {
                    val url =
                        "https://o.avff.pw/api.php?module=app&login=${Uri.encode(login)}&token=${
                            Uri.encode(token)
                        }"
                    val responseText = URL(url).readText()
                    val json = JSONObject(responseText)
                    handler.post {
                        btnApply.isEnabled = true
                        if (json.optString("valid") == "OK") {
                            val name = json.optString("name", login)
                            val playlist = json.optString("playlist", "")
                            prefs.edit().putString(PREF_USER_LOGIN, login).apply()
                            applyAuthorizedProfile(name, token, playlist)
                            showAppToast("Авторизация успешна")
                            dialog.dismiss()
                        } else {
                            val msg = json.optString("message", "Неверный login или token.")
                            AlertDialog.Builder(this)
                                .setTitle("Ошибка авторизации")
                                .setMessage(msg)
                                .setPositiveButton("ОК", null)
                                .show()
                        }
                    }
                } catch (e: Exception) {
                    handler.post {
                        btnApply.isEnabled = true
                        AlertDialog.Builder(this)
                            .setTitle("Ошибка сети")
                            .setMessage("Не удалось проверить авторизацию: ${e.message ?: "неизвестная ошибка"}")
                            .setPositiveButton("ОК", null)
                            .show()
                    }
                }
            }
        }

        dialog.show()
        dialog.window?.decorView?.let { applyGolosTypeface(it) }
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun bindInlineUserSettings(panel: View) {
        val tvState = panel.findViewById<TextView>(R.id.tvUserSectionState)
        val etLogin = panel.findViewById<EditText>(R.id.etUserLoginInline)
        val etToken = panel.findViewById<EditText>(R.id.etUserTokenInline)
        val btnAuth = panel.findViewById<TextView>(R.id.btnUserAuthInline)
        val authForm = panel.findViewById<View>(R.id.userAuthForm)
        val authorizedView = panel.findViewById<View>(R.id.userAuthorizedView)
        val tvAuthorizedName = panel.findViewById<TextView>(R.id.tvAuthorizedName)
        val btnChangeUser = panel.findViewById<TextView>(R.id.btnChangeUserInline)
        val tvAuthorizedTokenValue = panel.findViewById<TextView>(R.id.tvAuthorizedTokenValue)
        val tvAuthorizedPlaylistValue = panel.findViewById<TextView>(R.id.tvAuthorizedPlaylistValue)
        val btnChangeToken = panel.findViewById<TextView>(R.id.btnChangeTokenInline)
        val btnOpenPlaylist = panel.findViewById<TextView>(R.id.btnOpenPlaylistInline)
        etLogin.setText(prefs.getString(PREF_USER_LOGIN, "") ?: "")
        etToken.setText(prefs.getString(PREF_USER_TOKEN, "") ?: "")
        val cachedName = prefs.getString(PREF_USER_NAME, "") ?: ""
        val isAuthorized = cachedName.isNotBlank()
        authForm.visibility = if (isAuthorized) View.GONE else View.VISIBLE
        authorizedView.visibility = if (isAuthorized) View.VISIBLE else View.GONE
        panel.findViewById<View>(R.id.tvUserFooterHint).visibility =
            if (isAuthorized) View.GONE else View.VISIBLE
        val showAspectSettings = isAuthorized && !settingsOpenedAsAuthOnly
        panel.findViewById<View>(R.id.userSettingsAspectDivider).visibility =
            if (showAspectSettings) View.VISIBLE else View.GONE
        panel.findViewById<View>(R.id.itemAspectRatio).visibility =
            if (showAspectSettings) View.VISIBLE else View.GONE
        tvState.text = "Имя пользователя"
        tvAuthorizedName.text = cachedName
        tvAuthorizedTokenValue.text = prefs.getString(PREF_USER_TOKEN, "") ?: ""
        tvAuthorizedPlaylistValue.text = prefs.getString(PREF_USER_PLAYLIST, "") ?: ""
        etToken.isEnabled = true
        btnChangeUser.setOnClickListener {
            performLogout()
            bindInlineUserSettings(panel)
        }
        btnChangeToken.setOnClickListener {
            prefs.edit().remove(PREF_USER_NAME).apply()
            bindInlineUserSettings(panel)
        }
        btnOpenPlaylist.setOnClickListener {
            val url = prefs.getString(PREF_USER_PLAYLIST, "") ?: ""
            if (url.isNotBlank()) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
        btnAuth.setOnClickListener {
            val login = etLogin.text.toString().trim()
            val token = etToken.text.toString().trim()
            if (login.isBlank() || token.isBlank()) {
                AlertDialog.Builder(this).setMessage("Необходимо передать login и token.")
                    .setPositiveButton("ОК", null).show()
                return@setOnClickListener
            }
            btnAuth.isEnabled = false
            thread {
                runCatching {
                    val url =
                        "https://o.avff.pw/api.php?module=app&login=${Uri.encode(login)}&token=${
                            Uri.encode(token)
                        }"
                    JSONObject(URL(url).readText())
                }.onSuccess { json ->
                    handler.post {
                        btnAuth.isEnabled = true
                        if (json.optString("valid") == "OK") {
                            val name = json.optString("name", login)
                            val playlist = json.optString("playlist", "")
                            prefs.edit().putString(PREF_USER_LOGIN, login)
                                .putString(PREF_USER_TOKEN, token).putString(PREF_USER_NAME, name)
                                .putString(PREF_USER_PLAYLIST, playlist).apply()
                            val profiles = getPlaylistProfiles().toMutableList()
                            val idx = profiles.indexOfFirst { it.name == "Пользователь" }
                            val p = PlaylistProfile("Пользователь", "url", playlist, true)
                            if (idx >= 0) profiles[idx] = p else profiles.add(p)
                            savePlaylistProfiles(profiles)
                            syncPortalPlaylistsForAuthorizedUser(token)
                            hideSettingsScreen()
                            showPlaylistPageOnHome()
                            bindInlineUserSettings(panel)
                        } else {
                            AlertDialog.Builder(this)
                                .setMessage(json.optString("message", "Неверный login или token."))
                                .setPositiveButton("ОК", null)
                                .show()
                        }
                    }
                }.onFailure { e ->
                    handler.post {
                        btnAuth.isEnabled = true
                        val isNetworkError = e is UnknownHostException ||
                                e is SocketTimeoutException ||
                                e.message?.contains(
                                    "Unable to resolve host",
                                    ignoreCase = true
                                ) == true ||
                                e.message?.contains("timeout", ignoreCase = true) == true
                        val message = if (isNetworkError) {
                            "Ошибка сети. Проверьте подключение и повторите попытку."
                        } else {
                            "Неверный login или token."
                        }
                        AlertDialog.Builder(this).setMessage(message).setPositiveButton("ОК", null)
                            .show()
                    }
                }
            }
        }
    }

    private fun showPlaylistSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_playlist_settings, null)
        val spPlaylist = view.findViewById<Spinner>(R.id.spPlaylist)
        val etPlaylistName = view.findViewById<EditText>(R.id.etPlaylistName)
        val rgSourceType = view.findViewById<RadioGroup>(R.id.rgSourceType)
        val etSourceValue = view.findViewById<EditText>(R.id.etSourceValue)
        val ivPlaylistEnabled = view.findViewById<ImageView>(R.id.ivPlaylistEnabled)
        val ivSourceEnabled = view.findViewById<ImageView>(R.id.ivSourceEnabled)
        val btnAddOrUpdate = view.findViewById<TextView>(R.id.btnAddOrUpdatePlaylist)
        val btnApply = view.findViewById<TextView>(R.id.btnApplyPlaylist)
        val btnDelete = view.findViewById<TextView>(R.id.btnDeletePlaylist)
        val btnClose = view.findViewById<TextView>(R.id.btnClosePlaylistDialog)

        var profiles = getPlaylistProfiles().toMutableList()
        var selectedIndex =
            profiles.indexOfFirst { it.name == getSelectedPlaylistName() }.takeIf { it >= 0 } ?: 0

        fun refreshSpinner() {
            val names = profiles.map { it.name }
            spPlaylist.adapter =
                ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
            if (selectedIndex in profiles.indices) spPlaylist.setSelection(selectedIndex)
        }

        fun fillFields(index: Int) {
            if (index !in profiles.indices) return
            val p = profiles[index]
            etPlaylistName.setText(p.name)
            etSourceValue.text?.clear()
            rgSourceType.check(if (p.type == "token") R.id.rbToken else R.id.rbUrl)
            val toggleRes = if (p.enabled) R.drawable.toggleright else R.drawable.toggleleft
            ivPlaylistEnabled.setImageResource(toggleRes)
            ivSourceEnabled.setImageResource(toggleRes)
        }

        fun updateSourceHint() {
            etSourceValue.hint =
                if (rgSourceType.checkedRadioButtonId == R.id.rbToken) {
                    "Введите токен с сайта O.Portal"
                } else {
                    "Введите ссылку на плейлист"
                }
        }

        fun isToggleAllowed(profile: PlaylistProfile): Boolean = profile.name != "Пользователь"

        fun toggleCurrentProfileState() {
            if (selectedIndex !in profiles.indices) return
            val current = profiles[selectedIndex]
            if (!isToggleAllowed(current)) return
            profiles[selectedIndex] = current.copy(enabled = !current.enabled)
            savePlaylistProfiles(profiles)
            fillFields(selectedIndex)
        }

        refreshSpinner()
        fillFields(selectedIndex)
        updateSourceHint()

        rgSourceType.setOnCheckedChangeListener { _, _ -> updateSourceHint() }
        ivPlaylistEnabled.setOnClickListener { toggleCurrentProfileState() }
        ivSourceEnabled.setOnClickListener { toggleCurrentProfileState() }

        spPlaylist.setOnItemSelectedListener(object :
            android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                selectedIndex = position
                fillFields(position)
                if (position in profiles.indices) {
                    setSelectedPlaylistName(profiles[position].name)
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        })

        val dialog =
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
                .setView(view)
                .create()

        btnAddOrUpdate.setOnClickListener {
            val name = etPlaylistName.text.toString().trim()
            val value = etSourceValue.text.toString().trim()
            val type = if (rgSourceType.checkedRadioButtonId == R.id.rbToken) "token" else "url"

            if (name.isBlank() || value.isBlank()) {
                showAppToast("Заполните название и значение")
                return@setOnClickListener
            }

            val existingProfile = profiles.firstOrNull { it.name.equals(name, true) }
            val enabledState = existingProfile?.enabled ?: true
            val profile = PlaylistProfile(name, type, value, enabledState)
            val existing = profiles.indexOfFirst { it.name.equals(name, true) }
            if (existing >= 0) profiles[existing] = profile else profiles.add(profile)
            selectedIndex = profiles.indexOfFirst { it.name == name }
            savePlaylistProfiles(profiles)
            setSelectedPlaylistName(name)
            refreshSpinner()
            loadPlaylist(forceReload = true, showErrors = true)
            etSourceValue.text?.clear()
            showAppToast("Плейлист сохранён")
        }

        btnApply.setOnClickListener {
            if (selectedIndex !in profiles.indices) {
                showAppToast("Выберите профиль плейлиста")
                return@setOnClickListener
            }

            val selected = profiles[selectedIndex]
            val enteredName = etPlaylistName.text.toString().trim()
            val finalName = enteredName.ifBlank { selected.name }
            val enteredValue = etSourceValue.text.toString().trim()
            val value = enteredValue.ifBlank { selected.value }
            val type = if (rgSourceType.checkedRadioButtonId == R.id.rbToken) "token" else "url"

            if (value.isBlank()) {
                showAppToast("Введите токен или URL")
                return@setOnClickListener
            }

            val duplicate = profiles.indexOfFirst { indexProfile ->
                indexProfile.name.equals(finalName, true)
            }.takeIf { it >= 0 && it != selectedIndex } ?: -1
            if (duplicate >= 0) {
                showAppToast("Профиль с таким названием уже существует")
                return@setOnClickListener
            }

            profiles[selectedIndex] = selected.copy(name = finalName, type = type, value = value)
            selectedIndex =
                profiles.indexOfFirst { it.name == finalName }.takeIf { it >= 0 } ?: selectedIndex
            savePlaylistProfiles(profiles)
            setSelectedPlaylistName(finalName)
            refreshSpinner()
            etSourceValue.text?.clear()
            loadPlaylist(forceReload = true, showErrors = true)
            showAppToast("Применено")
        }

        btnDelete.setOnClickListener {
            if (profiles.size <= 1) {
                showAppToast("Должен остаться хотя бы один профиль")
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
            showAppToast("Программа передач отсутствует")
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
                        showAppToast("Ссылки EPG сохранены")
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
                showAppToast("Настройки EPG восстановлены")
                dialogRef?.dismiss()
                showEpgSelectionDialog()
            }
        }
        actionsRow.addView(
            btnEditLinks,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        actionsRow.addView(
            btnRestoreLinks,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
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

        val dialog =
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
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

    private fun loadPlaylist(
        forceReload: Boolean = false,
        showErrors: Boolean = false,
        autoPlay: Boolean = true
    ) {
        handler.post { showAppLoadingSpinner() }
        thread {
            try {
                val playlistUrl = resolveCurrentPlaylistUrl()
                if (playlistUrl.isBlank()) {
                    handler.post {
                        hideAppLoadingSpinner()
                        tvEpg.text = "Откройте настройки и задайте токен или плейлист"
                        showUI()
                    }
                    return@thread
                }

                val content = if (!forceReload) {
                    getCachedPlaylistContent(playlistUrl)
                        ?: URL(playlistUrl).readText().also { saveCachedPlaylistContent(playlistUrl, it) }
                } else {
                    URL(playlistUrl).readText().also { saveCachedPlaylistContent(playlistUrl, it) }
                }
                currentPlaylistText = content
                val parsedChannels = M3uParser.parse(content)
                val groupedCategories = parsedChannels
                    .groupBy { ch -> ch.groupTitle?.trim().takeUnless { g -> g.isNullOrBlank() } ?: "Без категории" }
                    .filterKeys { key -> key != "{region_name}" }
                val parsedEpgUrls = extractEpgSourcesFromPlaylist(content)
                val selectedPlaylist = getSelectedPlaylistName()
                logDebug("PLAYLIST_FLOW", "PLAYLIST_CLICK selectedPlaylist=$selectedPlaylist forceReload=$forceReload")
                logDebug("PLAYLIST_FLOW", "PLAYLIST_PARSED channelsCount=${parsedChannels.size}")
                logDebug("NAV", "playlist_click name=$selectedPlaylist")

                handler.post {
                    hideAppLoadingSpinner()
                    channels.clear()
                    channels.addAll(parsedChannels)
                    applyCachedLogosToChannels()
                    availableEpgSources = parsedEpgUrls

                    val savedSelection = getSelectedEpgSources()
                    selectedEpgSources = if (availableEpgSources.isEmpty()) {
                        // У плейлиста нет собственной ссылки на EPG (x-tvg-url) — применяем
                        // сохранённые в настройках источники как есть, без пересечения с пустым списком.
                        savedSelection.toMutableSet()
                    } else {
                        val intersected = savedSelection.intersect(availableEpgSources.toSet())
                        if (intersected.isNotEmpty()) {
                            intersected.toMutableSet()
                        } else {
                            availableEpgSources.toMutableSet()
                        }
                    }
                    logDebug(
                        "EPG_DEBUG",
                        "EPG_SOURCE_SELECTION playlist=$selectedPlaylist availableEpgSources=$availableEpgSources savedSelection=$savedSelection selectedEpgSources=$selectedEpgSources"
                    )

                    if (shouldRefreshEpgNow()) {
                        synchronized(epgDataLock) { epgData.clear() }
                    }

                    if (channels.isEmpty()) {
                        tvEpg.text = "Каналы не найдены в плейлисте"
                    } else if (shouldOpenLastChannelOnStart && autoPlay) {
                        if (!restoreLastChannelAndPlay()) {
                            logDebug("NAV", "startup_last_channel_not_found")
                            showDefaultStartupScreen()
                        }
                    } else if (!autoPlay) {
                        selectedPlaylistDisplayName = getSelectedPlaylistName()
                        if (!isSettingsModalVisible) {
                            logDebug("NAV", "open_categories_screen")
                            showCategoryTilesOnHome(selectedPlaylistDisplayName, groupedCategories)
                        }
                    } else {
                        logDebug("NAV", "startup_load_ready_without_autonavigation")
                    }
                }
            } catch (e: Exception) {
                Log.e("M3U", "Ошибка загрузки плейлиста: ${redactThrowableChain(e)}")
                handler.post {
                    hideAppLoadingSpinner()
                    if (showErrors) {
                        AlertDialog.Builder(this)
                            .setTitle("Ошибка загрузки")
                            .setMessage("Не удалось загрузить плейлист по умолчанию. Проверьте токен или ссылку в настройках.")
                            .setPositiveButton("Открыть настройки") { _, _ ->
                                showSettingsDialog()
                                openPlaylistSettingsScreen()
                            }
                            .setNegativeButton("Закрыть", null)
                            .show()
                    }
                    tvEpg.text = "Ошибка загрузки плейлиста"
                    showUI()
                }
            }
        }
    }

    private fun getCachedPlaylistContent(url: String): String? {
        if (url.isBlank()) return null
        return runCatching {
            val root = JSONObject(prefs.getString(PREF_PLAYLIST_CONTENT_CACHE, "{}") ?: "{}")
            root.optString(url, "").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun saveCachedPlaylistContent(url: String, content: String) {
        if (url.isBlank() || content.isBlank()) return
        runCatching {
            val root = JSONObject(prefs.getString(PREF_PLAYLIST_CONTENT_CACHE, "{}") ?: "{}")
            root.put(url, content)
            prefs.edit().putString(PREF_PLAYLIST_CONTENT_CACHE, root.toString()).apply()
        }
    }

    private fun clearPlaylistContentCache() {
        prefs.edit().remove(PREF_PLAYLIST_CONTENT_CACHE).apply()
    }

    private fun fetchEpgSources(
        urls: List<String>,
        statusViews: Map<String, TextView> = emptyMap()
    ) {
        if (epgFetchInProgress || urls.isEmpty()) return
        val fetchGen = epgFetchGeneration
        epgFetchInProgress = true
        updateEpgDisplay()
        updateEpgLoadStatusUi()
        refreshOpenOverlayPanelsAfterEpgUpdate()
        thread {
            fun humanReadableEpgError(t: Throwable): String {
                return when {
                    t is UnknownHostException -> "Нет доступа к сети или DNS недоступен"
                    t.message?.contains(
                        "Unable to resolve host",
                        ignoreCase = true
                    ) == true -> "Не удаётся определить адрес хоста"

                    t.message?.contains(
                        "timeout",
                        ignoreCase = true
                    ) == true -> "Превышено время ожидания сети"

                    t.message?.contains("too large", ignoreCase = true) == true ||
                            t.message?.contains(
                                "safe limit",
                                ignoreCase = true
                            ) == true -> "Файл EPG слишком большой"

                    else -> t.message?.take(120) ?: t.javaClass.simpleName
                }
            }

            fun applyEpgStatus(source: String, status: String) {
                if (fetchGen != epgFetchGeneration) return
                epgSourceStatus[source] = status
                saveEpgStatusCache()
                handler.post {
                    if (fetchGen != epgFetchGeneration) return@post
                    statusViews[source]?.text = status
                    updateEpgLoadStatusUi()
                }
            }

            urls.forEach { sourceUrl ->
                if (fetchGen != epgFetchGeneration) return@forEach
                applyEpgStatus(sourceUrl, "Загрузка файла: 0%")
                var parsed = false
                var lastError = "Неизвестная ошибка"
                val epgUrlVariants = buildEpgUrlCandidates(sourceUrl)
                candidates = epgUrlVariants

                for (candidateUrl in epgUrlVariants) {
                    if (fetchGen != epgFetchGeneration) break
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
                        Log.w("EPG", redactSensitive("Ошибка обработки EPG кандидата: $candidateUrl"), t)
                        lastError = humanReadableEpgError(t)
                        when (t) {
                            is OutOfMemoryError -> applyEpgStatus(
                                sourceUrl,
                                "Файл EPG слишком большой"
                            )

                            is IOException -> if (t.message?.contains(
                                    "too large",
                                    ignoreCase = true
                                ) == true ||
                                t.message?.contains("safe limit", ignoreCase = true) == true
                            ) {
                                applyEpgStatus(sourceUrl, "Файл EPG слишком большой")
                            }
                        }
                    }
                }

                if (!parsed) {
                    applyEpgStatus(sourceUrl, "Ошибка загрузки: $lastError")
                    Log.w("EPG", redactSensitive("Не удалось обработать источник EPG: $sourceUrl ($lastError)"))
                }
            }

            if (fetchGen == epgFetchGeneration) {
                runCatching {
                    trimEpgCacheToWeek()
                    saveEpgCache()
                    saveCurrentEpgSourceFingerprint()
                }.onFailure { Log.e("EPG", "Ошибка сохранения EPG кэша", it) }
            }
            handler.post {
                if (fetchGen != epgFetchGeneration) {
                    epgFetchInProgress = false
                    updateEpgLoadStatusUi()
                    return@post
                }
                prefs.edit().putLong(PREF_EPG_LAST_REFRESH, System.currentTimeMillis()).apply()
                updateEpgDisplay()
                refreshLogo()
                epgFetchInProgress = false
                updateEpgLoadStatusUi()
                refreshOpenOverlayPanelsAfterEpgUpdate()
            }
        }

    }

    private fun ensureEpgLoadedLazy() {
        if (selectedEpgSources.isEmpty() || epgFetchInProgress) return
        val epgEmpty = isEpgDataEmpty()
        val refreshDue = shouldRefreshEpgNow()
        logDebug(
            "EPG_DEBUG",
            "ensureEpgLoadedLazy epgEmpty=$epgEmpty refreshDue=$refreshDue " +
                "savedFingerprint=${getEpgSourceFingerprint()} " +
                "currentFingerprint=${buildEpgSourceFingerprint(selectedEpgSources.toList())} " +
                "selectedEpgSources=$selectedEpgSources"
        )
        if (epgEmpty || refreshDue) {
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
            val progressCompressed =
                ProgressInputStream(compressedLimited, compressedTotal, onDownload)
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

    private fun parseXmltvDate(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return try {
            val v = value.trim()
            if (v.length < 14) return 0L
            val year = v.substring(0, 4).toInt()
            val month = v.substring(4, 6).toInt() - 1
            val day = v.substring(6, 8).toInt()
            val hour = v.substring(8, 10).toInt()
            val minute = v.substring(10, 12).toInt()
            val second = v.substring(12, 14).toInt()

            val tz: TimeZone = if (v.length >= 20) {
                val tzPart = v.substring(15).trim()
                val sign = if (tzPart.startsWith("-")) -1 else 1
                val digits = tzPart.removePrefix("+").removePrefix("-")
                if (digits.length >= 4) {
                    val tzHour = digits.substring(0, 2).toInt()
                    val tzMinute = digits.substring(2, 4).toInt()
                    val offsetMillis = sign * ((tzHour * 60 + tzMinute) * 60 * 1000)
                    SimpleTimeZone(offsetMillis, "XMLTV")
                } else {
                    TimeZone.getTimeZone("UTC")
                }
            } else {
                TimeZone.getTimeZone("UTC")
            }

            val cal = Calendar.getInstance(tz)
            cal.clear()
            cal.set(year, month, day, hour, minute, second)
            cal.timeInMillis
        } catch (_: Exception) {
            0L
        }
    }

    private fun parseEpgXml(inputStream: InputStream, totalBytes: Int, onProgress: (Int) -> Unit) {
        var loggedChannelSamples = 0
        var loggedProgrammeSamples = 0
        var programmeTotal = 0
        var programmeSkippedEmptyChannel = 0
        var programmeZeroDate = 0
        val channelIdsSeen = LinkedHashSet<String>()
        val programmeChannelIdsSeen = LinkedHashSet<String>()

        ProgressInputStream(inputStream, totalBytes.toLong(), onProgress).use { stream ->
            val parser = Xml.newPullParser()
            parser.setInput(stream, "UTF-8")
            var eventType = parser.eventType
            var tempId = ""

            val channelLookupByKey = HashMap<String, MutableList<Channel>>()
            channels.forEach { ch ->
                listOfNotNull(ch.tvgId, ch.tvgName, ch.name).forEach { rawKey ->
                    val key = rawKey.lowercase().trim()
                    if (key.isNotEmpty()) {
                        channelLookupByKey.getOrPut(key) { mutableListOf() }.add(ch)
                    }
                }
            }
            logDebug(
                "EPG_DEBUG",
                "channelLookupByKey keys sample=${channelLookupByKey.keys.take(10)} totalKeys=${channelLookupByKey.size}"
            )

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "channel" -> {
                            tempId = parser.getAttributeValue(null, "id") ?: ""
                            if (tempId.isNotBlank()) channelIdsSeen += tempId
                            if (loggedChannelSamples < 10) {
                                logDebug("EPG_DEBUG", "raw <channel id=\"$tempId\">")
                                loggedChannelSamples++
                            }
                        }
                        "icon" -> {
                            val src = parser.getAttributeValue(null, "src")
                            channelLookupByKey[tempId.lowercase().trim()]?.forEach {
                                it.logoFromEpg = src
                            }
                        }

                        "programme" -> {
                            val rawChannel = parser.getAttributeValue(null, "channel") ?: ""
                            val rawStart = parser.getAttributeValue(null, "start")
                            val rawStop = parser.getAttributeValue(null, "stop")
                            val chId = rawChannel.lowercase().trim()
                            val start = parseXmltvDate(rawStart)
                            val stop = parseXmltvDate(rawStop)
                            var title = ""
                            var desc = ""
                            while (!(parser.next() == XmlPullParser.END_TAG && parser.name == "programme")) {
                                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "title") {
                                    title = parser.nextText()
                                } else if (parser.eventType == XmlPullParser.START_TAG && parser.name == "desc") {
                                    desc = parser.nextText()
                                }
                            }

                            programmeTotal++
                            if (chId.isNotBlank()) programmeChannelIdsSeen += chId
                            if (start == 0L || stop == 0L) programmeZeroDate++
                            if (loggedProgrammeSamples < 10) {
                                logDebug(
                                    "EPG_DEBUG",
                                    "raw <programme channel=\"$rawChannel\" start=\"$rawStart\" stop=\"$rawStop\"> title=\"$title\" -> parsedStart=$start parsedStop=$stop"
                                )
                                loggedProgrammeSamples++
                            }

                            if (chId.isNotEmpty() && channelLookupByKey.containsKey(chId)) {
                                val trimmedDesc = desc.take(300)
                                synchronized(epgDataLock) {
                                    val bucket = epgData.getOrPut(chId) { mutableListOf() }
                                    if (bucket.size < MAX_PROGRAMS_PER_CHANNEL) {
                                        bucket.add(Program(title, start, stop, trimmedDesc))
                                    }
                                }
                            } else {
                                programmeSkippedEmptyChannel++
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        }

        logDebug(
            "EPG_DEBUG",
            "PARSE SUMMARY programmeTotal=$programmeTotal programmeZeroDate=$programmeZeroDate " +
                "programmeSkippedNotInPlaylist=$programmeSkippedEmptyChannel " +
                "distinctChannelIdsInXml=${channelIdsSeen.size} distinctProgrammeChannelIds=${programmeChannelIdsSeen.size}"
        )
        logDebug("EPG_DEBUG", "channelIdsSeen sample=${channelIdsSeen.take(10)}")
        logDebug("EPG_DEBUG", "programmeChannelIdsSeen sample=${programmeChannelIdsSeen.take(10)}")
        logDebug(
            "EPG_DEBUG",
            "playlist channel keys sample (tvgId/tvgName/name)=${
                channels.take(10).map { "id=${it.tvgId} name=${it.tvgName ?: it.name}" }
            }"
        )
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
        val endUnix =
            (program.stop / 1000L).coerceAtLeast((nowUnix + 6 * 60 * 60).coerceAtLeast(startUnix))
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

    private fun ensurePlayerReadyForPlayback(preferSoftwareDecoder: Boolean) {
        val oldId = mediaPlayer?.let { System.identityHashCode(it) }
        if (videoRendererPossiblyBroken && mediaPlayer != null) {
            stopPlayback()
            setupPlayer(preferSoftwareDecoder = preferSoftwareDecoder)
            val newId = mediaPlayer?.let { System.identityHashCode(it) }
            logDebug("PLAYER_LIFECYCLE", "RECREATE_PLAYER_AFTER_SOURCE_ERROR oldPlayerId=$oldId newPlayerId=$newId")
        } else if (mediaPlayer == null) {
            setupPlayer(preferSoftwareDecoder = preferSoftwareDecoder)
            logDebug("PLAYER_LIFECYCLE", "PLAYER WAS NULL, CREATED NEW PLAYER BEFORE STARTUP")
        }
        findViewById<PlayerView>(R.id.videoLayout).player = mediaPlayer
    }

    private fun playArchiveProgram(channel: Channel, program: Program) {
        val archiveUrl = buildArchiveUrl(channel, program)
        if (archiveUrl.isNullOrBlank()) {
            showAppToast("Не удалось сформировать ссылку архива")
            return
        }
        runCatching {
            homePanel.visibility = View.GONE
            setPlayerVideoVisible(true)
            val shouldUseSoftware = !preferGpuDecoding
            if (softwareDecoderMode != shouldUseSoftware || mediaPlayer == null) {
                stopPlayback()
                softwareDecoderMode = shouldUseSoftware
            }
            ensurePlayerReadyForPlayback(preferSoftwareDecoder = shouldUseSoftware)
            val player = mediaPlayer ?: run {
                logDebug("PLAYER_LIFECYCLE", "PLAYER NULL AFTER ensurePlayerReadyForPlayback (archive), abort startup")
                return@runCatching
            }
            player.stop()
            lastRequestedPlaybackUrl = archiveUrl
            logHlsManifestPreview(archiveUrl)
            player.setMediaItem(buildMediaItem(archiveUrl))
            player.prepare()
            player.playWhenReady = true
            player.play()
            logMemoryStats("play_archive_start")
            handler.removeCallbacks(memoryLogRunnable)
            handler.post(memoryLogRunnable)
            handler.postDelayed(startupSlowStreamRunnable, 45_000L)
            lastPlaybackPositionMs = -1L
            lastProgressWallClockMs = System.currentTimeMillis()
            resetPlaybackProgressBaseline(extendGrace = true)
            armPlaybackFreezeWatchdog(4000L, withStartGrace = true)
            isPlaybackPaused = false
            liveTimelineAnchorMs = 0L
            isArchivePlayback = true
            updateLiveStatusBadge()
            currentArchiveProgram = program
            archiveStreamStartMs = program.start
            updatePlayPauseButton()
            tvChannelName.text = "${currentChannelIndex + 1}. ${channel.name}"
            val stamp = SimpleDateFormat(
                "dd.MM.yyyy HH:mm",
                Locale.getDefault()
            ).format(Date(program.start))
            tvEpg.text = "Архив от $stamp - ${program.title}"
            showUI()
        }.onFailure { e ->
            showPlaybackFailureAndReturn(archiveUrl, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun applyAspectRatioMode() {
        val playerView = findViewById<PlayerView>(R.id.videoLayout)
        if (videoPinchScale <= 1.05f) {
            playerView.scaleX = 1f
            playerView.scaleY = 1f
        }
        val mode = prefs.getString(PREF_ASPECT_RATIO_MODE, "auto") ?: "auto"
        val isWinkChannel = selectedPlaylistDisplayName.contains("wink", ignoreCase = true)
        when (mode) {
            "aspect_16_9" -> {
                // Жёсткий кадр 16:9: на 18:9 появляются поля сверху/снизу, без crop.
                applyForcedPlayerAspect(16f / 9f)
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
            "fit" -> {
                clearForcedPlayerAspect()
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            "fill" -> {
                clearForcedPlayerAspect()
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
            "zoom" -> {
                clearForcedPlayerAspect()
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
            else -> {
                clearForcedPlayerAspect()
                playerView.resizeMode =
                    if (isWinkChannel) AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        }
    }

    private fun applyForcedPlayerAspect(ratio: Float) {
        val playerView = findViewById<PlayerView>(R.id.videoLayout)
        val parent = playerView.parent as? View ?: return
        parent.post {
            val parentW = parent.width.takeIf { it > 0 } ?: return@post
            val parentH = parent.height.takeIf { it > 0 } ?: return@post
            val targetH = (parentW / ratio).toInt().coerceAtMost(parentH)
            val targetW = if (targetH == parentH) (parentH * ratio).toInt().coerceAtMost(parentW) else parentW
            val lp = playerView.layoutParams as? FrameLayout.LayoutParams ?: return@post
            lp.width = targetW
            lp.height = targetH
            lp.gravity = Gravity.CENTER
            playerView.layoutParams = lp
        }
    }

    private fun clearForcedPlayerAspect() {
        val playerView = findViewById<PlayerView>(R.id.videoLayout)
        val lp = playerView.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.width == ViewGroup.LayoutParams.MATCH_PARENT &&
            lp.height == ViewGroup.LayoutParams.MATCH_PARENT
        ) return
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT
        lp.gravity = Gravity.CENTER
        playerView.layoutParams = lp
    }

    private fun initAspectRatioState() {
        aspectRatioIndex = aspectRatioLabels.indexOf(
            prefs.getString(PREF_ASPECT_RATIO_MODE, "auto").let { ASPECT_RATIO_LABEL_BY_KEY[it] ?: "Автоматически" }
        ).coerceAtLeast(0)
    }

    private fun cycleAspectRatioMode() {
        aspectRatioIndex = (aspectRatioIndex + 1) % aspectRatioLabels.size
        val key = ASPECT_RATIO_KEY_BY_LABEL[aspectRatioLabels[aspectRatioIndex]] ?: "auto"
        prefs.edit().putString(PREF_ASPECT_RATIO_MODE, key).apply()
        videoPinchScale = 1f
        applyAspectRatioMode()
        showAppToast(aspectRatioLabels[aspectRatioIndex])
        showUI()
    }

    private fun applyVideoPinchScale() {
        val playerView = findViewById<PlayerView>(R.id.videoLayout)
        if (videoPinchScale > 1.05f) {
            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            playerView.scaleX = videoPinchScale
            playerView.scaleY = videoPinchScale
        } else {
            videoPinchScale = 1f
            playerView.scaleX = 1f
            playerView.scaleY = 1f
            applyAspectRatioMode()
        }
    }

    private fun finalizePinchAspectRatio() {
        when {
            videoPinchScale > 1.12f -> {
                prefs.edit().putString(PREF_ASPECT_RATIO_MODE, "zoom").apply()
                aspectRatioIndex = aspectRatioLabels.indexOf("Обрезать").coerceAtLeast(0)
            }
            videoPinchScale < 0.92f -> {
                prefs.edit().putString(PREF_ASPECT_RATIO_MODE, "fit").apply()
                aspectRatioIndex = aspectRatioLabels.indexOf("Вписать в экран").coerceAtLeast(0)
                videoPinchScale = 1f
            }
        }
        applyAspectRatioMode()
    }

    private fun playChannel(
        forcePlay: Boolean = false,
        reason: PlayerOpenReason = PlayerOpenReason.RECOVERY
    ) {
        runCatching {
            if (!hasStartedPlaybackFromChannelClick && reason != PlayerOpenReason.CHANNEL_CLICK) {
                logDebug("NAV", "ERROR unexpected_player_open_before_channel_click reason=$reason")
                return@runCatching
            }
            val ch = channels.getOrNull(currentChannelIndex) ?: run {
                logDebug("PLAYLIST_FLOW", "OPEN_PLAYER_WITHOUT_CHANNEL blocked currentChannelIndex=$currentChannelIndex channelsCount=${channels.size}")
                showPlaylistPageOnHome()
                return@runCatching
            }
            logDebug("NAV", "open_player")
            dismissHomeForPlayback()
            ensurePlayerControlsInteractive()
            setPlayerVideoVisible(true)
            applyAspectRatioMode()
            val shouldUseSoftware = !preferGpuDecoding
            if (softwareDecoderMode != shouldUseSoftware) {
                stopPlayback()
                softwareDecoderMode = shouldUseSoftware
            }
            ensurePlayerReadyForPlayback(preferSoftwareDecoder = shouldUseSoftware)
            mediaPlayer?.stop()
            val isQualityOverrideForThisChannel =
                manualQualityOverrideChannelIndex == currentChannelIndex && manualQualityOverrideUrl != null
            lastRequestedPlaybackUrl = if (isQualityOverrideForThisChannel) {
                manualQualityOverrideUrl!!
            } else {
                manualQualityOverrideChannelIndex = -1
                manualQualityOverrideUrl = null
                ch.url
            }
            if (!isQualityOverrideForThisChannel) {
                val channelBase = ch.url.substringBefore('?')
                val masterBase = masterStreamUrl?.substringBefore('?')
                val needFetch =
                    (availableQualities.isEmpty() && availableSubtitleTracks.isEmpty()) ||
                        masterBase == null ||
                        masterBase != channelBase
                if (needFetch) {
                    fetchStreamQualityInfo(ch.url)
                } else {
                    updateCcHdButtons()
                }
            }
            startupPlaybackUrlLock = lastRequestedPlaybackUrl
            videoOnlyMinimalNoFrameRunnable?.let { handler.removeCallbacks(it) }
            videoOnlyMinimalNoFrameRunnable = null
            logHlsManifestPreview(lastRequestedPlaybackUrl)
            dumpDebugTsSegments(lastRequestedPlaybackUrl, "problem")
            firstFrameRendered = false
            handler.removeCallbacks(startupSlowStreamRunnable)
            resetPlaybackProgressBaseline(extendGrace = true)
            armPlaybackFreezeWatchdog(4000L, withStartGrace = true)
            retriedWithoutAudio = false
            videoOnlyMinimalMode = false
            runtimeRecoveryAttempted = false
            retriedWithAlternateDecoder = false
            enableAudioTrack()
            applyUnlimitedVideoConstraints()

            val player = mediaPlayer ?: run {
                logDebug("PLAYER_LIFECYCLE", "PLAYER NULL AFTER ensurePlayerReadyForPlayback, abort startup")
                return@runCatching
            }

            val allowNonIdr = prefs.getBoolean(PREF_HLS_ALLOW_NON_IDR, false)
            logPathState("STARTUP_PATH before_set_source allowNonIdr=${allowNonIdr || shouldAllowNonIdrForStream(lastRequestedPlaybackUrl)} forcePlay=$forcePlay")
            player.setMediaSource(buildPlaybackMediaSource(lastRequestedPlaybackUrl, allowNonIdr))
            player.seekToDefaultPosition()
            logPathState("STARTUP_PATH after_seek_default")
            player.prepare()
            player.seekToDefaultPosition()
            logPathState("STARTUP_PATH after_prepare_seek_default")
            applySubtitleTrackSelection(
                enabled = subtitlesEnabled,
                language = availableSubtitleTracks.getOrNull(selectedSubtitleIndex)?.language
            )
            videoLayout.subtitleView?.visibility = View.GONE
            player.playWhenReady = true
            player.play()
            logPathState("STARTUP_PATH after_play")
            logMemoryStats("play_channel_start")
            handler.removeCallbacks(memoryLogRunnable)
            handler.post(memoryLogRunnable)
            handler.postDelayed(startupSlowStreamRunnable, 45_000L)
            isPlaybackPaused = false
            liveTimelineAnchorMs = 0L
            isArchivePlayback = false
            currentArchiveProgram = null
            archiveStreamStartMs = 0L
            updateLiveStatusBadge()
            updatePlayPauseButton()

            tvChannelName.text = "${currentChannelIndex + 1}. ${ch.name}"
            hasStartedPlaybackFromChannelClick = true
            saveLastChannelPrefs(ch)
            ensureEpgLoadedLazy()
            refreshLogo()
            updateEpgDisplay()
            refreshOpenOverlayPanelsAfterEpgUpdate()
            showPlayerLoadingUi()
        }.onFailure { e ->
            Log.e("PLAYER", "Ошибка воспроизведения канала: ${redactThrowableChain(e)}")
            hidePlayerLoadingUi()
            showPlaybackFailureAndReturn(
                lastRequestedPlaybackUrl,
                e.message ?: e.javaClass.simpleName
            )
            showUI()
        }
    }


    private fun buildMediaItem(url: String): MediaItem {
        val normalizedUrl = normalizePlaybackUrl(url)
        val uri = Uri.parse(normalizedUrl)
        val lowerUrl = normalizedUrl.lowercase(Locale.ROOT)
        val mime = when {
            lowerUrl.startsWith("udp://") -> MimeTypes.VIDEO_MP2T
            lowerUrl.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
            lowerUrl.contains(".ts") || lowerUrl.contains("mpegts") -> MimeTypes.VIDEO_MP2T
            else -> null
        }
        val builder = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(mime)

        val subtitleConfigs = availableSubtitleTracks
            .filter { it.url.startsWith("http://") || it.url.startsWith("https://") }
            .map { track ->
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(track.url))
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .setLanguage(track.language)
                    .setLabel(track.label)
                    .setSelectionFlags(if (subtitlesEnabled) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            }
        if (subtitleConfigs.isNotEmpty()) {
            builder.setSubtitleConfigurations(subtitleConfigs)
        }

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

    private fun logHlsManifestPreview(url: String) {
        if (!url.contains(".m3u8", ignoreCase = true)) return
        thread(start = true) {
            runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 12_000
                    readTimeout = 20_000
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Connection", "keep-alive")
                }
                val code = conn.responseCode
                val finalUrl = conn.url.toString()
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                val lines = body.lines()
                logDebug("PLAYER_HLS", "manifest status=$code finalUrl=$finalUrl contentType=${conn.contentType} headers=${conn.headerFields}")
                logDebug("PLAYER_HLS", "manifest head:\n${lines.take(20).joinToString("\n")}")
                logDebug("PLAYER_HLS", "variantLines=${lines.filter { it.contains("#EXT-X-STREAM-INF") }.take(8)}")
                val segments = lines.filter { it.isNotBlank() && !it.startsWith("#") }
                val chosen = segments.takeLast(2).firstOrNull()
                val liveEdge = segments.lastOrNull()
                logDebug("PLAYER_HLS", "segmentLines=${segments.take(8)}")
                logDebug("PLAYER_HLS", "segmentChoice chosen=$chosen liveEdge=$liveEdge segmentCount=${segments.size}")
                logDebug("PLAYER_HLS", "hasMap=${lines.any { it.startsWith("#EXT-X-MAP") }} hasKey=${lines.any { it.startsWith("#EXT-X-KEY") }} targetDuration=${lines.firstOrNull { it.startsWith("#EXT-X-TARGETDURATION") }} mediaSequence=${lines.firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE") }}")
                if (lines.none { it.contains("#EXT-X-STREAM-INF") }) {
                    logDebug("PLAYER_HLS", "single-variant TS playlist detected; device may struggle with 1080p AVC High profile streams")
                }
            }.onFailure {
                logDebug("PLAYER_HLS", "manifest preview failed url=$url error=${it.message}", it)
            }
        }
    }

    private val hideAppToastRunnable = Runnable {
        if (::tvAppToast.isInitialized) tvAppToast.visibility = View.GONE
    }

    private fun showAppToast(message: String, durationMs: Long = 2200L) {
        tvAppToast.text = message
        tvAppToast.visibility = View.VISIBLE
        handler.removeCallbacks(hideAppToastRunnable)
        handler.postDelayed(hideAppToastRunnable, durationMs)
    }

    private val spinnerAnimators = mutableMapOf<Int, ObjectAnimator>()
    private var seekSpinnerActive = false
    private var seekSpinnerStartedAtMs = 0L

    private fun showReloadingStatus(title: String, subtitle: String, isError: Boolean = false) {
        // Center / seek / player spinners must not sit under the reload/error plate.
        dismissCenterSpinnersForStatusPlate()
        val ring = findViewById<ImageView>(R.id.ivReloadingRing)
        ring?.visibility = View.GONE
        stopSpinnerOnView(ivReloadingIcon)
        ivReloadingIcon.setBackground(null)
        ivReloadingIcon.rotation = 0f
        if (isError) {
            // Error plate: alert icon only, never a spinner.
            ivReloadingIcon.setImageResource(R.drawable.alert)
            ivReloadingIcon.visibility = View.VISIBLE
            (ivReloadingIcon.parent as? View)?.visibility = View.VISIBLE
        } else {
            // Reload plate: text only — no spinner beside or under the title.
            ivReloadingIcon.setImageDrawable(null)
            ivReloadingIcon.visibility = View.GONE
            (ivReloadingIcon.parent as? View)?.visibility = View.GONE
        }
        tvReloadingTitle.text = title
        tvReloadingSubtitle.text = subtitle
        tvReloadingSubtitle.visibility = if (subtitle.isBlank()) View.GONE else View.VISIBLE
        tvReloadingStatus.visibility = View.VISIBLE
        tvReloadingStatus.bringToFront()
        tvReloadingStatus.parent?.let { (it as? View)?.requestLayout() }
    }

    /** Hides full-screen/center loading spinners so they do not overlap status plates. */
    private fun dismissCenterSpinnersForStatusPlate() {
        hideSeekSpinnerRunnable?.let { handler.removeCallbacks(it) }
        hideSeekSpinnerRunnable = null
        if (seekSpinnerActive) {
            seekSpinnerActive = false
            findViewById<View>(R.id.loadingPanel)?.apply {
                isClickable = true
                isFocusable = true
                setBackgroundColor(Color.parseColor("#99000000"))
            }
        }
        hideAppLoadingSpinner()
        hidePlayerLoadingUi()
    }

    private fun startSpinnerOnView(view: View, durationMs: Long = 700L) {
        val key = System.identityHashCode(view)
        val existing = spinnerAnimators[key]
        if (existing?.isRunning == true) return
        stopSpinnerOnView(view)
        fun startNow() {
            val w = view.width.takeIf { it > 0 } ?: view.measuredWidth
            val h = view.height.takeIf { it > 0 } ?: view.measuredHeight
            if (w <= 0 || h <= 0) {
                view.post { startNow() }
                return
            }
            view.pivotX = w * 0.5f
            view.pivotY = h * 0.5f
            view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            val from = view.rotation
            val animator = ObjectAnimator.ofFloat(view, View.ROTATION, from, from + 360f).apply {
                duration = durationMs
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = LinearInterpolator()
                start()
            }
            spinnerAnimators[key] = animator
        }
        view.post { startNow() }
    }

    private fun stopSpinnerOnView(view: View) {
        spinnerAnimators.remove(System.identityHashCode(view))?.let {
            it.removeAllListeners()
            it.cancel()
        }
        view.animate().cancel()
        view.clearAnimation()
        view.setLayerType(View.LAYER_TYPE_NONE, null)
    }

    private fun startCompositeSpinner(root: View?) {
        if (root == null) return
        val spinner = root.findViewById<View>(R.id.ivLoadArc) ?: root
        spinner.setBackgroundResource(R.drawable.bg_portal_spinner)
        startSpinnerOnView(spinner, durationMs = 700L)
    }

    private fun stopCompositeSpinner(root: View?) {
        if (root == null) return
        val spinner = root.findViewById<View>(R.id.ivLoadArc) ?: root
        stopSpinnerOnView(spinner)
        spinner.rotation = 0f
    }

    private fun showAppLoadingSpinner() {
        if (tvReloadingStatus.visibility == View.VISIBLE) return
        val panel = findViewById<View>(R.id.loadingPanel) ?: return
        panel.visibility = View.VISIBLE
        panel.bringToFront()
        startCompositeSpinner(findViewById(R.id.loadingSpinner))
    }

    private fun hideAppLoadingSpinner() {
        val panel = findViewById<View>(R.id.loadingPanel) ?: return
        stopCompositeSpinner(findViewById(R.id.loadingSpinner))
        panel.visibility = View.GONE
    }

    private var hideSeekSpinnerRunnable: Runnable? = null

    /** Spinner for seek/rewind freeze — stays until playback leaves BUFFERING. */
    private fun showSeekSpinner() {
        if (homePanel.visibility == View.VISIBLE) return
        if (tvReloadingStatus.visibility == View.VISIBLE) return
        seekSpinnerActive = true
        seekSpinnerStartedAtMs = System.currentTimeMillis()
        hideSeekSpinnerRunnable?.let { handler.removeCallbacks(it) }
        showAppLoadingSpinner()
        findViewById<View>(R.id.loadingPanel)?.apply {
            isClickable = false
            isFocusable = false
            setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun hideSeekSpinnerIfReady(minVisibleMs: Long = 280L) {
        if (!seekSpinnerActive) return
        val player = mediaPlayer
        if (player != null && player.playbackState == androidx.media3.common.Player.STATE_BUFFERING) {
            return
        }
        val elapsed = System.currentTimeMillis() - seekSpinnerStartedAtMs
        val delay = (minVisibleMs - elapsed).coerceAtLeast(0L)
        hideSeekSpinnerRunnable?.let { handler.removeCallbacks(it) }
        val hide = Runnable {
            if (!seekSpinnerActive) return@Runnable
            if (mediaPlayer?.playbackState == androidx.media3.common.Player.STATE_BUFFERING) return@Runnable
            seekSpinnerActive = false
            hideAppLoadingSpinner()
            findViewById<View>(R.id.loadingPanel)?.apply {
                isClickable = true
                isFocusable = true
                setBackgroundColor(Color.parseColor("#99000000"))
            }
        }
        hideSeekSpinnerRunnable = hide
        if (delay == 0L) hide.run() else handler.postDelayed(hide, delay)
    }

    private fun showTransientSeekSpinner(minVisibleMs: Long = 280L) {
        showSeekSpinner()
    }

    private fun showPlayerLoadingUi() {
        if (homePanel.visibility == View.VISIBLE || isSettingsModalVisible) {
            hidePlayerChromeFully()
            return
        }
        if (tvReloadingStatus.visibility == View.VISIBLE) {
            // Status plate already on screen — do not stack a center spinner under it.
            hidePlayerLoadingUi()
            return
        }
        // Только назад + время, без остальных элементов плеера.
        // INVISIBLE (не GONE) для среднего блока — плашка времени остаётся справа.
        topGradientOverlay.visibility = View.GONE
        controlsPanel.visibility = View.GONE
        topInfoPanel.visibility = View.VISIBLE
        findViewById<View>(R.id.liveStatusBadge)?.visibility = View.INVISIBLE
        findViewById<View>(R.id.playerTopChannelInfo)?.visibility = View.INVISIBLE
        findViewById<View>(R.id.playerTopTimePlate)?.visibility = View.VISIBLE
        findViewById<View>(R.id.btnBackToMenu)?.apply {
            visibility = View.VISIBLE
            isEnabled = true
            isClickable = true
        }
        bindRealPlayerExitButtonListener()
        val spinner = findViewById<View>(R.id.playerLoadingSpinner) ?: return
        spinner.visibility = View.VISIBLE
        spinner.bringToFront()
        topInfoPanel.bringToFront()
        startCompositeSpinner(findViewById(R.id.playerLoadingSpinnerInner))
    }

    private fun hidePlayerChromeFully() {
        topInfoPanel.visibility = View.GONE
        topGradientOverlay.visibility = View.GONE
        controlsPanel.visibility = View.GONE
        findViewById<View>(R.id.playerLoadingSpinner)?.visibility = View.GONE
        stopCompositeSpinner(findViewById(R.id.playerLoadingSpinnerInner))
    }

    private fun hidePlayerLoadingUi() {
        val spinner = findViewById<View>(R.id.playerLoadingSpinner)
        stopCompositeSpinner(findViewById(R.id.playerLoadingSpinnerInner))
        spinner?.visibility = View.GONE
        if (isHomeOrSettingsForeground()) {
            hidePlayerChromeFully()
            return
        }
        findViewById<View>(R.id.liveStatusBadge)?.visibility = View.VISIBLE
        findViewById<View>(R.id.playerTopChannelInfo)?.visibility = View.VISIBLE
    }

    private fun showCenterError(message: String, durationMs: Long = 3500L) {
        showReloadingStatus(
            title = "ERROR! Возникла ошибка при просмотре трансляции: $message",
            subtitle = "Обновляем трансляцию",
            isError = true
        )
        suppressReloadOverlayUntilMs = System.currentTimeMillis() + durationMs + 1500L
        handler.postDelayed({ tvReloadingStatus.visibility = View.GONE }, durationMs)
    }

    private fun showPlaybackFailureAndReturn(url: String, error: String) {
        val message = "Ошибка воспроизведения\n$error\n"
        mediaPlayer?.clearVideoSurface()
        showCenterError(message, 5000L)
        handler.removeCallbacks(returnToLiveRunnable)
        startupPlaybackUrlLock = null
        logDebug("PLAYER_STATE", "playback failure shown; waiting for user LIVE/channel action url=$url")
    }

    private fun epgUnavailableMessage(): String =
        if (epgFetchInProgress) "Выполняется обновление программы передач"
        else "Программа передач недоступна"

    private fun updateEpgLoadStatusUi() {
        val statusView = tvEpgLoadStatus ?: findViewById(R.id.tvEpgLoadStatus) ?: return
        tvEpgLoadStatus = statusView
        val text = when {
            epgFetchInProgress -> {
                val active = epgSourceStatus.entries.firstOrNull { (_, v) ->
                    v.contains("Загрузка") || v.contains("Распаковка") || v.contains("Чтение")
                }?.value
                active ?: "EPG: обновление..."
            }
            epgSourceStatus.isNotEmpty() -> {
                val ok = epgSourceStatus.count { it.value.contains("100%") || it.value.contains("готово", true) }
                val err = epgSourceStatus.count { it.value.contains("Ошибка", true) }
                when {
                    err > 0 && ok == 0 -> "EPG: ошибка загрузки"
                    err > 0 -> "EPG: частично обновлено ($ok ок, $err ошиб.)"
                    ok > 0 -> "EPG: обновлено ($ok ист.)"
                    else -> epgSourceStatus.values.firstOrNull() ?: "EPG: ожидание"
                }
            }
            synchronized(epgDataLock) { epgData.isNotEmpty() } -> "EPG: загружено из кэша"
            else -> "EPG: ожидание"
        }
        statusView.text = text
    }

    private fun cancelAndClearEpgCache() {
        epgFetchGeneration += 1
        epgFetchInProgress = false
        synchronized(epgDataLock) { epgData.clear() }
        epgSourceStatus.clear()
        prefs.edit()
            .remove(PREF_EPG_CACHE)
            .remove(PREF_EPG_STATUS)
            .remove(PREF_EPG_LAST_REFRESH)
            .apply()
        updateEpgLoadStatusUi()
    }

    private fun updateEpgDisplay() {
        if (inputNumber.isNotEmpty()) return
        val suppressText = timelineUserSeeking || System.currentTimeMillis() < seekStatusHoldUntilMs
        if (isArchivePlayback) {
            val channel = channels.getOrNull(currentChannelIndex)
            val playbackTime = archiveStreamStartMs + (mediaPlayer?.currentPosition ?: 0L)
            val program = if (channel != null && playbackTime > 0L) {
                getProgramsForDisplay(channel).find { playbackTime in it.start until it.stop }
                    ?: currentArchiveProgram
            } else {
                currentArchiveProgram
            }
            currentArchiveProgram = program
            if (!suppressText) {
                if (program != null) {
                    val stamp = SimpleDateFormat(
                        "dd.MM.yyyy HH:mm",
                        Locale.getDefault()
                    ).format(Date(program.start))
                    tvEpg.text = "Архив от $stamp - ${program.title}"
                } else {
                    tvEpg.text = "Архив"
                }
            }
            updateTimelineUi()
            return
        }
        val ch = channels.getOrNull(currentChannelIndex) ?: return
        val now = System.currentTimeMillis()
        val cur = getProgramsWithArchiveFallback(ch).find { now in it.start until it.stop }
        if (!suppressText) {
            tvEpg.text = cur?.title ?: "Программа канала ${ch.name}"
        }
        updateTimelineUi()
    }

    private fun loadLogoWithGlide(url: String?, target: ImageView) {
        val glideUrl = if (url.isNullOrEmpty()) null else GlideUrl(
            url,
            LazyHeaders.Builder().addHeader("User-Agent", userAgent).build()
        )
        Glide.with(this)
            .load(glideUrl)
            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
            .placeholder(R.mipmap.ic_launcher)
            .into(target)
    }

    private fun qualityLabelForHeight(height: Int): String = when {
        height >= 2160 -> "2160p"
        height >= 1440 -> "1440p"
        height >= 1080 -> "1080p"
        height >= 720 -> "720p"
        height >= 480 -> "480p"
        height >= 360 -> "360p"
        else -> "${height}p"
    }

    private fun resolvePlaylistUrl(baseUrl: String, line: String): String {
        if (line.startsWith("http://") || line.startsWith("https://")) return line
        val cutIndex = baseUrl.lastIndexOf('/')
        return if (cutIndex >= 0) baseUrl.substring(0, cutIndex + 1) + line else line
    }

    private fun fetchStreamQualityInfo(baseUrl: String) {
        val myToken = ++qualityFetchToken
        availableQualities = emptyList()
        currentQualityIndex = -1
        availableSubtitleTracks = emptyList()
        selectedSubtitleIndex = -1
        availableSubtitleUrl = null
        availableAudioTracks = emptyList()
        selectedAudioIndex = -1
        subtitlesEnabled = false
        masterStreamUrl = baseUrl
        btnCcSubtitles.visibility = View.GONE
        btnAudioTrack.visibility = View.GONE
        btnHdQuality.visibility = View.GONE
        clearPlayerSubtitles()
        thread {
            runCatching {
                val separator = if (baseUrl.contains("?")) "&" else "?"
                val masterUrl = "$baseUrl${separator}v=3"
                val textBody = URL(masterUrl).readText()
                val lines = textBody.lines()
                val subtitles = mutableListOf<SubtitleOption>()
                val qualities = mutableListOf<QualityOption>()
                val audios = mutableListOf<AudioOption>()
                for (i in lines.indices) {
                    val line = lines[i].trim()
                    if (line.startsWith("#EXT-X-MEDIA:") && line.contains("TYPE=SUBTITLES")) {
                        val uri = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                            ?.let { resolvePlaylistUrl(masterUrl, it) }
                            ?: continue
                        val name = Regex("NAME=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                        val language = Regex("LANGUAGE=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                        val label = when {
                            !name.isNullOrBlank() -> name
                            !language.isNullOrBlank() -> languageLabel(language)
                            else -> "Субтитры"
                        }
                        subtitles.add(SubtitleOption(label = label, language = language, url = uri))
                    } else if (line.startsWith("#EXT-X-MEDIA:") && line.contains("TYPE=AUDIO")) {
                        val name = Regex("NAME=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                        val language = Regex("LANGUAGE=\"([^\"]+)\"").find(line)?.groupValues?.get(1)
                        val label = when {
                            !name.isNullOrBlank() -> name
                            !language.isNullOrBlank() -> languageLabel(language)
                            else -> "Аудио"
                        }
                        audios.add(AudioOption(label = label, language = language))
                    } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                        val height = Regex("RESOLUTION=\\d+x(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
                            ?: continue
                        val nextLine = lines.getOrNull(i + 1)?.trim()
                        if (nextLine.isNullOrBlank() || nextLine.startsWith("#")) continue
                        qualities.add(
                            QualityOption(
                                qualityLabelForHeight(height),
                                height,
                                resolvePlaylistUrl(masterUrl, nextLine)
                            )
                        )
                    }
                }
                Quadruple(
                    masterUrl,
                    subtitles.distinctBy { it.url },
                    qualities.distinctBy { it.height }.sortedByDescending { it.height },
                    audios.distinctBy { "${it.language}|${it.label}" }
                )
            }.onSuccess { (masterUrl, subtitles, qualities, audios) ->
                handler.post {
                    if (myToken != qualityFetchToken) return@post
                    masterStreamUrl = masterUrl
                    availableSubtitleTracks = subtitles
                    availableSubtitleUrl = subtitles.firstOrNull()?.url
                    availableQualities = qualities
                    if (audios.isNotEmpty()) {
                        availableAudioTracks = audios
                    }
                    applyPersistedTrackPreferences()
                    updateCcHdButtons()
                }
            }.onFailure {
                logDebug(
                    "PLAYER_QUALITY",
                    "fetchStreamQualityInfo failed url=${redactSensitive(baseUrl)} error=${it.message}"
                )
            }
        }
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun applyPersistedTrackPreferences() {
        val preferSubtitles = prefs.getBoolean(PREF_SUBTITLE_ENABLED, false)
        val preferLang = prefs.getString(PREF_SUBTITLE_LANGUAGE, null)
        if (preferSubtitles && availableSubtitleTracks.isNotEmpty()) {
            val idx = when {
                !preferLang.isNullOrBlank() ->
                    availableSubtitleTracks.indexOfFirst {
                        it.language.equals(preferLang, true) || it.label.equals(preferLang, true)
                    }
                else -> 0
            }.takeIf { it >= 0 } ?: 0
            selectedSubtitleIndex = idx
            subtitlesEnabled = true
            applySubtitleTrackSelection(
                enabled = true,
                language = availableSubtitleTracks.getOrNull(idx)?.language
            )
        } else {
            selectedSubtitleIndex = -1
            subtitlesEnabled = false
            applySubtitleTrackSelection(enabled = false, language = null)
            clearPlayerSubtitles()
        }

        val preferHeight = prefs.getInt(PREF_QUALITY_HEIGHT, -1)
        var qualityRestartNeeded = false
        if (preferHeight > 0 && availableQualities.isNotEmpty()) {
            val idx = availableQualities.indexOfFirst { it.height == preferHeight }
            if (idx >= 0) {
                currentQualityIndex = idx
                val targetUrl = availableQualities[idx].url
                manualQualityOverrideUrl = targetUrl
                manualQualityOverrideChannelIndex = currentChannelIndex
                if (lastRequestedPlaybackUrl.isNotBlank() && lastRequestedPlaybackUrl != targetUrl) {
                    qualityRestartNeeded = true
                }
            } else {
                currentQualityIndex = -1
                manualQualityOverrideUrl = null
                manualQualityOverrideChannelIndex = -1
            }
        } else {
            currentQualityIndex = -1
            // Keep Auto; do not clear an in-flight manual override from the quality menu mid-session
            // unless preference is Auto.
            if (preferHeight <= 0) {
                manualQualityOverrideUrl = null
                manualQualityOverrideChannelIndex = -1
            }
        }

        val preferAudio = prefs.getString(PREF_AUDIO_LANGUAGE, null)
        if (!preferAudio.isNullOrBlank() && availableAudioTracks.isNotEmpty()) {
            val idx = availableAudioTracks.indexOfFirst {
                it.language.equals(preferAudio, true) || it.label.equals(preferAudio, true)
            }
            selectedAudioIndex = if (idx >= 0) idx else -1
            applyAudioTrackSelection(selectedAudioIndex)
        } else {
            selectedAudioIndex = -1
            applyAudioTrackSelection(-1)
        }
        if (qualityRestartNeeded) {
            playChannel(forcePlay = true)
        }
    }

    private fun languageLabel(code: String): String = when (code.lowercase(Locale.ROOT)) {
        "ru", "rus" -> "Русский"
        "en", "eng" -> "English"
        "uk", "ukr" -> "Українська"
        "de", "deu", "ger" -> "Deutsch"
        "fr", "fra", "fre" -> "Français"
        "es", "spa" -> "Español"
        else -> code.uppercase(Locale.ROOT)
    }

    private fun updateCcHdButtons() {
        val hasSubtitles = availableSubtitleTracks.isNotEmpty() || availableSubtitleUrl != null
        btnCcSubtitles.visibility = if (hasSubtitles) View.VISIBLE else View.GONE
        val subtitleLabel = if (subtitlesEnabled && selectedSubtitleIndex >= 0) {
            availableSubtitleTracks.getOrNull(selectedSubtitleIndex)?.label ?: "ON"
        } else {
            "Выкл"
        }
        btnCcSubtitles.text = "CC  $subtitleLabel"
        btnCcSubtitles.alpha = if (subtitlesEnabled) 1f else 0.55f

        val hasAudioChoice = availableAudioTracks.size > 1
        btnAudioTrack.visibility = if (hasAudioChoice) View.VISIBLE else View.GONE
        val audioLabel = if (selectedAudioIndex >= 0) {
            availableAudioTracks.getOrNull(selectedAudioIndex)?.label ?: "Авто"
        } else {
            "Авто"
        }
        btnAudioTrack.text = "AU  $audioLabel"
        btnAudioTrack.alpha = if (hasAudioChoice) 1f else 0.55f

        if (availableQualities.isEmpty()) {
            btnHdQuality.visibility = View.GONE
        } else {
            btnHdQuality.visibility = View.VISIBLE
            val qualityLabel = if (currentQualityIndex < 0) {
                "Авто"
            } else {
                availableQualities.getOrNull(currentQualityIndex)?.label ?: "HD"
            }
            btnHdQuality.text = "HD  $qualityLabel"
            btnHdQuality.alpha = if (availableQualities.size > 1) 1f else 0.6f
        }
        updatePlayerControlFocusChain()
        layoutPlayerSubtitlesOverlay()
    }

    private fun updatePlayerControlFocusChain() {
        val chain = mutableListOf<Int>()
        if (btnCcSubtitles.visibility == View.VISIBLE) chain += R.id.btnCcSubtitles
        if (btnAudioTrack.visibility == View.VISIBLE) chain += R.id.btnAudioTrack
        if (btnHdQuality.visibility == View.VISIBLE) chain += R.id.btnHdQuality

        btnAspectRatio.nextFocusRightId = chain.firstOrNull() ?: R.id.btnLiveReload
        chain.forEachIndexed { index, id ->
            val view = findViewById<View>(id)
            view.nextFocusLeftId = if (index == 0) R.id.btnAspectRatio else chain[index - 1]
            view.nextFocusRightId = if (index == chain.lastIndex) R.id.btnLiveReload else chain[index + 1]
        }
        btnLiveReload.nextFocusLeftId = chain.lastOrNull() ?: R.id.btnAspectRatio
    }

    private fun showSubtitleTrackMenu() {
        if (availableSubtitleTracks.isEmpty() && availableSubtitleUrl == null) return
        val tracks = availableSubtitleTracks.ifEmpty {
            listOf(SubtitleOption("Субтитры", null, availableSubtitleUrl.orEmpty()))
        }
        val items = mutableListOf(Triple("Выкл", selectedSubtitleIndex < 0, -1))
        tracks.forEachIndexed { index, track ->
            items.add(Triple(track.label, selectedSubtitleIndex == index, index))
        }
        showPlayerTrackMenu(btnCcSubtitles, items) { index ->
            selectedSubtitleIndex = index
            subtitlesEnabled = index >= 0
            val language = tracks.getOrNull(index)?.language
            prefs.edit()
                .putBoolean(PREF_SUBTITLE_ENABLED, subtitlesEnabled)
                .putString(PREF_SUBTITLE_LANGUAGE, language ?: tracks.getOrNull(index)?.label)
                .apply()
            applySubtitleTrackSelection(enabled = subtitlesEnabled, language = language)
            if (!subtitlesEnabled) clearPlayerSubtitles()
            updateCcHdButtons()
        }
    }

    private fun showAudioTrackMenu() {
        if (availableAudioTracks.size <= 1) return
        val items = mutableListOf(Triple("Авто", selectedAudioIndex < 0, -1))
        availableAudioTracks.forEachIndexed { index, track ->
            items.add(Triple(track.label, selectedAudioIndex == index, index))
        }
        showPlayerTrackMenu(btnAudioTrack, items) { index ->
            selectedAudioIndex = index
            val lang = availableAudioTracks.getOrNull(index)?.language
                ?: availableAudioTracks.getOrNull(index)?.label
            prefs.edit().putString(PREF_AUDIO_LANGUAGE, lang).apply()
            applyAudioTrackSelection(index)
            updateCcHdButtons()
        }
    }

    private fun showQualityTrackMenu() {
        if (availableQualities.isEmpty()) return
        val items = mutableListOf(Triple("Авто", currentQualityIndex < 0, -1))
        availableQualities.forEachIndexed { index, option ->
            items.add(Triple(option.label, currentQualityIndex == index, index))
        }
        showPlayerTrackMenu(btnHdQuality, items) { index ->
            currentQualityIndex = index
            val height = availableQualities.getOrNull(index)?.height ?: -1
            prefs.edit().putInt(PREF_QUALITY_HEIGHT, height).apply()
            updateCcHdButtons()
            if (index < 0) {
                manualQualityOverrideUrl = null
                manualQualityOverrideChannelIndex = -1
                val master = masterStreamUrl ?: channels.getOrNull(currentChannelIndex)?.url
                if (!master.isNullOrBlank()) {
                    playChannel(forcePlay = true)
                }
            } else {
                val target = availableQualities[index]
                manualQualityOverrideUrl = target.url
                manualQualityOverrideChannelIndex = currentChannelIndex
                playChannel(forcePlay = true)
            }
        }
    }

    private fun showPlayerTrackMenu(
        anchor: View,
        items: List<Triple<String, Boolean, Int>>,
        onSelect: (Int) -> Unit
    ) {
        dismissPlayerTrackMenu()
        val content = layoutInflater.inflate(R.layout.player_track_menu, null) as LinearLayout
        val itemHeight = resources.getDimensionPixelSize(R.dimen.player_track_menu_item_height)
        val itemGap = resources.getDimensionPixelSize(R.dimen.player_track_menu_item_gap)
        val textSizePx = resources.getDimension(R.dimen.player_track_menu_item_text_size)
        items.forEachIndexed { i, (label, selected, value) ->
            val itemView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    itemHeight
                ).also { lp ->
                    if (i > 0) lp.topMargin = itemGap
                }
                background = getDrawable(R.drawable.bg_player_track_menu_item)
                gravity = Gravity.CENTER
                includeFontPadding = false
                isFocusable = true
                isClickable = true
                isSelected = selected
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
                typeface = Typeface.create(golosTypeface ?: Typeface.SANS_SERIF, Typeface.NORMAL)
                text = label
                minWidth = dpToPx(112)
                setPadding(dpToPx(14), 0, dpToPx(14), 0)
                setOnClickListener {
                    dismissPlayerTrackMenu()
                    onSelect(value)
                }
            }
            content.addView(itemView)
        }
        content.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popup = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            elevation = dpToPx(8).toFloat()
            setBackgroundDrawable(null)
            setOnDismissListener { playerTrackMenu = null }
        }
        playerTrackMenu = popup
        val yOff = -(content.measuredHeight + dpToPx(10) + anchor.height)
        popup.showAsDropDown(anchor, 0, yOff)
        content.post {
            content.getChildAt(items.indexOfFirst { it.second }.coerceAtLeast(0))?.requestFocus()
        }
    }

    private fun dismissPlayerTrackMenu() {
        playerTrackMenu?.dismiss()
        playerTrackMenu = null
    }

    private fun applySubtitleTrackSelection(enabled: Boolean, language: String?) {
        val player = mediaPlayer ?: return
        val builder = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
        if (enabled && !language.isNullOrBlank()) {
            builder.setPreferredTextLanguage(language)
        }
        player.trackSelectionParameters = builder.build()
        videoLayout.subtitleView?.visibility = View.GONE
    }

    private fun applyAudioTrackSelection(index: Int) {
        val player = mediaPlayer ?: return
        val builder = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
        val option = availableAudioTracks.getOrNull(index)
        if (option != null && option.groupIndex >= 0 && option.trackIndex >= 0) {
            val groups = player.currentTracks.groups
            val group = groups.getOrNull(option.groupIndex)
            if (group != null && group.type == C.TRACK_TYPE_AUDIO) {
                builder.setOverrideForType(
                    TrackSelectionOverride(group.mediaTrackGroup, listOf(option.trackIndex))
                )
            }
        } else if (option != null && !option.language.isNullOrBlank()) {
            builder.setPreferredAudioLanguage(option.language)
        }
        player.trackSelectionParameters = builder.build()
    }

    private fun ensurePlayerControlsInteractive() {
        if (!::controlsPanel.isInitialized) return
        controlsPanel.isEnabled = true
        controlsPanel.isClickable = true
        controlsPanel.isFocusable = false
        listOf(
            R.id.btnPlayPause, R.id.btnLiveReload, R.id.btnBackLeft, R.id.btnBackRight,
            R.id.btnLock, R.id.btnEpgPlayer, R.id.btnAspectRatio,
            R.id.btnCcSubtitles, R.id.btnAudioTrack, R.id.btnHdQuality
        ).forEach { id ->
            findViewById<View?>(id)?.let { v ->
                v.isEnabled = true
                v.isClickable = true
            }
        }
    }

    private fun layoutPlayerSubtitlesOverlay() {
        if (!::playerSubtitlesOverlay.isInitialized || !::tvPlayerSubtitles.isInitialized) return
        val w = playerSubtitlesOverlay.width.takeIf { it > 0 } ?: return
        val h = playerSubtitlesOverlay.height.takeIf { it > 0 } ?: return
        val side = (w * 0.08f).toInt()
        val controlsLift = if (::controlsPanel.isInitialized && controlsPanel.visibility == View.VISIBLE) {
            controlsPanel.height + dpToPx(12)
        } else {
            (h * 0.12f).toInt()
        }
        val bottom = controlsLift.coerceAtLeast((h * 0.08f).toInt())
        val maxH = (h * 0.22f).toInt()
        playerSubtitlesOverlay.setPadding(side, 0, side, bottom)
        tvPlayerSubtitles.maxHeight = maxH.coerceAtLeast(dpToPx(40))
        val lp = tvPlayerSubtitles.layoutParams as FrameLayout.LayoutParams
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        tvPlayerSubtitles.layoutParams = lp
        // Keep captions under top chrome but above the control panel.
        if (::controlsPanel.isInitialized) {
            playerSubtitlesOverlay.elevation = 0f
            controlsPanel.elevation = 8f
        }
    }

    private fun updatePlayerSubtitlesFromCues(cueGroup: CueGroup) {
        if (!subtitlesEnabled) {
            clearPlayerSubtitles()
            return
        }
        val text = cueGroup.cues.mapNotNull { cue: Cue ->
            cue.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }.joinToString("\n")
        if (text.isBlank()) {
            clearPlayerSubtitles()
            return
        }
        layoutPlayerSubtitlesOverlay()
        tvPlayerSubtitles.text = text
        tvPlayerSubtitles.visibility = View.VISIBLE
        playerSubtitlesOverlay.bringToFront()
    }

    private fun clearPlayerSubtitles() {
        if (::tvPlayerSubtitles.isInitialized) {
            tvPlayerSubtitles.text = ""
            tvPlayerSubtitles.visibility = View.GONE
        }
    }


    private fun discoverSubtitleTracksFromPlayer(tracks: Tracks) {
        var changed = false
        if (availableSubtitleTracks.isEmpty()) {
            val discovered = mutableListOf<SubtitleOption>()
            for (group in tracks.groups) {
                if (group.type != C.TRACK_TYPE_TEXT) continue
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val language = format.language
                    val label = format.label?.takeIf { it.isNotBlank() }
                        ?: language?.let { languageLabel(it) }
                        ?: "Субтитры"
                    discovered.add(
                        SubtitleOption(
                            label = label,
                            language = language,
                            url = ""
                        )
                    )
                }
            }
            if (discovered.isNotEmpty()) {
                availableSubtitleTracks = discovered.distinctBy { "${it.language}|${it.label}" }
                availableSubtitleUrl = availableSubtitleTracks.firstOrNull()?.url
                changed = true
            }
        }
        val audioDiscovered = mutableListOf<AudioOption>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val format = group.getTrackFormat(i)
                val language = format.language
                val label = format.label?.takeIf { it.isNotBlank() }
                    ?: language?.let { languageLabel(it) }
                    ?: "Аудио ${audioDiscovered.size + 1}"
                audioDiscovered.add(
                    AudioOption(
                        label = label,
                        language = language,
                        groupIndex = groupIndex,
                        trackIndex = i
                    )
                )
            }
        }
        if (audioDiscovered.size > 1) {
            availableAudioTracks = audioDiscovered.distinctBy { "${it.groupIndex}:${it.trackIndex}:${it.language}|${it.label}" }
            changed = true
        }
        if (changed) {
            applyPersistedTrackPreferences()
            updateCcHdButtons()
        }
    }

    private fun toggleSubtitles() {
        // Kept for compatibility; UI uses the track menu.
        if (availableSubtitleTracks.isEmpty() && availableSubtitleUrl == null) return
        if (subtitlesEnabled) {
            selectedSubtitleIndex = -1
            subtitlesEnabled = false
            applySubtitleTrackSelection(enabled = false, language = null)
            clearPlayerSubtitles()
        } else {
            selectedSubtitleIndex = 0
            subtitlesEnabled = true
            applySubtitleTrackSelection(
                enabled = true,
                language = availableSubtitleTracks.firstOrNull()?.language
            )
        }
        updateCcHdButtons()
    }

    private fun cycleQuality() {
        // Kept for compatibility; UI uses the track menu.
        if (availableQualities.isEmpty()) return
        currentQualityIndex = if (currentQualityIndex < 0) 0 else {
            if (currentQualityIndex >= availableQualities.lastIndex) -1 else currentQualityIndex + 1
        }
        updateCcHdButtons()
        if (currentQualityIndex < 0) {
            manualQualityOverrideUrl = null
            manualQualityOverrideChannelIndex = -1
        } else {
            val target = availableQualities[currentQualityIndex]
            manualQualityOverrideUrl = target.url
            manualQualityOverrideChannelIndex = currentChannelIndex
        }
        playChannel(forcePlay = true)
    }

    private fun updateLiveStatusBadge() {
        if (isArchivePlayback) {
            liveStatusBadge.setBackgroundResource(R.drawable.bg_live_badge_blue)
            liveStatusDot.setBackgroundResource(R.drawable.dot_live_blue)
            tvLiveStatusText.text = "АРХИВ"
        } else {
            liveStatusBadge.setBackgroundResource(R.drawable.bg_live_badge_red)
            liveStatusDot.setBackgroundResource(R.drawable.dot_live_red)
            tvLiveStatusText.text = "LIVE"
        }
        liveStatusBadge.requestLayout()
    }

    private fun liveProgressInProgram(p: Program): Int {
        val liveMs = minOf(System.currentTimeMillis(), p.stop)
        val duration = (p.stop - p.start).coerceAtLeast(1L)
        return (((liveMs - p.start).toDouble() / duration.toDouble()) * 1000.0)
            .toInt().coerceIn(0, 1000)
    }

    private fun switchToLivePlayback() {
        if (!isArchivePlayback) return
        isArchivePlayback = false
        currentArchiveProgram = null
        archiveStreamStartMs = 0L
        liveTimelineAnchorMs = 0L
        isPlaybackPaused = false
        playChannel(forcePlay = true, reason = PlayerOpenReason.LIVE_RETRY)
    }
    private fun refreshLogo() = updateLiveStatusBadge()

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
        val reused = mediaPlayer != null
        val oldId = mediaPlayer?.let { System.identityHashCode(it) }
        logDebug("PLAYER_LIFECYCLE", "setupPlayer start reused=$reused oldPlayerId=$oldId forceFreshPlayerSession=$forceFreshPlayerSession looper=${Looper.myLooper()} thread=${Thread.currentThread().name}")
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(25_000)
            .setDefaultRequestProperties(
                mapOf(
                    "Accept" to "*/*",
                    "Connection" to "keep-alive"
                )
            )

        val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        val loadControl = DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setBufferDurationsMs(
                6_000,
                20_000,
                1_200,
                2_500
            )
            .setTargetBufferBytes(C.LENGTH_UNSET)
            .setBackBuffer(0, false)
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

        val useFfmpegAudio = prefs.getBoolean(PREF_USE_FFMPEG_AUDIO_FOR_MPEG_L2, USE_FFMPEG_AUDIO_FOR_MPEG_L2)
        val extensionMode = if (useFfmpegAudio) {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        } else {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
        }
        logDebug("PLAYER_STATE", "ffmpeg_mode=${if (useFfmpegAudio) "PREFER" else "OFF"}")
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(extensionMode)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(codecSelector)

        val allowNonIdr = prefs.getBoolean(PREF_HLS_ALLOW_NON_IDR, false)
        val hlsPayloadReaderFlags =
            if (allowNonIdr) DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES else 0
        logDebug("PLAYER_HLS", "hlsPayloadReaderFlags=$hlsPayloadReaderFlags allowNonIdr=$allowNonIdr")
        val mediaSourceFactory = DefaultMediaSourceFactory(httpFactory).setDataSourceFactory(httpFactory)

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
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { player ->
                findViewById<PlayerView>(R.id.videoLayout).player = player
                val newId = System.identityHashCode(player)
                logDebug("PLAYER_LIFECYCLE", "NEW PLAYER SESSION CREATED oldPlayerId=$oldId newPlayerId=$newId reused=$reused lastReleasedPlayerId=$lastReleasedPlayerId playerViewAttached=${findViewById<PlayerView>(R.id.videoLayout).player === player}")
                forceFreshPlayerSession = false
                val listener = object : androidx.media3.common.Player.Listener {
                    override fun onRenderedFirstFrame() {
                        firstFrameRendered = true
                        startupPlaybackUrlLock = null
                        lastPlaybackPositionMs = player.currentPosition
                        lastProgressWallClockMs = System.currentTimeMillis()
                        onPlaybackRecoverySucceeded()
                        handler.removeCallbacks(startupSlowStreamRunnable)
                        resetPlaybackProgressBaseline(extendGrace = true)
                        armPlaybackFreezeWatchdog(4000L, withStartGrace = true)
                        hideSeekSpinnerIfReady(0L)
                        hidePlayerLoadingUi()
                        if (homePanel.visibility != View.VISIBLE && !isPlayerOverlayOpen()) {
                            if (suppressAutoPlayerUiOnce) {
                                suppressAutoPlayerUiOnce = false
                                hideUI()
                            } else {
                                showUI()
                            }
                        }
                        layoutPlayerSubtitlesOverlay()
                        logDebug("PLAYER_STATE", "onRenderedFirstFrame videoOnlyMode=$videoOnlyMinimalMode url=$lastRequestedPlaybackUrl")
                        logAudioTrackState("first_frame_rendered")
                        logPathState("STARTUP_PATH onRenderedFirstFrame")
                        if (videoRendererPossiblyBroken) {
                            videoRendererPossiblyBroken = false
                            logDebug("PLAYER_LIFECYCLE", "SOURCE_ERROR_RECOVERY_SUCCESS renderer_state_restored=true")
                        }
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        logSelectedPlaybackFormats(tracks)
                        if (retriedWithoutAudio) {
                            logAudioTrackState("post_fallback_tracks_changed")
                        }
                        if (!firstFrameRendered && !retriedWithoutAudio && hasSelectedMpegL2Audio(tracks) && !shouldAllowNonIdrForStream(lastRequestedPlaybackUrl)) {
                            logDebug("PLAYER_STATE", "mpeg-l2 detected, but auto video-only fallback disabled in production path")
                        }
                        // Discover in-manifest text tracks even if HLS parse missed them.
                        discoverSubtitleTracksFromPlayer(tracks)
                    }

                    override fun onCues(cueGroup: CueGroup) {
                        updatePlayerSubtitlesFromCues(cueGroup)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        val state = when (playbackState) {
                            androidx.media3.common.Player.STATE_IDLE -> "IDLE"
                            androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
                            androidx.media3.common.Player.STATE_READY -> "READY"
                            androidx.media3.common.Player.STATE_ENDED -> "ENDED"
                            else -> "UNKNOWN($playbackState)"
                        }
                        logDebug("PLAYER_STATE", "state=$state isLoading=${player.isLoading} playWhenReady=${player.playWhenReady} isPlaying=${player.isPlaying} suppression=${player.playbackSuppressionReason} playerError=${player.playerError?.message} videoSize=${player.videoSize.width}x${player.videoSize.height} url=$lastRequestedPlaybackUrl")
                        if (playbackState == androidx.media3.common.Player.STATE_BUFFERING) {
                            logMemoryStats("state_buffering")
                            if (seekSpinnerActive) showSeekSpinner()
                        } else if (
                            playbackState == androidx.media3.common.Player.STATE_READY ||
                            playbackState == androidx.media3.common.Player.STATE_ENDED ||
                            playbackState == androidx.media3.common.Player.STATE_IDLE
                        ) {
                            hideSeekSpinnerIfReady()
                        }
                        // Live stream ended unexpectedly — trigger immediate recovery without
                        // waiting for the watchdog's slow HARD_STOP timer.
                        if (playbackState == androidx.media3.common.Player.STATE_ENDED &&
                            player.playWhenReady &&
                            firstFrameRendered &&
                            !isPlaybackPaused &&
                            !isArchivePlayback
                        ) {
                            handler.post {
                                if (!isHomeOrSettingsForeground() && !isPlayerOverlayOpen()) {
                                    logDebug("PLAYER_STATE", "live stream ENDED — scheduling immediate recovery")
                                    notifyPlaybackStall("Трансляция завершилась", immediate = true)
                                }
                            }
                        }
                    }

                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        logDebug("PLAYER_STATE", "onPlayWhenReadyChanged playWhenReady=$playWhenReady reason=$reason url=$lastRequestedPlaybackUrl")
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val causeChain = generateSequence(error.cause) { it.cause }
                            .take(6)
                            .joinToString(" -> ") { "${it::class.java.simpleName}:${it.message}" }
                        logDebug("PLAYER_STATE", "onPlayerError url=$lastRequestedPlaybackUrl message=${error.message} code=${error.errorCode} codeName=${error.errorCodeName} causeChain=$causeChain", error)
                        startupPlaybackUrlLock = null
                        logMemoryStats("on_player_error")
                        if (
                            error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                            error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                            error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                        ) {
                            videoRendererPossiblyBroken = true
                            logDebug("PLAYER_LIFECYCLE", "PLAYER_ERROR_SOURCE marked_renderer_tainted=true errorCode=${error.errorCode} codeName=${error.errorCodeName}")
                        }
                        handler.post {
                            handler.removeCallbacks(startupSlowStreamRunnable)
                            handler.removeCallbacks(playbackFreezeWatchdogRunnable)
                            videoOnlyMinimalMode = false
                            audioTrackForcedDisabled = false
                            enableAudioTrack()
                            lastPlaybackStallReason =
                                "${error.errorCodeName}: ${error.message ?: "PlaybackException"}"
                            if (playbackRecoveryActive) {
                                handler.postDelayed(playbackFreezeWatchdogRunnable, 4000L)
                                return@post
                            }
                            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                                if (behindLiveWindowRecoveryInProgress) {
                                    showPlaybackFailureAndReturn(lastRequestedPlaybackUrl, "ERROR_CODE_BEHIND_LIVE_WINDOW")
                                    return@post
                                }
                                behindLiveWindowRecoveryInProgress = true
                                logDebug("PLAYER_STATE", "BEHIND_LIVE_WINDOW recovery started attempt=1 url=$lastRequestedPlaybackUrl")
                                val playerRef = mediaPlayer ?: run {
                                    behindLiveWindowRecoveryInProgress = false
                                    return@post
                                }
                                val allowNonIdr = prefs.getBoolean(PREF_HLS_ALLOW_NON_IDR, false)
                                playerRef.stop()
                                playerRef.clearMediaItems()
                                logPathState("BEHIND_LIVE_WINDOW after_clear")
                                playerRef.setMediaSource(buildPlaybackMediaSource(lastRequestedPlaybackUrl, allowNonIdr))
                                playerRef.seekToDefaultPosition()
                                logPathState("BEHIND_LIVE_WINDOW after_seek_default")
                                playerRef.prepare()
                                playerRef.playWhenReady = true
                                playerRef.play()
                                logPathState("BEHIND_LIVE_WINDOW after_prepare_play")
                                behindLiveWindowRecoveryInProgress = false
                                return@post
                            }
                            if (error.errorCodeName == "ERROR_CODE_FAILED_RUNTIME_CHECK") {
                                showCenterError("Буферизация потока, попробуйте LIVE", 2000L)
                                startupPlaybackUrlLock = null
                                return@post
                            }
                            showPlaybackFailureAndReturn(
                                lastRequestedPlaybackUrl,
                                "${error.errorCodeName}: ${error.message ?: "PlaybackException"}"
                            )
                        }
                    }
                }
                player.addListener(listener)
                playerEventListener = listener

                val analyticsListener = object : AnalyticsListener {
                    private fun logDecoderCounters(stage: String, counters: DecoderCounters?) {
                        counters ?: return
                        counters.ensureUpdated()
                        logDebug(
                            "PLAYER_DECODER",
                            "stage=$stage renderedOutputBufferCount=${counters.renderedOutputBufferCount} skippedOutputBufferCount=${counters.skippedOutputBufferCount} droppedBufferCount=${counters.droppedBufferCount} droppedToKeyframeCount=${counters.droppedToKeyframeCount} maxConsecutiveDroppedBufferCount=${counters.maxConsecutiveDroppedBufferCount} url=$lastRequestedPlaybackUrl"
                        )
                    }

                    override fun onLoadStarted(
                        eventTime: AnalyticsListener.EventTime,
                        loadEventInfo: LoadEventInfo,
                        mediaLoadData: MediaLoadData
                    ) {
                        logDebug("PLAYER_NET", "onLoadStarted type=${mediaLoadData.dataType} trackType=${mediaLoadData.trackType} uri=${loadEventInfo.dataSpec.uri} url=$lastRequestedPlaybackUrl")
                    }

                    override fun onLoadCompleted(
                        eventTime: AnalyticsListener.EventTime,
                        loadEventInfo: LoadEventInfo,
                        mediaLoadData: MediaLoadData
                    ) {
                        logDebug("PLAYER_NET", "onLoadCompleted type=${mediaLoadData.dataType} trackType=${mediaLoadData.trackType} uri=${loadEventInfo.dataSpec.uri} bytes=${loadEventInfo.bytesLoaded} loadMs=${loadEventInfo.loadDurationMs} headers=${loadEventInfo.responseHeaders} url=$lastRequestedPlaybackUrl")
                    }

                    override fun onLoadError(
                        eventTime: AnalyticsListener.EventTime,
                        loadEventInfo: LoadEventInfo,
                        mediaLoadData: MediaLoadData,
                        error: IOException,
                        wasCanceled: Boolean
                    ) {
                        logDebug("PLAYER_NET", "onLoadError type=${mediaLoadData.dataType} trackType=${mediaLoadData.trackType} uri=${loadEventInfo.dataSpec.uri} bytes=${loadEventInfo.bytesLoaded} loadMs=${loadEventInfo.loadDurationMs} canceled=$wasCanceled headers=${loadEventInfo.responseHeaders} error=${error.message} url=$lastRequestedPlaybackUrl", error)
                    }

                    override fun onLoadCanceled(
                        eventTime: AnalyticsListener.EventTime,
                        loadEventInfo: LoadEventInfo,
                        mediaLoadData: MediaLoadData
                    ) {
                        logDebug("PLAYER_NET", "onLoadCanceled type=${mediaLoadData.dataType} trackType=${mediaLoadData.trackType} uri=${loadEventInfo.dataSpec.uri} bytes=${loadEventInfo.bytesLoaded} loadMs=${loadEventInfo.loadDurationMs} url=$lastRequestedPlaybackUrl")
                    }

                    override fun onDroppedVideoFrames(
                        eventTime: AnalyticsListener.EventTime,
                        droppedFrames: Int,
                        elapsedMs: Long
                    ) {
                        Log.w("PLAYER_STATE", redactSensitive("droppedFrames=$droppedFrames elapsedMs=$elapsedMs url=$lastRequestedPlaybackUrl"))
                    }

                    override fun onVideoDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderName: String,
                        initializedTimestampMs: Long,
                        initializationDurationMs: Long
                    ) {
                        logDebug("PLAYER_STATE", "videoDecoderInitialized decoder=$decoderName initMs=$initializationDurationMs url=$lastRequestedPlaybackUrl")
                    }

                    override fun onAudioDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderName: String,
                        initializedTimestampMs: Long,
                        initializationDurationMs: Long
                    ) {
                        logDebug("PLAYER_STATE", "audioDecoderInitialized decoder=$decoderName initMs=$initializationDurationMs url=$lastRequestedPlaybackUrl")
                    }

                    override fun onVideoEnabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
                        logDecoderCounters("video_enabled", decoderCounters)
                    }

                    override fun onVideoDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
                        logDecoderCounters("video_disabled", decoderCounters)
                    }
                }
                player.addAnalyticsListener(analyticsListener)
                playerAnalyticsListener = analyticsListener
            }
    }

    private fun normalizePlaybackUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.startsWith("/")) return "file://$trimmed"
        return trimmed
    }

    private fun tsPayloadReaderFlags(allowNonIdr: Boolean, url: String): Int {
        val effectiveAllowNonIdr = allowNonIdr || shouldAllowNonIdrForStream(url)
        return if (effectiveAllowNonIdr) DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES else 0
    }

    private fun buildPlaybackMediaSource(url: String, allowNonIdr: Boolean): MediaSource {
        val normalizedUrl = normalizePlaybackUrl(url)
        val lower = normalizedUrl.lowercase(Locale.ROOT)
        return when {
            lower.startsWith("udp://") -> buildUdpMediaSource(normalizedUrl, allowNonIdr)
            lower.startsWith("file://") -> buildProgressiveMediaSource(normalizedUrl, allowNonIdr)
            lower.contains(".m3u8") -> buildHlsMediaSource(normalizedUrl, allowNonIdr)
            lower.contains(".ts") || lower.contains("mpegts") -> buildProgressiveMediaSource(normalizedUrl, allowNonIdr)
            else -> buildHlsMediaSource(normalizedUrl, allowNonIdr)
        }
    }

    private fun buildUdpMediaSource(url: String, allowNonIdr: Boolean): ProgressiveMediaSource {
        val dataSourceFactory = DataSource.Factory {
            UdpDataSource(UdpDataSource.DEFAULT_MAX_PACKET_SIZE, 8_000)
        }
        val extractorsFactory = DefaultExtractorsFactory()
            .setTsExtractorFlags(tsPayloadReaderFlags(allowNonIdr, url))
        return ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
            .createMediaSource(buildMediaItem(url))
    }

    private fun buildProgressiveMediaSource(url: String, allowNonIdr: Boolean): ProgressiveMediaSource {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(25_000)
        val dataSourceFactory = if (url.lowercase(Locale.ROOT).startsWith("file://")) {
            DefaultDataSource.Factory(this)
        } else {
            DefaultDataSource.Factory(this, httpFactory)
        }
        val extractorsFactory = DefaultExtractorsFactory()
            .setTsExtractorFlags(tsPayloadReaderFlags(allowNonIdr, url))
        return ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
            .createMediaSource(buildMediaItem(url))
    }

    private fun buildHlsMediaSource(url: String, allowNonIdr: Boolean): HlsMediaSource {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(25_000)
        val effectiveAllowNonIdr = allowNonIdr || shouldAllowNonIdrForStream(url)
        val hlsPayloadReaderFlags =
            if (effectiveAllowNonIdr) DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES else 0
        logDebug("PLAYER_HLS", "hlsPayloadReaderFlags=$hlsPayloadReaderFlags allowNonIdr=$effectiveAllowNonIdr requestedAllowNonIdr=$allowNonIdr")
        return HlsMediaSource.Factory(httpFactory)
            .setAllowChunklessPreparation(false)
            .setExtractorFactory(DefaultHlsExtractorFactory(hlsPayloadReaderFlags, true))
            .createMediaSource(buildMediaItem(url))
    }

    private fun shouldAllowNonIdrForStream(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        if (lower.contains(".m3u8")) return true
        if (lower.startsWith("udp://") || lower.contains(".ts") || lower.contains("mpegts")) return true
        return false
    }

    private fun dumpDebugTsSegments(playlistUrl: String, label: String) {
        if (!enableTsForensicDump) return
        thread(name = "ts-dump-$label") {
            runCatching {
                val text = URL(playlistUrl).openConnection().run {
                    (this as HttpURLConnection).apply {
                        connectTimeout = 10000
                        readTimeout = 15000
                        setRequestProperty("User-Agent", userAgent)
                    }.inputStream.bufferedReader().use { it.readText() }
                }
                val segments = text.lineSequence()
                    .map { it.trim() }
                    .filter { it.startsWith("http", true) && it.contains(".ts") }
                    .toList()
                val tail = segments.takeLast(3)
                val dir = File(filesDir, "debug_ts/$label-${System.currentTimeMillis()}")
                dir.mkdirs()
                tail.forEachIndexed { index, segUrl ->
                    val out = File(dir, "segment_${index + 1}.ts")
                    URL(segUrl).openStream().use { input -> out.outputStream().use { input.copyTo(it) } }
                }
                logDebug("PLAYER_HLS", "forensic TS dump saved path=${dir.absolutePath} files=${tail.size}")
            }.onFailure {
                logDebug("PLAYER_HLS", "forensic TS dump failed url=$playlistUrl error=${it.message}", it)
            }
        }
    }

    private fun logMemoryStats(stage: String) {
        val rt = Runtime.getRuntime()
        val maxMb = rt.maxMemory() / (1024 * 1024)
        val totalMb = rt.totalMemory() / (1024 * 1024)
        val freeMb = rt.freeMemory() / (1024 * 1024)
        val usedMb = totalMb - freeMb
        logDebug("PLAYER_MEM", "stage=$stage usedMb=$usedMb totalMb=$totalMb freeMb=$freeMb maxMb=$maxMb url=$lastRequestedPlaybackUrl")
    }

    private fun logPlaybackProgress(stage: String) {
        val player = mediaPlayer ?: return
        logDebug(
            "PLAYER_PROGRESS",
            "stage=$stage posMs=${player.currentPosition} bufferedPosMs=${player.bufferedPosition} totalBufferedMs=${player.totalBufferedDuration} isPlaying=${player.isPlaying} isLoading=${player.isLoading} playWhenReady=${player.playWhenReady} state=${player.playbackState} url=$lastRequestedPlaybackUrl"
        )
    }


    private fun shouldRetryWithoutAudio(error: PlaybackException): Boolean {
        if (retriedWithoutAudio || lastRequestedPlaybackUrl.isBlank()) return false
        val text = ((error.message ?: "") + " " + (error.cause?.message ?: "")).lowercase(Locale.ROOT)
        return (text.contains("mpga") || text.contains("mpeg audio") || text.contains("mp2") || text.contains("mp3")) && text.contains("audio")
    }

    private fun hasSelectedMpegL2Audio(tracks: Tracks): Boolean {
        for (group in tracks.groups) {
            for (i in 0 until group.length) {
                if (!group.isTrackSelected(i)) continue
                val format = group.getTrackFormat(i)
                val sample = format.sampleMimeType?.lowercase(Locale.ROOT).orEmpty()
                val codecs = format.codecs?.lowercase(Locale.ROOT).orEmpty()
                if (sample == "audio/mpeg" ||
                    sample == "audio/mpeg-l1" ||
                    sample == "audio/mpeg-l2" ||
                    sample == "audio/mpeg-l3" ||
                    codecs.contains("mp2") ||
                    codecs.contains("mp3") ||
                    codecs.contains("mpga") ||
                    codecs.contains("mpeg")
                ) {
                    return true
                }
            }
        }
        return false
    }

    private fun logSelectedPlaybackFormats(tracks: Tracks) {
        logDebug("PLAYER_FORMAT", "trackGroups=${tracks.groups.size} url=$lastRequestedPlaybackUrl")
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
                logDebug(
                    "PLAYER_FORMAT",
                    "$type codec=${format.codecs ?: format.sampleMimeType.orEmpty()} sampleMime=${format.sampleMimeType.orEmpty()} " +
                            "containerMime=${format.containerMimeType.orEmpty()} bitrate=${format.bitrate} " +
                            "size=${format.width}x${format.height} lang=${format.language.orEmpty()} id=${format.id.orEmpty()} url=$lastRequestedPlaybackUrl"
                )
            }
        }
    }

    private fun retryCurrentStreamWithoutAudio() {
        val url = lastRequestedPlaybackUrl
        if (url.isBlank()) return
        if (!videoOnlyMinimalMode) return
        retriedWithoutAudio = true
        videoOnlyMinimalMode = true
        handler.removeCallbacks(memoryLogRunnable)
        handler.removeCallbacks(startupSlowStreamRunnable)
        handler.removeCallbacks(playbackFreezeWatchdogRunnable)
        videoOnlyMinimalTriedSoftwareDecoder = false
        startVideoOnlyMinimalDebug(url, preferSoftwareDecoder = false)
        logMemoryStats("retry_without_audio_start")
        firstFrameRendered = false
    }

    private fun disableAudioTrack() {
        audioTrackForcedDisabled = true
        trackSelector?.setParameters(
            trackSelector?.buildUponParameters()?.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                ?: return
        )
    }

    private fun isAudioTrackTypeDisabledCompat(): Boolean {
        val params = trackSelector?.parameters ?: return false
        return try {
            val method = params.javaClass.getMethod("isTrackTypeDisabled", Int::class.javaPrimitiveType)
            (method.invoke(params, C.TRACK_TYPE_AUDIO) as? Boolean) == true
        } catch (_: Throwable) {
            try {
                val method = params.javaClass.getMethod("getTrackTypeDisabled", Int::class.javaPrimitiveType)
                (method.invoke(params, C.TRACK_TYPE_AUDIO) as? Boolean) == true
            } catch (_: Throwable) {
                audioTrackForcedDisabled
            }
        }
    }

    private fun logAudioTrackState(stage: String) {
        val audioDisabled = isAudioTrackTypeDisabledCompat()
        val selectedAudioTracks = countSelectedTracksByType("audio/")
        val selectedVideoTracks = countSelectedTracksByType("video/")
        val trackGroupCount = mediaPlayer?.currentTracks?.groups?.size ?: -1
        logDebug(
            "PLAYER_STATE",
            "$stage audioTrackForcedDisabled=$audioTrackForcedDisabled audioDisabled=$audioDisabled selected audio tracks count=$selectedAudioTracks selected video tracks count=$selectedVideoTracks trackGroups=$trackGroupCount videoOnlyMinimalMode=$videoOnlyMinimalMode url=$lastRequestedPlaybackUrl"
        )
    }

    private fun logPathState(stage: String) {
        val player = mediaPlayer
        val allowNonIdr = shouldAllowNonIdrForStream(lastRequestedPlaybackUrl)
        val liveOffset = player?.currentLiveOffset ?: C.TIME_UNSET
        val selectedAudioTracks = countSelectedTracksByType("audio/")
        val selectedVideoTracks = countSelectedTracksByType("video/")
        logDebug(
            "PLAYER_PATH",
            "$stage posMs=${player?.currentPosition ?: -1} bufferedPosMs=${player?.bufferedPosition ?: -1} liveOffsetMs=$liveOffset playWhenReady=${player?.playWhenReady} isPlaying=${player?.isPlaying} state=${player?.playbackState} selectedAudio=$selectedAudioTracks selectedVideo=$selectedVideoTracks audioTrackForcedDisabled=$audioTrackForcedDisabled audioDisabled=${isAudioTrackTypeDisabledCompat()} allowNonIdr=$allowNonIdr url=$lastRequestedPlaybackUrl"
        )
    }

    private fun countSelectedTracksByType(prefix: String): Int {
        return mediaPlayer?.currentTracks?.groups
            ?.filter { it.isSelected }
            ?.sumOf { group ->
                var count = 0
                for (i in 0 until group.length) {
                    if (!group.isTrackSelected(i)) continue
                    val format = group.getTrackFormat(i)
                    if (format.sampleMimeType?.startsWith(prefix) == true) count++
                }
                count
            } ?: -1
    }

    private fun enableAudioTrack() {
        audioTrackForcedDisabled = false
        trackSelector?.setParameters(
            trackSelector?.buildUponParameters()?.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                ?: return
        )
    }

    private fun startVideoOnlyMinimalDebug(url: String, preferSoftwareDecoder: Boolean) {
        stopPlayback()
        videoOnlyMinimalFirstFrameRendered = false
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
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
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(codecSelector)
        val selector = DefaultTrackSelector(this)
        trackSelector = selector
        selector.setParameters(
            selector.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
        )
        audioTrackForcedDisabled = true
        val player = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(selector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build()
        mediaPlayer = player
        findViewById<PlayerView>(R.id.videoLayout).player = player
        val minimalListener = object : androidx.media3.common.Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                if (!videoOnlyMinimalMode) return
                logDebug("VIDEO_ONLY_MINIMAL", "trackGroups=${tracks.groups.size}")
                logAudioTrackState("video_only_minimal_tracks_changed")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!videoOnlyMinimalMode) return
                logDebug(
                    "VIDEO_ONLY_MINIMAL",
                    "state=$playbackState currentPosition=${player.currentPosition} bufferedPosition=${player.bufferedPosition} totalBufferedDuration=${player.totalBufferedDuration} playWhenReady=${player.playWhenReady}"
                )
            }

            override fun onRenderedFirstFrame() {
                videoOnlyMinimalFirstFrameRendered = true
                if (!videoOnlyMinimalMode) return
                logDebug("VIDEO_ONLY_MINIMAL", "onRenderedFirstFrame")
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (!videoOnlyMinimalMode) return
                logDebug("VIDEO_ONLY_MINIMAL", "videoSize=${videoSize.width}x${videoSize.height}")
            }
        }
        player.addListener(minimalListener)
        playerEventListener = minimalListener
        val minimalAnalytics = object : AnalyticsListener {
            override fun onLoadStarted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
                logDebug("VIDEO_ONLY_MINIMAL", "onLoadStarted type=${mediaLoadData.dataType} trackType=${mediaLoadData.trackType} uri=${loadEventInfo.dataSpec.uri}")
            }
            override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
                logDebug("VIDEO_ONLY_MINIMAL", "onLoadCompleted type=${mediaLoadData.dataType} trackType=${mediaLoadData.trackType} bytes=${loadEventInfo.bytesLoaded} ms=${loadEventInfo.loadDurationMs}")
            }
            override fun onLoadCanceled(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
                logDebug("VIDEO_ONLY_MINIMAL", "onLoadCanceled type=${mediaLoadData.dataType} trackType=${mediaLoadData.trackType}")
            }
            override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData, error: IOException, wasCanceled: Boolean) {
                logDebug("VIDEO_ONLY_MINIMAL", "onLoadError type=${mediaLoadData.dataType} trackType=${mediaLoadData.trackType} error=${error.message}")
            }
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                logDebug("VIDEO_ONLY_MINIMAL", "videoDecoderInitialized decoder=$decoderName initMs=$initializationDurationMs")
            }
            override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                logDebug("VIDEO_ONLY_MINIMAL", "videoInputFormat codec=${format.codecs} sampleMime=${format.sampleMimeType} size=${format.width}x${format.height}")
            }
            override fun onVideoEnabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
                decoderCounters.ensureUpdated()
                logDebug("VIDEO_ONLY_MINIMAL", "renderedOutputBufferCount=${decoderCounters.renderedOutputBufferCount}")
            }
        }
        player.addAnalyticsListener(minimalAnalytics)
        playerAnalyticsListener = minimalAnalytics
        logAudioTrackState("video_only_minimal_before_prepare")
        logDebug("VIDEO_ONLY_MINIMAL", "startup preferSoftwareDecoder=$preferSoftwareDecoder")
        player.playWhenReady = true
        val allowNonIdr = prefs.getBoolean(PREF_HLS_ALLOW_NON_IDR, false)
        player.setMediaSource(buildPlaybackMediaSource(url, allowNonIdr))
        player.seekToDefaultPosition()
        player.prepare()
        player.seekToDefaultPosition()
        player.play()
        logAudioTrackState("video_only_minimal_after_prepare")
        videoOnlyMinimalNoFrameRunnable?.let { handler.removeCallbacks(it) }
        val noFrameRunnable = Runnable {
            if (!videoOnlyMinimalFirstFrameRendered && videoOnlyMinimalMode) {
                logAudioTrackState("video_only_minimal_no_first_frame_25s")
                logDebug(
                    "VIDEO_ONLY_MINIMAL",
                    "no first frame after 25s; audioDisabled path is active, network loads continue, likely decode/render incompatibility for this AVC TS stream on device"
                )
                if (!videoOnlyMinimalTriedSoftwareDecoder) {
                    videoOnlyMinimalTriedSoftwareDecoder = true
                    logDebug("VIDEO_ONLY_MINIMAL", "retry once with software-preferred decoder for compatibility check")
                    startVideoOnlyMinimalDebug(url, preferSoftwareDecoder = true)
                }
            }
        }
        videoOnlyMinimalNoFrameRunnable = noFrameRunnable
        handler.postDelayed(noFrameRunnable, 25_000L)
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
        val allowNonIdr = prefs.getBoolean(PREF_HLS_ALLOW_NON_IDR, false)
        mediaPlayer?.setMediaSource(buildPlaybackMediaSource(url, allowNonIdr))
        mediaPlayer?.prepare()
        mediaPlayer?.playWhenReady = true
        logMemoryStats("restart_stream_start")
        handler.removeCallbacks(memoryLogRunnable)
        handler.post(memoryLogRunnable)
        firstFrameRendered = false
        handler.removeCallbacks(startupSlowStreamRunnable)
        handler.postDelayed(startupSlowStreamRunnable, 45_000L)
        resetPlaybackProgressBaseline(extendGrace = true)
        armPlaybackFreezeWatchdog(4000L, withStartGrace = true)
        if (homePanel.visibility != View.VISIBLE) {
            showPlayerLoadingUi()
        }
    }

    private fun isHomeOrSettingsForeground(): Boolean =
        homePanel.visibility == View.VISIBLE ||
            isSettingsModalVisible ||
            (::homeSettingsScreen.isInitialized && homeSettingsScreen.visibility == View.VISIBLE)

    private fun showUI(preferFocus: View? = null) {
        // В меню/настройках хром плеера никогда не должен «проскакивать».
        if (isHomeOrSettingsForeground()) {
            hidePlayerChromeFully()
            handler.removeCallbacks(hideUiRunnable)
            return
        }
        // Пока открыт EPG — панель управления и верхний бар не показываем.
        if (::epgPanel.isInitialized && epgPanel.visibility == View.VISIBLE) {
            hidePlayerChromeFully()
            handler.removeCallbacks(hideUiRunnable)
            return
        }
        // До первого кадра — только назад и время.
        if (!firstFrameRendered) {
            showPlayerLoadingUi()
            return
        }
        hidePlayerLoadingUi()
        topInfoPanel.visibility = View.VISIBLE
        topGradientOverlay.visibility = View.VISIBLE
        controlsPanel.visibility = View.VISIBLE
        findViewById<View>(R.id.liveStatusBadge)?.visibility = View.VISIBLE
        findViewById<View>(R.id.playerTopChannelInfo)?.visibility = View.VISIBLE
        findViewById<View>(R.id.playerTopTimePlate)?.visibility = View.VISIBLE
        findViewById<View>(R.id.btnBackToMenu).apply {
            visibility = View.VISIBLE
            isEnabled = true
            isClickable = true
            isFocusable = false
        }
        bindRealPlayerExitButtonListener()
        sbTimeline.isEnabled = true
        handler.removeCallbacks(hideUiRunnable)
        handler.postDelayed(hideUiRunnable, 5000)
        updatePlayPauseButton()
        ensurePlayerControlsInteractive()
        layoutPlayerSubtitlesOverlay()
        val controlsPanelButtonIds = intArrayOf(
            R.id.btnPlayPause, R.id.btnLiveReload, R.id.btnBackLeft,
            R.id.btnBackRight, R.id.btnLock, R.id.btnEpgPlayer, R.id.btnAspectRatio,
            R.id.btnCcSubtitles, R.id.btnAudioTrack, R.id.btnHdQuality
        )
        val current = currentFocus
        val keepCurrent = current != null && controlsPanelButtonIds.any { findViewById<View>(it) === current }
        val focusTarget = when {
            preferFocus != null -> preferFocus
            keepCurrent -> current
            else -> findViewById(R.id.btnPlayPause)
        }
        focusTarget?.post { focusTarget.requestFocus() }
    }

    private fun hideUI() {
        dismissPlayerTrackMenu()
        if (isHomeOrSettingsForeground()) {
            hidePlayerChromeFully()
            sbTimeline.isEnabled = false
            hideSystemUI()
            return
        }
        if (::epgPanel.isInitialized && epgPanel.visibility == View.VISIBLE) {
            // Пока пользователь читает программу передач, автоскрытие интерфейса не должно её задевать.
            return
        }
        if (::channelListPanel.isInitialized && channelListPanel.visibility == View.VISIBLE) {
            return
        }
        // Во время загрузки канала оставляем назад + время.
        if (!firstFrameRendered) {
            showPlayerLoadingUi()
            return
        }
        topInfoPanel.visibility = View.GONE
        topGradientOverlay.visibility = View.GONE
        controlsPanel.visibility = View.GONE
        sbTimeline.isEnabled = false
        layoutPlayerSubtitlesOverlay()
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
        seekStatusHoldUntilMs = 0L
        restoreChannelHeaderAfterNumberInput()
    }

    private fun updateChannelNumberInputDisplay() {
        if (inputNumber.isEmpty()) {
            restoreChannelHeaderAfterNumberInput()
            return
        }
        val idx = inputNumber.toIntOrNull()?.minus(1) ?: -1
        val channelName = channels.getOrNull(idx)?.name
        tvChannelName.text = if (channelName != null) {
            "Переключаем на канал: $inputNumber ($channelName)"
        } else {
            "Переключаем на канал: $inputNumber"
        }
        tvEpg.visibility = View.GONE
    }

    private fun restoreChannelHeaderAfterNumberInput() {
        tvEpg.visibility = View.VISIBLE
        val ch = channels.getOrNull(currentChannelIndex)
        if (ch != null) {
            tvChannelName.text = "${currentChannelIndex + 1}. ${ch.name}"
        }
        updateEpgDisplay()
    }

    private fun isWatchingChannel(): Boolean =
        homePanel.visibility != View.VISIBLE &&
            homeSettingsScreen.visibility != View.VISIBLE &&
            (!::playerSettingsOverlay.isInitialized || playerSettingsOverlay.visibility != View.VISIBLE) &&
            (!::epgPanel.isInitialized || epgPanel.visibility != View.VISIBLE) &&
            (!::channelListPanel.isInitialized || channelListPanel.visibility != View.VISIBLE)

    private fun isFocusInPlayerControlsRow(): Boolean {
        val focused = currentFocus ?: return false
        val controlsPanelButtonIds = intArrayOf(
            R.id.btnPlayPause, R.id.btnLiveReload, R.id.btnBackLeft,
            R.id.btnBackRight, R.id.btnLock, R.id.btnEpgPlayer, R.id.btnAspectRatio,
            R.id.btnCcSubtitles, R.id.btnAudioTrack, R.id.btnHdQuality
        )
        return controlsPanelButtonIds.any { findViewById<View>(it) === focused }
    }

    private fun handleWatchingHotkeys(keyCode: Int): Boolean {
        if (!isWatchingChannel()) return false
        when (keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                inputNumber += (keyCode - KeyEvent.KEYCODE_0).toString()
                updateChannelNumberInputDisplay()
                seekStatusHoldUntilMs = System.currentTimeMillis() + 2000L
                handler.removeCallbacks(channelSwitchRunnable)
                showUI()
                handler.postDelayed(channelSwitchRunnable, 1500)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (channels.isNotEmpty()) {
                    currentChannelIndex = (currentChannelIndex + 1) % channels.size
                    playChannel(forcePlay = true)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (channels.isNotEmpty()) {
                    currentChannelIndex =
                        (currentChannelIndex - 1 + channels.size) % channels.size
                    playChannel(forcePlay = true)
                }
                return true
            }
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event?.action == KeyEvent.ACTION_DOWN && timerWarningPanel.visibility == View.VISIBLE &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
        ) {
            cancelSleepTimer()
            showAppToast("Таймер остановлен")
            return true
        }

        // Самый первый экран приложения (ещё нет ни одного плейлиста) — тут нет списка, который можно
        // было бы обойти обычным фокусом, поэтому свой мини-переключатель между шестерёнкой и питанием.
        // Важно: проверяем homePanel — у дочерних View visibility может остаться VISIBLE при parent=GONE.
        if (homePanel.visibility == View.VISIBLE && tvHomeStartTitle.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val icons = listOf(ivHomeProfile, ivHomeSettings, ivHomePower)
                        .filter { it.visibility == View.VISIBLE }
                    if (icons.isEmpty()) return true
                    val delta = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                    homeActionIndex = (homeActionIndex + delta + icons.size) % icons.size
                    icons.forEachIndexed { index, icon ->
                        val selected = index == homeActionIndex
                        icon.alpha = if (selected) 1f else 0.5f
                        icon.scaleX = if (selected) 1.25f else 1f
                        icon.scaleY = if (selected) 1.25f else 1f
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    val icons = listOf(ivHomeProfile, ivHomeSettings, ivHomePower)
                        .filter { it.visibility == View.VISIBLE }
                    icons.getOrNull(homeActionIndex.coerceIn(0, (icons.size - 1).coerceAtLeast(0)))
                        ?.performClick()
                    return true
                }
            }
        }

        // Плиточные экраны (плейлисты/категории/список каналов на главном экране, но не самый первый
        // пустой экран) — своя явная связка: вверх с верхнего ряда уводит на шестерёнку/питание,
        // влево-вправо переключает между ними, вниз возвращает обратно в сетку.
        if (homePanel.visibility == View.VISIBLE && tvHomeStartTitle.visibility != View.VISIBLE) {
            val focused = currentFocus
            val headerIcons = listOf(ivHomeProfile, ivHomeSettings, ivHomePower)
                .filter { it.visibility == View.VISIBLE }
            val iconsHaveFocus = focused != null && focused in headerIcons
            if (iconsHaveFocus) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val idx = headerIcons.indexOf(focused)
                        val next = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                            headerIcons[(idx + 1) % headerIcons.size]
                        } else {
                            headerIcons[(idx - 1 + headerIcons.size) % headerIcons.size]
                        }
                        next.requestFocus()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        headerIcons.forEach { it.isFocusable = false }
                        if (homePlaylistTilesPanel.visibility == View.VISIBLE) rvHomeTiles.requestFocus()
                        else if (gvHomeChannelList.visibility == View.VISIBLE) gvHomeChannelList.requestFocus()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        focused?.performClick()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> return true
                }
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP &&
                (homePlaylistTilesPanel.visibility == View.VISIBLE || gvHomeChannelList.visibility == View.VISIBLE)
            ) {
                val grid = if (homePlaylistTilesPanel.visibility == View.VISIBLE) rvHomeTiles else gvHomeChannelList
                val focusedLoc = IntArray(2)
                val gridLoc = IntArray(2)
                focused?.getLocationOnScreen(focusedLoc)
                grid.getLocationOnScreen(gridLoc)
                val isTopRow = focused == null || focusedLoc[1] <= gridLoc[1] + dpToPx(8)
                if (isTopRow) {
                    headerIcons.forEach { it.isFocusable = true }
                    (if (ivHomeSettings.visibility == View.VISIBLE) ivHomeSettings else headerIcons.firstOrNull())
                        ?.requestFocus()
                    return true
                }
                // иначе — не наш случай, пусть обычная навигация внутри сетки решает сама
            }
        }

        // Панель управления плеером открыта (после ОК), и фокус реально уже на одной из её
        // кнопок — даём стрелкам переключаться между кнопками панели через обычный фокус,
        // вместо переключения канала/списка каналов/EPG.
        // Панель EPG открыта — влево/вправо явно листают даты, независимо от того, где
        // сейчас реальный фокус (раньше это работало только случайно через поиск фокуса Android).
        if (::epgPanel.isInitialized && epgPanel.visibility == View.VISIBLE) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    shiftEpgDate(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    shiftEpgDate(1)
                    return true
                }
            }
        }

        if (controlsPanel.visibility == View.VISIBLE) {
            if (handleWatchingHotkeys(keyCode)) return true
            // Active player chrome: L/R move between control buttons (seek / lock / EPG / …).
            // Side panels open only when chrome is hidden.
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (!isFocusInPlayerControlsRow()) {
                        findViewById<View>(R.id.btnPlayPause)?.requestFocus()
                    }
                    handler.removeCallbacks(hideUiRunnable)
                    handler.postDelayed(hideUiRunnable, 5000)
                    return super.onKeyDown(keyCode, event)
                }
            }
        } else if (handleWatchingHotkeys(keyCode)) {
            return true
        }

        // MENU на пульте во время просмотра — только хром плеера, не настройки приложения.
        if ((keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) &&
            homePanel.visibility != View.VISIBLE &&
            homeSettingsScreen.visibility != View.VISIBLE &&
            (!::playerSettingsOverlay.isInitialized || playerSettingsOverlay.visibility != View.VISIBLE)
        ) {
            if (::epgPanel.isInitialized && epgPanel.visibility == View.VISIBLE) {
                hideEpgPanel()
                return true
            }
            if (::channelListPanel.isInitialized && channelListPanel.visibility == View.VISIBLE) {
                hideChannelListPanel()
                return true
            }
            if (controlsPanel.visibility == View.VISIBLE) hideUI() else showUI()
            return true
        }

        // Любой экран поверх плеера — плейлисты/категории/список каналов на главном экране,
        // любые настройки, список каналов и EPG внутри плеера. Отдаём нажатия стандартной системе
        // фокуса Android, чтобы реально подсвечивались и перелистывались пункты — раньше это всё
        // "съедалось" переключением канала, даже когда экран был совсем другой.
        val overlayOpen = homePanel.visibility == View.VISIBLE ||
            homeSettingsScreen.visibility == View.VISIBLE ||
            (::playerSettingsOverlay.isInitialized && playerSettingsOverlay.visibility == View.VISIBLE) ||
            (::channelListPanel.isInitialized && channelListPanel.visibility == View.VISIBLE) ||
            (::epgPanel.isInitialized && epgPanel.visibility == View.VISIBLE)

        if (overlayOpen) {
            return super.onKeyDown(keyCode, event)
        }

        // Дальше — мы реально смотрим канал, ничего поверх не открыто: горячие клавиши плеера.
        when {
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                showUI()
                return true
            }

            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                toggleEpgPanel()
                return true
            }

            keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> {
                showChannelListPanel()
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (::scaleGestureDetector.isInitialized) {
            scaleGestureDetector.onTouchEvent(e)
        }
        return mDetector.onTouchEvent(e) || super.onTouchEvent(e)
    }

    override fun onStart() {
        super.onStart()
        startEpgTicker()
        handler.post(timelineTickerRunnable)
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val currentVersion =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
        preferGpuDecoding = prefs.getBoolean(PREF_USE_GPU_DECODER, true)
        softwareDecoderMode = !preferGpuDecoding

        val savedVersion = prefs.getLong(PREF_APP_VERSION_CODE, -1L)
        val versionChanged = savedVersion != currentVersion
        if (versionChanged) {
            prefs.edit().putLong(PREF_APP_VERSION_CODE, currentVersion).apply()
        }
        if (mediaPlayer == null || forceFreshPlayerSession) {
            if (mediaPlayer != null && forceFreshPlayerSession) {
                stopPlayback()
            }
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
        val hasIncompleteEpgProgress = epgSourceStatus.values.any {
            it.contains("Загрузка файла") || it.contains("Распаковка файла") || it.contains("Чтение файла")
        }
        if (versionChanged || hasIncompleteEpgProgress) ensureEpgLoadedLazy()
        if (mediaPlayer != null && isPlaybackPaused) {
            mediaPlayer?.play()
            handler.postDelayed(startupSlowStreamRunnable, 45_000L)
            isPlaybackPaused = false
            liveTimelineAnchorMs = 0L
            updatePlayPauseButton()
        }
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(epgTickerRunnable)
        handler.removeCallbacks(timelineTickerRunnable)
        handler.removeCallbacks(memoryLogRunnable)
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
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val p =
            if (isArchivePlayback) currentArchiveProgram else channels.getOrNull(currentChannelIndex)
                ?.let { ch ->
                    getProgramsForDisplay(ch).find { System.currentTimeMillis() in it.start until it.stop }
                }
        if (p == null) {
            tvCurrentTime.visibility = View.INVISIBLE
            tvProgramEndTime.visibility = View.INVISIBLE
            if (!timelineUserSeeking) {
                sbTimeline.progress = 0
            }
            return
        }
        tvCurrentTime.visibility = View.VISIBLE
        tvProgramEndTime.visibility = View.VISIBLE
        tvProgramEndTime.text = fmt.format(Date(p.stop))
        val currentMs =
            if (isArchivePlayback) {
                archiveStreamStartMs + (mediaPlayer?.currentPosition ?: 0L)
            } else {
                getLiveTimelinePositionMs()
            }
        tvCurrentTime.text = fmt.format(Date(currentMs.coerceIn(p.start, p.stop)))
        if (!timelineUserSeeking) {
            val progress = (((currentMs - p.start).toDouble() / (p.stop - p.start).coerceAtLeast(1L)
                .toDouble()) * 1000.0).toInt().coerceIn(0, 1000)
            val clamped = clampTimelineProgress(progress)
            sbTimeline.progress = clamped
            applyTimelineProgressUi(clamped)
        }
    }

    private fun getLiveTimelinePositionMs(): Long {
        if (isArchivePlayback) return System.currentTimeMillis()
        if (isPlaybackPaused && liveTimelineAnchorMs > 0L) return liveTimelineAnchorMs
        return System.currentTimeMillis()
    }

    private fun maxTimelineProgressForLiveSeek(): Int? {
        if (isArchivePlayback) return null
        val ch = channels.getOrNull(currentChannelIndex) ?: return null
        val cur =
            getProgramsForDisplay(ch).find { getLiveTimelinePositionMs() in it.start until it.stop }
                ?: return null
        val duration = (cur.stop - cur.start).coerceAtLeast(1L)
        return (((getLiveTimelinePositionMs() - cur.start).toDouble() / duration) * 1000.0)
            .toInt().coerceIn(0, 1000)
    }

    private fun clampTimelineProgress(progress: Int): Int {
        val maxLive = maxTimelineProgressForLiveSeek()
        return if (maxLive != null) progress.coerceIn(0, maxLive) else progress.coerceIn(0, 1000)
    }

    private fun applyTimelineProgressUi(progress: Int) {
        applyTimelineLiveWidth(progress)
        applyTimelineThumbPosition(progress)
    }

    private fun applyTimelineThumbPosition(progress: Int) {
        val trackWidth = timelineTrack.width
        if (trackWidth <= 0) {
            timelineTrack.post { applyTimelineThumbPosition(progress) }
            return
        }
        val thumbSize = viewTimelineThumb.width.takeIf { it > 0 }
            ?: resources.getDimensionPixelSize(R.dimen.player_timeline_thumb_size)
        val thumbCenter = trackWidth * (progress / 1000f)
        val offset = (thumbCenter - thumbSize / 2f).coerceIn(0f, (trackWidth - thumbSize).toFloat())
        viewTimelineThumb.translationX = offset
    }

    private fun applyTimelineLiveWidth(progress: Int) {
        val trackWidth = timelineTrack.width
        if (trackWidth <= 0) {
            timelineTrack.post { applyTimelineLiveWidth(progress) }
            return
        }
        val lp = viewTimelineLive.layoutParams ?: return
        val thumbCenter = (trackWidth * (progress / 1000f)).toInt()
        val targetWidth = thumbCenter.coerceIn(0, trackWidth)
        if (lp.width != targetWidth) {
            lp.width = targetWidth
            viewTimelineLive.layoutParams = lp
        }
    }

    private fun formatMinutesRu(minutes: Int): String {
        val safeMinutes = minutes.coerceAtLeast(0)
        val rem100 = safeMinutes % 100
        val rem10 = safeMinutes % 10
        val word = when {
            rem100 in 11..14 -> "минут"
            rem10 == 1 -> "минуту"
            rem10 in 2..4 -> "минуты"
            else -> "минут"
        }
        return "$safeMinutes $word"
    }

    private fun timelineProgressFromTouchX(view: View, x: Float): Int {
        val width = view.width.takeIf { it > 0 } ?: return 0
        return ((x / width) * 1000f).toInt().coerceIn(0, 1000)
    }

    /** Границы и точка отсчёта "сейчас" для текущей передачи — общие для превью и применения перемотки. */
    private fun currentTimelineSeekableProgram(): Triple<Long, Long, Long>? {
        return if (isArchivePlayback) {
            val p = currentArchiveProgram ?: return null
            Triple(p.start, p.stop, p.start + (mediaPlayer?.currentPosition ?: 0L))
        } else {
            val ch = channels.getOrNull(currentChannelIndex) ?: return null
            val cur =
                getProgramsForDisplay(ch).find { getLiveTimelinePositionMs() in it.start until it.stop }
                    ?: return null
            Triple(cur.start, cur.stop, getLiveTimelinePositionMs())
        }
    }

    private fun previewTimelineSeekText(progress: Int) {
        val clamped = clampTimelineProgress(progress)
        val (programStart, programStop, currentAbsoluteMs) = currentTimelineSeekableProgram() ?: return
        val target = programStart + ((programStop - programStart) * (clamped / 1000f)).toLong()
        val deltaMin = kotlin.math.abs((target - currentAbsoluteMs) / 60_000L).toInt()
        tvEpg.text = "Перематываем передачу на ${formatMinutesRu(deltaMin)}"
    }

    private fun seekToAbsoluteTime(targetMs: Long) {
        val ch = channels.getOrNull(currentChannelIndex) ?: return
        val now = System.currentTimeMillis()
        if (targetMs >= now - 2_000L) {
            switchToLivePlayback()
            return
        }
        val programs = getProgramsWithArchiveFallback(ch)
        val targetProgram = programs.find { targetMs in it.start until it.stop }
            ?: programs.filter { it.start <= targetMs }.maxByOrNull { it.start }
            ?: return
        if (!isArchiveAvailable(ch, targetProgram)) {
            showAppToast("Архив передачи недоступен для этого канала", 2500L)
            return
        }
        if (isArchivePlayback && currentArchiveProgram?.start == targetProgram.start) {
            seekArchiveTo(targetMs)
            return
        }
        playArchiveProgram(ch, targetProgram)
        handler.postDelayed({ seekArchiveTo(targetMs) }, 450L)
    }

    private fun applyRelativeSeekSeconds(deltaSec: Int) {
        if (deltaSec == 0) return
        val deltaMin = kotlin.math.abs(deltaSec) / 60
        tvEpg.text = "Перематываем передачу на ${formatMinutesRu(deltaMin)}"
        seekStatusHoldUntilMs = System.currentTimeMillis() + 2200L
        handler.removeCallbacks(restoreEpgRunnable)
        handler.postDelayed(restoreEpgRunnable, 2200L)

        if (!isArchivePlayback && deltaSec > 0) {
            showAppToast("Перемотка вперёд недоступна в прямом эфире")
            return
        }

        val currentAbs = if (isArchivePlayback) {
            val p = currentArchiveProgram ?: return
            p.start + (mediaPlayer?.currentPosition ?: 0L)
        } else {
            getLiveTimelinePositionMs()
        }
        val targetAbs = currentAbs + deltaSec * 1000L
        seekToAbsoluteTime(targetAbs)
        showSeekSpinner()
        showUI(preferFocus = if (deltaSec < 0) btnBackLeft else btnBackRight)
    }

    private fun applyTimelineSeekFromProgress(progress: Int) {
        val clamped = clampTimelineProgress(progress)
        if (isArchivePlayback) {
            val p = currentArchiveProgram ?: return
            val liveProgress = liveProgressInProgram(p)
            if (clamped >= liveProgress - timelineSeekDeadband) {
                switchToLivePlayback()
                return
            }
            val duration = (p.stop - p.start).coerceAtLeast(1L)
            val currentOffset = mediaPlayer?.currentPosition ?: 0L
            val currentProgress =
                ((currentOffset.toDouble() / duration.toDouble()) * 1000.0).toInt().coerceIn(0, 1000)
            if (kotlin.math.abs(clamped - currentProgress) < timelineSeekDeadband) {
                updateTimelineUi()
                return
            }
            val target = p.start + ((p.stop - p.start) * (clamped / 1000f)).toLong()
            seekArchiveTo(target)
        } else {
            val maxLive = maxTimelineProgressForLiveSeek()
            if (maxLive != null && clamped >= maxLive - timelineSeekDeadband) {
                updateTimelineUi()
                return
            }
            val ch = channels.getOrNull(currentChannelIndex)
            val cur =
                ch?.let { getProgramsForDisplay(it).find { pr -> getLiveTimelinePositionMs() in pr.start until pr.stop } }
            if (ch != null && cur != null && isArchiveAvailable(ch, cur)) {
                val target =
                    cur.start + ((cur.stop - cur.start) * (clamped / 1000f)).toLong()
                val currentMs = getLiveTimelinePositionMs()
                val deltaMs = kotlin.math.abs(target - currentMs)
                if (deltaMs < 15_000L) {
                    updateTimelineUi()
                    return
                }
                playArchiveProgram(ch, cur)
                handler.postDelayed({ seekArchiveTo(target) }, 450L)
            } else if (ch != null && cur != null) {
                showAppToast("Архив передачи недоступен для этого канала", 2500L)
                updateTimelineUi()
            }
        }
    }

    private fun seekArchiveTo(targetProgramTimeMs: Long) {
        val p = currentArchiveProgram ?: return
        val previous = mediaPlayer?.currentPosition ?: 0L
        val offset = (targetProgramTimeMs - p.start).coerceAtLeast(0L)
        showSeekSpinner()
        mediaPlayer?.seekTo(offset)
        val deltaMin = kotlin.math.abs(((offset - previous) / 60_000L).toInt())
        tvEpg.text = "Перематываем передачу на ${formatMinutesRu(deltaMin)}"
        seekStatusHoldUntilMs = System.currentTimeMillis() + 2200L
        handler.removeCallbacks(restoreEpgRunnable)
        handler.postDelayed(restoreEpgRunnable, 2200L)
        updateTimelineUi()
        // If seek is already ready (no buffer stall), still hide after a short beat.
        handler.postDelayed({ hideSeekSpinnerIfReady() }, 300L)
    }

    private fun startEpgTicker() {
        handler.removeCallbacks(epgTickerRunnable)
        handler.post(epgTickerRunnable)
    }

    private fun stopPlayback() {
        handler.removeCallbacks(memoryLogRunnable)
        handler.removeCallbacks(startupSlowStreamRunnable)
        handler.removeCallbacks(playbackFreezeWatchdogRunnable)
        videoOnlyMinimalNoFrameRunnable?.let { handler.removeCallbacks(it) }
        videoOnlyMinimalNoFrameRunnable = null

        val playerView = findViewById<PlayerView>(R.id.videoLayout)
        val attachedBefore = playerView.player
        val attachedBeforeId = attachedBefore?.let { System.identityHashCode(it) }
        val oldPlayerId = mediaPlayer?.let { System.identityHashCode(it) }
        logDebug("PLAYER_LIFECYCLE", "stopPlayback start oldPlayerId=$oldPlayerId attachedPlayerId=$attachedBeforeId listenerSet=${playerEventListener != null} analyticsSet=${playerAnalyticsListener != null} looper=${Looper.myLooper()} thread=${Thread.currentThread().name}")

        mediaPlayer?.let { player ->
            playerEventListener?.let { player.removeListener(it) }
            playerAnalyticsListener?.let { player.removeAnalyticsListener(it) }
            player.clearVideoSurface()
            player.clearMediaItems()
            player.stop()
            player.release()
            logDebug("PLAYER_LIFECYCLE", "release completed playerId=${System.identityHashCode(player)} renderersReleased=true codecReleased=unknown")
            lastReleasedPlayerId = System.identityHashCode(player)
        }

        playerView.player = null
        logDebug("PLAYER_LIFECYCLE", "playerView detached beforeId=$attachedBeforeId afterIsNull=${playerView.player == null}")

        mediaPlayer = null
        startupPlaybackUrlLock = null
        playerEventListener = null
        playerAnalyticsListener = null
        trackSelector = null
        forceFreshPlayerSession = true
    }

    private fun resetPlaybackSessionStateOnExit() {
        startupPlaybackUrlLock = null
        lastRequestedPlaybackUrl = ""
        firstFrameRendered = false
        retriedWithoutAudio = false
        videoOnlyMinimalMode = false
        retriedWithAlternateDecoder = false
        runtimeRecoveryAttempted = false
        behindLiveWindowRecoveryInProgress = false
        startupRecoveryAttempts = 0
        bufferingSinceMs = 0L
        lastPlaybackPositionMs = -1L
        lastProgressWallClockMs = 0L
        videoOnlyMinimalFirstFrameRendered = false
        videoOnlyMinimalTriedSoftwareDecoder = false
        audioTrackForcedDisabled = false
        isPlaybackPaused = false
        liveTimelineAnchorMs = 0L
        isArchivePlayback = false
        currentArchiveProgram = null
        archiveStreamStartMs = 0L
        resetPlaybackRecoveryState()
        enableAudioTrack()
        applyUnlimitedVideoConstraints()
    }

    private fun exitPlayerToPlaylist() {
        dismissPlayerTrackMenu()
        clearPlayerSubtitles()
        logDebug("NAV", "EXIT_PLAYER_TO_PLAYLIST_ENTERED")
        if (::epgPanel.isInitialized) {
            epgPanel.visibility = View.GONE
            lvEpgPrograms.adapter = null
        }
        if (::channelListPanel.isInitialized) {
            channelListPanel.visibility = View.GONE
            gvChannelListPanel.adapter = null
        }
        stopPlayback()
        resetPlaybackSessionStateOnExit()
        hasStartedPlaybackFromChannelClick = false
        logDebug("NAV", "EXIT_PLAYER_LOCAL_HOME_RESET")
        resetSettingsOverlayState()
        showHomeOnly()
        val category = lastChannelListCategory
        if (homeReturnTarget == HomeReturnTarget.CHANNEL_LIST && category != null &&
            cachedCategoryGroups.containsKey(category)
        ) {
            returnToCategoryTilesOnHome()
            showHomeChannelList(category, cachedCategoryGroups[category].orEmpty())
        }
        homeReturnTarget = HomeReturnTarget.PLAYLISTS
    }

    private fun bindRealPlayerExitButtonListener() {
        findViewById<ImageView>(R.id.btnBackToMenu).setOnClickListener {
            logDebug("NAV", "PLAYER_EXIT_BUTTON_CLICKED_REAL_LISTENER")
            if (::epgPanel.isInitialized && epgPanel.visibility == View.VISIBLE) {
                hideEpgPanel()
                return@setOnClickListener
            }
            if (::channelListPanel.isInitialized && channelListPanel.visibility == View.VISIBLE) {
                hideChannelListPanel()
                return@setOnClickListener
            }
            exitPlayerToPlaylist()
        }
    }

    private fun resetSettingsOverlayState() {
        isSettingsModalVisible = false
        settingsOpenedFromPlayer = false
        playerSettingsOverlay.visibility = View.GONE
        homeSettingsScreen.visibility = View.GONE
        findViewById<View>(R.id.settingsMainPanel).visibility = View.GONE
        findViewById<View>(R.id.userProfileHeaderCard).visibility = View.GONE
        findViewById<View>(R.id.playlistSettingsPanel).visibility = View.GONE
        findViewById<View>(R.id.epgSettingsPanel).visibility = View.GONE
        findViewById<View>(R.id.userSettingsPanel).visibility = View.GONE
    }

    private fun showHomeOnly() {
        logDebug("NAV", "SHOW_HOME_ONLY_START")
        resetSettingsOverlayState()
        val btnBackToMenu = findViewById<View>(R.id.btnBackToMenu)
        btnBackToMenu.visibility = View.GONE
        btnBackToMenu.isClickable = false
        btnBackToMenu.isFocusable = false
        btnBackToMenu.isEnabled = false
        btnBackToMenu.setOnClickListener(null)
        disableHomeCategoryBack("showHomeOnly_pre")
        tvReloadingStatus.visibility = View.GONE
        tvReloadingStatus.isClickable = false
        tvReloadingStatus.isFocusable = false
        tvReloadingStatus.isEnabled = false
        listBackgroundOverlay.visibility = View.GONE
        listBackgroundOverlay.isClickable = false
        listBackgroundOverlay.isFocusable = false
        listBackgroundOverlay.isEnabled = false
        timerWarningPanel.visibility = View.GONE
        timerWarningPanel.isClickable = false
        timerWarningPanel.isFocusable = false
        timerWarningPanel.isEnabled = false
        homePanel.setBackgroundResource(R.drawable.bg_home_screen)
        topInfoPanel.visibility = View.GONE
        topInfoPanel.isClickable = false
        topInfoPanel.isFocusable = false
        topInfoPanel.isEnabled = false
        topGradientOverlay.visibility = View.GONE
        topGradientOverlay.isClickable = false
        topGradientOverlay.isFocusable = false
        topGradientOverlay.isEnabled = false
        controlsPanel.visibility = View.GONE
        controlsPanel.isClickable = false
        controlsPanel.isFocusable = false
        controlsPanel.isEnabled = false
        setPlayerVideoVisible(false)
        tvHomeAppTitle.visibility = View.VISIBLE
        tvHomeSystemTime.visibility = View.VISIBLE
        ivHomeProfile.visibility = View.VISIBLE
        ivHomeSettings.visibility = View.VISIBLE
        ivHomePower.visibility = View.VISIBLE
        showPlaylistPageOnHome(source = "exit_player")
        homePanel.alpha = 1f
        homePanel.translationX = 0f
        homePanel.translationY = 0f
        homePlaylistTilesPanel.alpha = 1f
        homePlaylistTilesPanel.translationX = 0f
        homePlaylistTilesPanel.translationY = 0f
        (homePlaylistTilesPanel.layoutParams as? ConstraintLayout.LayoutParams)?.let { lp ->
            lp.width = 0
            lp.height = 0
            lp.startToStart = ConstraintSet.PARENT_ID
            lp.endToEnd = ConstraintSet.PARENT_ID
            lp.topToBottom = R.id.tvHomeAppTitle
            lp.bottomToBottom = ConstraintSet.PARENT_ID
            homePlaylistTilesPanel.layoutParams = lp
        }
        disableHomeCategoryBack("showHomeOnly_post_showPlaylistPageOnHome")
        val recycler = findViewById<RecyclerView>(R.id.rvHomeTiles)
        (recycler.layoutParams as? ViewGroup.LayoutParams)?.let { lp ->
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            recycler.layoutParams = lp
        }
        recycler.visibility = View.VISIBLE
        recycler.isEnabled = true
        recycler.isClickable = true
        recycler.isFocusable = true
        recycler.alpha = 1f
        recycler.translationX = 0f
        recycler.translationY = 0f
        recycler.requestFocus()
        var tilesCount = recycler.adapter?.itemCount ?: 0
        if (tilesCount <= 0) {
            logDebug("NAV", "CLICK_BLOCKED reason=home_tiles_empty_rebind")
            showPlaylistPageOnHome(source = "exit_player")
            tilesCount = recycler.adapter?.itemCount ?: 0
        }
        recycler.adapter?.notifyDataSetChanged()
        recycler.requestLayout()
        homePlaylistTilesPanel.requestLayout()
        homePanel.requestLayout()
        recycler.post {
            recycler.requestFocus()
            val firstTile = recycler.getChildAt(0)
            if (firstTile != null) {
                firstTile.requestFocus()
                logDebug(
                    "NAV",
                    "HOME_FIRST_TILE_STATE shown=${firstTile.isShown} enabled=${firstTile.isEnabled} clickable=${firstTile.isClickable} focusable=${firstTile.isFocusable} hasFocus=${firstTile.hasFocus()} hasOnClick=${firstTile.hasOnClickListeners()}"
                )
            }
            val focusedChild = recycler.focusedChild?.javaClass?.simpleName ?: "null"
            val rootFocus = window.decorView.findFocus()?.javaClass?.simpleName ?: "null"
            val categoryOverlay = findViewById<View>(R.id.tvHomeCategoryBack)
            val categoryOverlayViewId = runCatching { resources.getResourceEntryName(categoryOverlay.id) }.getOrDefault("no_id")
            logDebug(
                "NAV",
                "HOME_INPUT_STATE recyclerShown=${recycler.isShown} recyclerVisibility=${recycler.visibility} recyclerEnabled=${recycler.isEnabled} recyclerClickable=${recycler.isClickable} recyclerFocusable=${recycler.isFocusable} recyclerHasFocus=${recycler.hasFocus()} recyclerFocusedChild=$focusedChild adapterCount=${recycler.adapter?.itemCount ?: 0} rootFindFocus=$rootFocus currentScreen=HOME_PLAYLISTS settingsOpenedFromPlayer=$settingsOpenedFromPlayer loadingOverlayVisible=${tvReloadingStatus.visibility == View.VISIBLE} loadingOverlayClickable=${tvReloadingStatus.isClickable} playerOverlayVisible=${topInfoPanel.visibility == View.VISIBLE || controlsPanel.visibility == View.VISIBLE || topGradientOverlay.visibility == View.VISIBLE} playerOverlayClickable=${topInfoPanel.isClickable || controlsPanel.isClickable || topGradientOverlay.isClickable} categoryOverlayViewId=$categoryOverlayViewId categoryOverlayClass=${categoryOverlay.javaClass.simpleName} categoryOverlayVisibility=${categoryOverlay.visibility} categoryOverlayVisible=${categoryOverlay.visibility == View.VISIBLE} categoryOverlayClickable=${categoryOverlay.isClickable} categoryOverlayEnabled=${categoryOverlay.isEnabled} categoryOverlayFocusable=${categoryOverlay.isFocusable} categoryOverlayAlpha=${categoryOverlay.alpha} categoryOverlayElevation=${categoryOverlay.elevation} channelOverlayVisible=${listBackgroundOverlay.visibility == View.VISIBLE} channelOverlayClickable=${listBackgroundOverlay.isClickable} settingsClickable=${homeSettingsScreen.isClickable || findViewById<View>(R.id.playlistSettingsPanel).isClickable || findViewById<View>(R.id.epgSettingsPanel).isClickable || findViewById<View>(R.id.userSettingsPanel).isClickable}"
            )
            logHomeLayoutState("HOME_LAYOUT_STATE_INITIAL")
            logRootChildrenState()
        }
        recycler.postDelayed({
            logHomeLayoutState("HOME_LAYOUT_STATE_POST_250MS")
            val childCount = recycler.childCount
            val firstTile = recycler.getChildAt(0)
            val firstRect = Rect()
            val firstVisible = firstTile?.getGlobalVisibleRect(firstRect) ?: false
            logDebug(
                "NAV",
                "FIRST_TILE_POST_LAYOUT_STATE childCount=$childCount firstExists=${firstTile != null} firstWidth=${firstTile?.width ?: -1} firstHeight=${firstTile?.height ?: -1} firstIsShown=${firstTile?.isShown ?: false} firstAlpha=${firstTile?.alpha ?: -1f} firstGlobalVisible=$firstVisible firstRect=$firstRect parentVisible=${(firstTile?.parent as? View)?.visibility ?: -1} parentAlpha=${(firstTile?.parent as? View)?.alpha ?: -1f}"
            )
            recycler.scrollToPosition(0)
            recycler.requestFocus()
        }, 250)
        val homeVisible = homePanel.visibility == View.VISIBLE
        val tilesVisible = homePlaylistTilesPanel.visibility == View.VISIBLE && recycler.visibility == View.VISIBLE
        val settingsVisible = homeSettingsScreen.visibility == View.VISIBLE ||
            findViewById<View>(R.id.playlistSettingsPanel).visibility == View.VISIBLE ||
            findViewById<View>(R.id.epgSettingsPanel).visibility == View.VISIBLE ||
            findViewById<View>(R.id.userSettingsPanel).visibility == View.VISIBLE
        val playerVisible = topInfoPanel.visibility == View.VISIBLE || controlsPanel.visibility == View.VISIBLE
        val backgroundVisible = homePanel.visibility == View.VISIBLE
        if (homeVisible && tvHomeCategoryBack.isClickable) {
            logDebug("NAV", "BUG tvHomeCategoryBack still clickable on HOME_PLAYLISTS")
            disableHomeCategoryBack("showHomeOnly_assert_fix")
        }
        logDebug("NAV", "SHOW_HOME_ONLY_DONE currentScreen=HOME_PLAYLISTS homeVisible=$homeVisible tilesVisible=$tilesVisible tilesCount=$tilesCount playerVisible=$playerVisible settingsVisible=$settingsVisible backgroundVisible=$backgroundVisible")
    }

    private fun logViewGeometry(tag: String, name: String, v: View?) {
        if (v == null) {
            logDebug(tag, "$name=null")
            return
        }
        val rect = Rect()
        val globalVisible = v.getGlobalVisibleRect(rect)
        val parentView = v.parent as? View
        val idName = runCatching { resources.getResourceEntryName(v.id) }.getOrDefault("no_id")
        logDebug(
            tag,
            "$name id=$idName class=${v.javaClass.simpleName} visibility=${v.visibility} shown=${v.isShown} alpha=${v.alpha} w=${v.width} h=${v.height} x=${v.x} y=${v.y} tx=${v.translationX} ty=${v.translationY} elevation=${v.elevation} z=${v.z} globalVisible=$globalVisible rect=$rect attached=${v.isAttachedToWindow} parentClass=${parentView?.javaClass?.simpleName} parentVisibility=${parentView?.visibility ?: -1} parentAlpha=${parentView?.alpha ?: -1f} parentW=${parentView?.width ?: -1} parentH=${parentView?.height ?: -1}"
        )
    }

    private fun logHomeLayoutState(tag: String) {
        val recycler = findViewById<RecyclerView>(R.id.rvHomeTiles)
        val root = findViewById<View>(android.R.id.content)
        logViewGeometry(tag, "homePanel", homePanel)
        logViewGeometry(tag, "homePlaylistTilesPanel", homePlaylistTilesPanel)
        logViewGeometry(tag, "rvHomeTiles", recycler)
        logViewGeometry(tag, "firstTile", recycler.getChildAt(0))
        logViewGeometry(tag, "rootContent", root)
        logViewGeometry(tag, "topGradientOverlay", topGradientOverlay)
        logViewGeometry(tag, "topInfoPanel", topInfoPanel)
        logViewGeometry(tag, "controlsPanel", controlsPanel)
        logViewGeometry(tag, "playerSettingsOverlay", playerSettingsOverlay)
        logViewGeometry(tag, "homeSettingsScreen", homeSettingsScreen)
        logViewGeometry(tag, "listBackgroundOverlay", listBackgroundOverlay)
        logViewGeometry(tag, "tvReloadingStatus", tvReloadingStatus)
    }

    private fun logRootChildrenState() {
        val root = findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as? ViewGroup ?: return
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            val idName = runCatching { resources.getResourceEntryName(child.id) }.getOrDefault("no_id")
            logDebug(
                "NAV",
                "ROOT_CHILDREN_STATE idx=$i id=$idName class=${child.javaClass.simpleName} visibility=${child.visibility} shown=${child.isShown} alpha=${child.alpha} clickable=${child.isClickable} enabled=${child.isEnabled} w=${child.width} h=${child.height} elevation=${child.elevation} z=${child.z}"
            )
        }
    }

    private fun enableHomeCategoryBack(onClick: () -> Unit) {
        // "Назад" больше не показываем нигде — эту функцию теперь выполняет клик по "O.Portal".
        homeCategoryBackHandler = onClick
        tvHomeCategoryBack.visibility = View.GONE
        tvHomeCategoryBack.isEnabled = false
        tvHomeCategoryBack.isFocusable = false
        tvHomeCategoryBack.isFocusableInTouchMode = false
        tvHomeCategoryBack.isClickable = false
        tvHomeCategoryBack.setOnClickListener { onClick() }
    }

    private fun disableHomeCategoryBack(source: String) {
        logDebug(
            "NAV",
            "DISABLE_HOME_CATEGORY_BACK_BEFORE source=$source visibility=${tvHomeCategoryBack.visibility} clickable=${tvHomeCategoryBack.isClickable} enabled=${tvHomeCategoryBack.isEnabled} focusable=${tvHomeCategoryBack.isFocusable} hasOnClickListeners=${tvHomeCategoryBack.hasOnClickListeners()}"
        )
        homeCategoryBackHandler = null
        tvHomeCategoryBack.setOnClickListener(null)
        tvHomeCategoryBack.setOnLongClickListener(null)
        tvHomeCategoryBack.onFocusChangeListener = null
        tvHomeCategoryBack.isClickable = false
        tvHomeCategoryBack.isLongClickable = false
        tvHomeCategoryBack.isFocusable = false
        tvHomeCategoryBack.isFocusableInTouchMode = false
        tvHomeCategoryBack.isEnabled = false
        tvHomeCategoryBack.visibility = View.GONE

        if (tvHomeCategoryBack.isClickable) {
            tvHomeCategoryBack.clearFocus()
            tvHomeCategoryBack.clearAnimation()
            tvHomeCategoryBack.cancelPendingInputEvents()
            tvHomeCategoryBack.setOnTouchListener { _, _ -> false }
            tvHomeCategoryBack.isClickable = false
            tvHomeCategoryBack.isLongClickable = false
        }
        logDebug(
            "NAV",
            "DISABLE_HOME_CATEGORY_BACK_AFTER source=$source visibility=${tvHomeCategoryBack.visibility} clickable=${tvHomeCategoryBack.isClickable} enabled=${tvHomeCategoryBack.isEnabled} focusable=${tvHomeCategoryBack.isFocusable} hasOnClickListeners=${tvHomeCategoryBack.hasOnClickListeners()}"
        )
    }

    private fun showLockedMessage() {
        tvEpg.text = "Управление свайпами заблокировано! Разблокируйте для переключения канала!"
        showUI()
        handler.removeCallbacks(restoreEpgRunnable)
        handler.postDelayed(restoreEpgRunnable, 2000)
    }

    private fun extractEpgSourcesFromPlaylist(content: String): List<String> {
        val tagName = when {
            content.contains("x-tvg-url=\"") -> "x-tvg-url=\""
            content.contains("url-tvg=\"") -> "url-tvg=\""
            else -> return emptyList()
        }
        return content.substringAfter(tagName, "")
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
        val days = prefs.getInt(PREF_EPG_REFRESH_INTERVAL_DAYS, 1).coerceIn(1, 7)
        return System.currentTimeMillis() - last >= days * 24L * 60L * 60L * 1000L
    }


    private fun shouldRefreshEpgNow(): Boolean {
        if (shouldDailyRefreshEpg()) return true
        return getEpgSourceFingerprint() != buildEpgSourceFingerprint(selectedEpgSources.toList())
    }

    private fun buildEpgSourceFingerprint(sources: List<String>): String =
        sources.map { it.trim() }.filter { it.isNotBlank() }.sorted().joinToString("|")

    private fun getEpgSourceFingerprint(): String =
        prefs.getString(PREF_EPG_SOURCES_FINGERPRINT, "") ?: ""

    private fun saveCurrentEpgSourceFingerprint() {
        prefs.edit().putString(
            PREF_EPG_SOURCES_FINGERPRINT,
            buildEpgSourceFingerprint(selectedEpgSources.toList())
        ).apply()
    }

    private fun trimEpgCacheToWeek() {
        val now = System.currentTimeMillis()
        val windowStart = now - EPG_KEEP_PAST_DAYS * 24L * 60L * 60L * 1000L
        val windowEnd = now + EPG_KEEP_FUTURE_DAYS * 24L * 60L * 60L * 1000L
        val channelsBefore: Int
        val programsBefore: Int
        synchronized(epgDataLock) {
            channelsBefore = epgData.size
            programsBefore = epgData.values.sumOf { it.size }
            epgData.entries.forEach { (_, programs) ->
                programs.removeAll { it.stop < windowStart || it.start > windowEnd }
                programs.sortBy { it.start }
            }
            epgData.entries.removeAll { it.value.isEmpty() }
        }
        val channelsAfter: Int
        val programsAfter: Int
        synchronized(epgDataLock) {
            channelsAfter = epgData.size
            programsAfter = epgData.values.sumOf { it.size }
        }
        logDebug(
            "EPG_DEBUG",
            "TRIM before: channels=$channelsBefore programs=$programsBefore -> " +
                "after: channels=$channelsAfter programs=$programsAfter"
        )
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

    private fun saveLastChannelPrefs(channel: Channel) {
        prefs.edit()
            .putInt(PREF_LAST_CHANNEL, currentChannelIndex)
            .putString(PREF_LAST_CHANNEL_URL, channel.url)
            .putString(PREF_LAST_CHANNEL_NAME, channel.name)
            .apply()
    }

    private fun resolveLastChannelIndex(): Int {
        val savedUrl = prefs.getString(PREF_LAST_CHANNEL_URL, null)
        val savedName = prefs.getString(PREF_LAST_CHANNEL_NAME, null)
        if (!savedUrl.isNullOrBlank()) {
            val byIdentity = channels.indexOfFirst {
                it.url == savedUrl && (savedName.isNullOrBlank() || it.name == savedName)
            }
            if (byIdentity >= 0) return byIdentity
        }
        val savedIndex = prefs.getInt(PREF_LAST_CHANNEL, -1)
        return if (savedIndex in channels.indices) savedIndex else -1
    }

    private fun dismissHomeForPlayback() {
        resetSettingsOverlayState()
        homePanel.visibility = View.GONE
        homeStartCenterBlock.visibility = View.GONE
        tvHomeStartTitle.visibility = View.GONE
        tvHomeStartSubtitle.visibility = View.GONE
        // Prevent stale start-screen key routing / accidental Power OK.
        homeActionIndex = 0
        listOf(ivHomeProfile, ivHomeSettings, ivHomePower).forEach { icon ->
            icon.isFocusable = false
            icon.alpha = 1f
            icon.scaleX = 1f
            icon.scaleY = 1f
        }
    }

    private fun restoreLastChannelAndPlay(): Boolean {
        val index = resolveLastChannelIndex()
        if (index < 0) return false
        currentChannelIndex = index
        logDebug("NAV", "startup_restore_last_channel index=$index name=${channels[index].name}")
        // Keep chrome hidden so D-pad L/R open channel list / EPG instead of focusing controls.
        suppressAutoPlayerUiOnce = true
        dismissHomeForPlayback()
        ensurePlayerControlsInteractive()
        playChannel(forcePlay = true, reason = PlayerOpenReason.CHANNEL_CLICK)
        return true
    }

    private fun ensureDefaultPlaylistProfile() {
        val profiles = getPlaylistProfiles().toMutableList()
        if (profiles.isEmpty()) {
            savePlaylistProfiles(emptyList())
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
                            value = o.optString("value"),
                            enabled = o.optBoolean("enabled", true)
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
                    put("enabled", it.enabled)
                }
            )
        }
        prefs.edit().putString(PREF_PLAYLISTS, arr.toString()).apply()
    }

    private fun resolveCurrentPlaylistUrl(): String {
        val selected = getSelectedPlaylistName()
        val profiles = getPlaylistProfiles()
        val profile = profiles.firstOrNull { it.name == selected && it.enabled }
            ?: profiles.firstOrNull { it.enabled }
        return when (profile?.type) {
            "token" -> {
                val token = profile.value.trim()
                if (token.isBlank()) "" else "$TOKEN_PREFIX$token$TOKEN_SUFFIX"
            }

            "url" -> profile.value.trim()
            else -> ""
        }
    }

    private fun getSelectedPlaylistName(): String =
        prefs.getString(PREF_SELECTED_PLAYLIST, "") ?: ""

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
                programs.sortedBy { it.start }.take(500).forEach { p ->
                    arr.put(JSONObject().apply {
                        put("title", p.title)
                        put("start", p.start)
                        put("stop", p.stop)
                        put("desc", p.desc)
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
                        stop = p.optLong("stop"),
                        desc = p.optString("desc")
                    )
                }
                synchronized(epgDataLock) { epgData[key] = list }
            }
        } catch (_: Exception) {
            cachedLogos.clear()
        }
        updateEpgLoadStatusUi()
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
    private data class ChannelGridItemViewHolder(
        val tvNumber: TextView,
        val tvName: TextView,
        val tvCurrentProgram: TextView,
        val ivLogo: ImageView,
        val archiveBadge: View
    )
    private fun redactSensitive(message: String): String {
        val token = (prefs.getString(PREF_USER_TOKEN, null) ?: "").trim()
        return if (token.length >= 6) message.replace(token, "***TOKEN***") else message
    }

    /**
     * Собирает всю цепочку причин исключения (t, t.cause, t.cause.cause...) в одну строку,
     * маскируя токен на каждом уровне — Android сам подставляет e.toString() в лог,
     * и текст ошибки сети иногда содержит упавший URL целиком, включая токен.
     */
    private fun redactThrowableChain(t: Throwable): String {
        val chain = generateSequence(t) { it.cause }.toList()
        return chain.joinToString(" -- caused by: ") { redactSensitive(it.toString()) }
    }

    private fun logDebug(tag: String, message: String, tr: Throwable? = null) {
        val safeMessage = redactSensitive(message)
        Log.i(tag, safeMessage, tr)
        runCatching<Unit> {
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val line = "$ts [$tag] $safeMessage\n"
            val out = openFileOutput("player_debug.log", Context.MODE_APPEND)
            out.use { stream -> stream.write(line.toByteArray()) }
        }
    }

    private fun exportDebugLogToDownloads() {
        val src = File(filesDir, "player_debug.log")
        if (!src.exists()) {
            showAppToast("Файл лога ещё не создан")
            return
        }
        val fileName = "player_debug_${System.currentTimeMillis()}.log"
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Не удалось создать файл в Загрузках")
                resolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                } ?: throw IllegalStateException("Не удалось открыть поток для записи")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                showAppToast("Лог сохранён в Загрузки: $fileName", 3500L)
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val dst = File(dir, fileName)
                src.copyTo(dst, overwrite = true)
                showAppToast("Лог сохранён: ${dst.absolutePath}", 3500L)
            }
        }.onFailure { error: Throwable ->
            showAppToast("Ошибка экспорта: ${error.message}", 3500L)
        }
    }

    private class CustomTypefaceSpan(private val typeface: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(textPaint: TextPaint) {
            textPaint.typeface = typeface
        }

        override fun updateMeasureState(textPaint: TextPaint) {
            textPaint.typeface = typeface
        }
    }
}
