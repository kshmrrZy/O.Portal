package com.example.o_portal_ott

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.util.Xml
import android.view.GestureDetector
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
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import org.json.JSONArray
import org.json.JSONObject
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
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
    var logoFromEpg: String? = null
)

data class Program(val title: String, val start: Long, val stop: Long)

data class PlaylistProfile(
    val name: String,
    val type: String, // token|url
    val value: String
)

class MainActivity : AppCompatActivity() {

    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var mDetector: GestureDetectorCompat

    private var channelListDialog: AlertDialog? = null
    private var scheduleDialog: AlertDialog? = null

    // UI элементы
    private lateinit var controlsPanel: View
    private lateinit var topInfoPanel: View
    private lateinit var tvEpg: TextView
    private lateinit var tvChannelName: TextView
    private lateinit var tvSystemTime: TextView
    private lateinit var ivLogo: ImageView
    private lateinit var btnLock: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnSleepTimer: ImageButton
    private lateinit var btnLiveReload: LinearLayout
    private lateinit var tvReloadingStatus: TextView
    private lateinit var listBackgroundOverlay: View
    private lateinit var timerWarningPanel: View
    private lateinit var btnStopTimer: TextView

    // Состояние
    private var isLocked = false
    private var isPlaybackPaused = false
    private var currentChannelIndex = 0
    private val channels = mutableListOf<Channel>()
    private val epgData = mutableMapOf<String, MutableList<Program>>()
    private val handler = Handler(Looper.getMainLooper())
    private var inputNumber = ""
    private var currentPlaylistText: String = ""
    private var availableEpgSources: List<String> = emptyList()
    private var selectedEpgSources: MutableSet<String> = mutableSetOf()
    private val epgSourceStatus = mutableMapOf<String, String>()

    private var timerEndAtMillis: Long = 0L
    private var lastBackPressAt = 0L

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
    private val prefs by lazy { getSharedPreferences("oportal_settings", Context.MODE_PRIVATE) }

    companion object {
        private const val PREF_PLAYLISTS = "playlist_profiles"
        private const val PREF_SELECTED_PLAYLIST = "selected_playlist"
        private const val PREF_SELECTED_EPG = "selected_epg"
        private const val PREF_LAST_CHANNEL = "last_channel"
        private const val PREF_EPG_CACHE = "epg_cache"
        private const val PREF_EPG_STATUS = "epg_status"
        private const val PREF_EPG_LAST_REFRESH = "epg_last_refresh"

        private const val TOKEN_PREFIX = "https://o.avff.ru/my/"
        private const val TOKEN_SUFFIX = ".m3u"
    }

    private val hideUiRunnable = Runnable { hideUI() }
    private val channelSwitchRunnable = Runnable { processChannelNumberInput() }
    private val restoreEpgRunnable = Runnable { updateEpgDisplay() }
    private val timerFinishRunnable = Runnable { closeAppCompletely() }
    private val timerWarnRunnable = Runnable { showTimerWarning() }

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
        setupVLC()
        setupInteractions()
        setupBackHandling()
        loadEpgCache()
        restoreLastChannel()
        startClockUpdater()
        loadPlaylist(showErrors = true)
    }

    private fun initViews() {
        controlsPanel = findViewById(R.id.controlsPanel)
        topInfoPanel = findViewById(R.id.topInfoPanel)
        tvEpg = findViewById(R.id.tvEpgInfo)
        tvChannelName = findViewById(R.id.tvChannelNameInfo)
        tvSystemTime = findViewById(R.id.tvSystemTime)
        ivLogo = findViewById(R.id.ivChannelLogo)
        btnLock = findViewById(R.id.btnLock)
        btnSettings = findViewById(R.id.btnSettings)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnSleepTimer = findViewById(R.id.btnSleepTimer)
        btnLiveReload = findViewById(R.id.btnLiveReload)
        tvReloadingStatus = findViewById(R.id.tvReloadingStatus)
        listBackgroundOverlay = findViewById(R.id.listBackgroundOverlay)
        timerWarningPanel = findViewById(R.id.timerWarningPanel)
        btnStopTimer = findViewById(R.id.btnStopTimer)
        tvEpg.isSelected = true
    }

    private fun setupInteractions() {
        btnLiveReload.setOnClickListener {
            tvReloadingStatus.visibility = View.VISIBLE
            playChannel(forcePlay = true)
            handler.postDelayed({ tvReloadingStatus.visibility = View.GONE }, 1200)
        }

        btnSettings.setOnClickListener { showSettingsDialog() }
        btnSleepTimer.setOnClickListener { showTimerDialog() }

        btnPlayPause.setOnClickListener {
            if (isPlaybackPaused) {
                mediaPlayer?.play()
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
        stopPlayback()
        finishAffinity()
        finishAndRemoveTask()
    }

    private fun showTimerDialog() {
        val options = arrayOf(10, 20, 30, 60, 90, 120, 240)
        val labels = options.map { "$it минут" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Выберите время таймера")
            .setItems(labels) { _, which -> startSleepTimer(options[which]) }
            .setNegativeButton("Отмена", null)
            .show()
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

                val pList = epgData[channel.tvgId?.lowercase()?.trim()]
                    ?: epgData[channel.tvgName?.lowercase()?.trim()]
                    ?: epgData[channel.name.lowercase().trim()]
                val cur = pList?.find { System.currentTimeMillis() in it.start until it.stop }
                holder.tvEpgItem.text = cur?.title ?: "Нет программы"

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
            setLayout((dm.widthPixels * 0.82f).toInt(), (dm.heightPixels * 0.82f).toInt())
        }
    }

    private fun showCurrentChannelSchedule() {
        val ch = channels.getOrNull(currentChannelIndex) ?: return
        val programs = (epgData[ch.tvgId?.lowercase()?.trim()]
            ?: epgData[ch.tvgName?.lowercase()?.trim()]
            ?: epgData[ch.name.lowercase().trim()])?.sortedBy { it.start } ?: emptyList()

        if (programs.isEmpty()) {
            Toast.makeText(this, "Для канала пока нет EPG", Toast.LENGTH_SHORT).show()
            return
        }

        val byDate = programs.groupBy {
            SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(it.start))
        }

        val view = layoutInflater.inflate(R.layout.dialog_channel_schedule, null)
        val tvHeader = view.findViewById<TextView>(R.id.tvScheduleHeader)
        val dateContainer = view.findViewById<LinearLayout>(R.id.dateContainer)
        val lvSchedule = view.findViewById<ListView>(R.id.lvSchedule)

        val currentProgram = programs.find { System.currentTimeMillis() in it.start until it.stop }
        tvHeader.text = "${ch.name}\nСейчас: ${currentProgram?.title ?: "Нет данных"}"

        val dateKeys = byDate.keys.sortedBy { key ->
            SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).parse(key)?.time ?: 0L
        }

        var selectedDate = dateKeys.first()

        fun renderSchedule(date: String) {
            val rows = byDate[date].orEmpty().map {
                "${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.start))}   ${it.title}"
            }
            lvSchedule.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
        }

        dateKeys.forEach { dateKey ->
            val chip = TextView(this).apply {
                text = dateKey
                setTextColor(Color.WHITE)
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

        renderSchedule(selectedDate)

        scheduleDialog?.dismiss()
        scheduleDialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(view)
            .create()

        scheduleDialog?.show()
        val dm = resources.displayMetrics
        scheduleDialog?.window?.setLayout((dm.widthPixels * 0.92f).toInt(), (dm.heightPixels * 0.9f).toInt())
    }

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val btnPlaylistSettings = view.findViewById<TextView>(R.id.btnPlaylistSettings)
        val btnEpgSelect = view.findViewById<TextView>(R.id.btnEpgSelect)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setNegativeButton("Закрыть", null)
            .create()

        btnPlaylistSettings.setOnClickListener {
            dialog.dismiss()
            showPlaylistSettingsDialog()
        }

        btnEpgSelect.setOnClickListener {
            dialog.dismiss()
            showEpgSelectionDialog()
        }

        dialog.show()
        val dm = resources.displayMetrics
        dialog.window?.setLayout((dm.widthPixels * 0.82f).toInt(), (dm.heightPixels * 0.82f).toInt())
    }

    private fun showPlaylistSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_playlist_settings, null)
        val spPlaylist = view.findViewById<Spinner>(R.id.spPlaylist)
        val etPlaylistName = view.findViewById<EditText>(R.id.etPlaylistName)
        val rgSourceType = view.findViewById<RadioGroup>(R.id.rgSourceType)
        val etSourceValue = view.findViewById<EditText>(R.id.etSourceValue)
        val btnAddOrUpdate = view.findViewById<TextView>(R.id.btnAddOrUpdatePlaylist)
        val btnDelete = view.findViewById<TextView>(R.id.btnDeletePlaylist)

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
            etSourceValue.setText(p.value)
            rgSourceType.check(if (p.type == "token") R.id.rbToken else R.id.rbUrl)
        }

        refreshSpinner()
        fillFields(selectedIndex)

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

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setNegativeButton("Закрыть", null)
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
            Toast.makeText(this, "Плейлист сохранён", Toast.LENGTH_SHORT).show()
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

        dialog.show()
        val dm = resources.displayMetrics
        dialog.window?.setLayout((dm.widthPixels * 0.82f).toInt(), (dm.heightPixels * 0.82f).toInt())
    }

    private fun showEpgSelectionDialog() {
        if (availableEpgSources.isEmpty()) {
            availableEpgSources = parseEpgSourcesFromPlaylist(currentPlaylistText)
        }
        if (availableEpgSources.isEmpty()) {
            Toast.makeText(this, "В плейлисте не найден x-tvg-url", Toast.LENGTH_SHORT).show()
            return
        }

        val view = layoutInflater.inflate(R.layout.dialog_epg_selection, null)
        val container = view.findViewById<LinearLayout>(R.id.epgContainer)
        val localSelection = selectedEpgSources.toMutableSet()

        val rows = mutableMapOf<String, TextView>()

        availableEpgSources.forEach { source ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(8, 8, 8, 8)
            }
            val cb = android.widget.CheckBox(this).apply {
                text = source
                setTextColor(Color.WHITE)
                isChecked = localSelection.contains(source)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) localSelection.add(source) else localSelection.remove(source)
                }
            }
            val tvStatus = TextView(this).apply {
                setTextColor(Color.parseColor("#B3FFFFFF"))
                textSize = 12f
                text = epgSourceStatus[source] ?: "Загрузка файла: 0%"
            }
            rows[source] = tvStatus
            row.addView(cb)
            row.addView(tvStatus)
            container.addView(row)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Применить", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                selectedEpgSources = localSelection
                saveSelectedEpgSources(selectedEpgSources)
                if (selectedEpgSources.isNotEmpty()) {
                    epgData.clear()
                    fetchEpgSources(selectedEpgSources.toList(), rows)
                }
                dialog.dismiss()
            }
        }

        dialog.show()
        val dm = resources.displayMetrics
        dialog.window?.setLayout((dm.widthPixels * 0.82f).toInt(), (dm.heightPixels * 0.82f).toInt())
    }

    private fun loadPlaylist(forceReload: Boolean = false, showErrors: Boolean = false) {
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
                val parsedEpgUrls = parseEpgSourcesFromPlaylist(content)

                handler.post {
                    channels.clear()
                    channels.addAll(parsedChannels)
                    availableEpgSources = parsedEpgUrls

                    val savedSelection = getSelectedEpgSources()
                    selectedEpgSources = if (savedSelection.isNotEmpty()) {
                        savedSelection.intersect(availableEpgSources.toSet()).toMutableSet()
                    } else {
                        availableEpgSources.toMutableSet()
                    }

                    if (shouldWeeklyRefreshEpg()) {
                        epgData.clear()
                        if (selectedEpgSources.isNotEmpty()) {
                            fetchEpgSources(selectedEpgSources.toList())
                        }
                    } else if (selectedEpgSources.isNotEmpty() && epgData.isEmpty()) {
                        fetchEpgSources(selectedEpgSources.toList())
                    }

                    if (channels.isNotEmpty()) {
                        currentChannelIndex = currentChannelIndex.coerceIn(channels.indices)
                        playChannel(forcePlay = true)
                    } else {
                        tvEpg.text = "Каналы не найдены в плейлисте"
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
        thread {
            urls.forEach { sourceUrl ->
                updateEpgStatus(sourceUrl, "Загрузка файла: 0%", statusViews[sourceUrl])
                val candidates = normalizeEpgUrls(sourceUrl)
                var parsed = false

                for (candidate in candidates) {
                    try {
                        updateEpgStatus(sourceUrl, "Распаковка файла: 50%", statusViews[sourceUrl])
                        parseEpgXml(getFinalInputStream(candidate))
                        updateEpgStatus(sourceUrl, "Чтение: 100%", statusViews[sourceUrl])
                        parsed = true
                        break
                    } catch (_: Exception) {
                    }
                }

                if (!parsed) {
                    updateEpgStatus(sourceUrl, "Ошибка загрузки", statusViews[sourceUrl])
                    Log.w("EPG", "Не удалось обработать источник EPG: $sourceUrl")
                }
            }

            saveEpgCache()
            handler.post {
                prefs.edit().putLong(PREF_EPG_LAST_REFRESH, System.currentTimeMillis()).apply()
                updateEpgDisplay()
                refreshLogo()
            }
        }
    }

    private fun updateEpgStatus(source: String, status: String, targetView: TextView?) {
        epgSourceStatus[source] = status
        saveEpgStatusCache()
        handler.post { targetView?.text = status }
    }

    private fun parseEpgSourcesFromPlaylist(content: String): List<String> {
        if (!content.contains("x-tvg-url=\"")) return emptyList()

        return content
            .substringAfter("x-tvg-url=\"", "")
            .substringBefore("\"")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun normalizeEpgUrls(url: String): List<String> {
        val clean = url.trim()
        if (clean.isBlank()) return emptyList()

        val normalized = mutableListOf(clean)
        if (!clean.endsWith(".xml.gz", true) && !clean.endsWith(".xml", true)) {
            normalized += "$clean.xml.gz"
            normalized += "$clean.xml"
            normalized += clean.trimEnd('/') + "/xmltv.xml.gz"
            normalized += clean.trimEnd('/') + "/epg.xml.gz"
        }

        return normalized.distinct()
    }

    private fun getFinalInputStream(u: String): InputStream {
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", userAgent)
        val bis = BufferedInputStream(conn.inputStream)
        bis.mark(1024)
        val h = ByteArray(2)
        bis.read(h)
        bis.reset()
        return if (h[0].toInt() and 0xFF == 0x1F && h[1].toInt() and 0xFF == 0x8B) GZIPInputStream(bis) else bis
    }

    private fun parseEpgXml(inputStream: InputStream) {
        inputStream.use { stream ->
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
                                epgData.getOrPut(chId) { mutableListOf() }.add(Program(title, start, stop))
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        }
    }

    private fun playChannel(forcePlay: Boolean = false) {
        val ch = channels.getOrNull(currentChannelIndex) ?: return
        mediaPlayer?.stop()

        val media = Media(libVlc, Uri.parse(ch.url)).apply {
            setHWDecoderEnabled(true, true)
            addOption(":network-caching=1200")
            addOption(":clock-jitter=0")
            addOption(":clock-synchro=0")
            addOption(":no-video-title-show")
        }

        mediaPlayer?.media = media
        media.release()
        if (forcePlay || isPlaybackPaused) {
            mediaPlayer?.play()
            isPlaybackPaused = false
            btnPlayPause.setImageResource(R.drawable.ic_pause)
        } else {
            mediaPlayer?.play()
        }

        tvChannelName.text = "${currentChannelIndex + 1}. ${ch.name}"
        prefs.edit().putInt(PREF_LAST_CHANNEL, currentChannelIndex).apply()
        refreshLogo()
        updateEpgDisplay()
        showUI()
    }

    private fun updateEpgDisplay() {
        val ch = channels.getOrNull(currentChannelIndex) ?: return
        val now = System.currentTimeMillis()
        val programs = epgData[ch.tvgId?.lowercase()?.trim()]
            ?: epgData[ch.tvgName?.lowercase()?.trim()]
            ?: epgData[ch.name.lowercase().trim()]

        val cur = programs?.find { now in it.start until it.stop }
        tvEpg.text = cur?.let {
            "${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.start))} - ${it.title}"
        } ?: "Загрузка программы..."
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
                tvSystemTime.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun setupVLC() {
        libVlc = LibVLC(this, arrayListOf("--network-caching=1200", "--avcodec-hw=any", "--audio-time-stretch"))
        mediaPlayer = MediaPlayer(libVlc)
        mediaPlayer?.attachViews(findViewById(R.id.videoLayout), null, false, false)
    }

    private fun showUI() {
        topInfoPanel.visibility = View.VISIBLE
        controlsPanel.visibility = View.VISIBLE
        handler.removeCallbacks(hideUiRunnable)
        handler.postDelayed(hideUiRunnable, 5000)
    }

    private fun hideUI() {
        topInfoPanel.visibility = View.GONE
        controlsPanel.visibility = View.GONE
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
                showChannelList()
                return true
            }

            keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> {
                showCurrentChannelSchedule()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = mDetector.onTouchEvent(e) || super.onTouchEvent(e)

    override fun onStart() {
        super.onStart()
        if (libVlc == null || mediaPlayer == null) {
            setupVLC()
            if (channels.isNotEmpty()) {
                playChannel(forcePlay = true)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        stopPlayback()
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }

    private fun stopPlayback() {
        mediaPlayer?.stop()
        mediaPlayer?.detachViews()
        mediaPlayer?.release()
        mediaPlayer = null
        libVlc?.release()
        libVlc = null
    }

    private fun showLockedMessage() {
        tvEpg.text = "Управление свайпами заблокировано! Разблокируйте!"
        showUI()
        handler.removeCallbacks(restoreEpgRunnable)
        handler.postDelayed(restoreEpgRunnable, 2000)
    }

    private fun shouldWeeklyRefreshEpg(): Boolean {
        val last = prefs.getLong(PREF_EPG_LAST_REFRESH, 0L)
        if (last == 0L) return true
        val next = nextTuesdayAtThree(last)
        return System.currentTimeMillis() >= next
    }

    private fun nextTuesdayAtThree(fromMillis: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = fromMillis }
        cal.set(Calendar.DAY_OF_WEEK, Calendar.TUESDAY)
        cal.set(Calendar.HOUR_OF_DAY, 3)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= fromMillis) cal.add(Calendar.WEEK_OF_YEAR, 1)
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
        prefs.edit().putString(PREF_EPG_CACHE, cache.toString()).apply()
        saveEpgStatusCache()
    }

    private fun loadEpgCache() {
        loadEpgStatusCache()
        val raw = prefs.getString(PREF_EPG_CACHE, "{}") ?: "{}"
        try {
            val obj = JSONObject(raw)
            epgData.clear()
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
                epgData[key] = list
            }
        } catch (_: Exception) {
        }
    }

    private data class ChannelItemViewHolder(
        val tvName: TextView,
        val tvEpgItem: TextView,
        val ivLogoItem: ImageView,
        val btnWatch: TextView
    )
}
