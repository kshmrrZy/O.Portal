package com.example.o_portal_ott

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
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

class MainActivity : AppCompatActivity() {

    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var mDetector: GestureDetectorCompat

    private var channelListDialog: AlertDialog? = null

    // UI элементы
    private lateinit var controlsPanel: View
    private lateinit var topInfoPanel: View
    private lateinit var tvEpg: TextView
    private lateinit var tvChannelName: TextView
    private lateinit var tvSystemTime: TextView
    private lateinit var ivLogo: ImageView
    private lateinit var btnLock: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnLiveReload: LinearLayout
    private lateinit var tvReloadingStatus: TextView
    private lateinit var listBackgroundOverlay: View

    // Состояние
    private var isLocked = false
    private var currentChannelIndex = 0
    private val channels = mutableListOf<Channel>()
    private val epgData = mutableMapOf<String, MutableList<Program>>()
    private val handler = Handler(Looper.getMainLooper())
    private var inputNumber = ""
    private var currentPlaylistText: String = ""
    private var availableEpgSources: List<String> = emptyList()
    private var selectedEpgSources: MutableSet<String> = mutableSetOf()

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"

    private val prefs by lazy { getSharedPreferences("oportal_settings", Context.MODE_PRIVATE) }

    companion object {
        private const val PREF_PLAYLIST_URL = "playlist_url"
        private const val PREF_SELECTED_EPG = "selected_epg"
        private const val TOKEN_PREFIX = "https://o.avff.ru/my/"
        private const val TOKEN_SUFFIX = ".m3u"
    }

    // Задачи для Handler
    private val hideUiRunnable = Runnable { hideUI() }
    private val channelSwitchRunnable = Runnable { processChannelNumberInput() }
    private val restoreEpgRunnable = Runnable { updateEpgDisplay() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } catch (_: Exception) {
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()
        setContentView(R.layout.activity_main)

        initViews()
        setupVLC()
        setupInteractions()
        startClockUpdater()
        loadPlaylist()
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
        btnLiveReload = findViewById(R.id.btnLiveReload)
        tvReloadingStatus = findViewById(R.id.tvReloadingStatus)
        listBackgroundOverlay = findViewById(R.id.listBackgroundOverlay)
        tvEpg.isSelected = true
    }

    private fun setupInteractions() {
        // Обновить трансляцию
        btnLiveReload.setOnClickListener {
            tvReloadingStatus.visibility = View.VISIBLE
            playChannel()
            handler.postDelayed({ tvReloadingStatus.visibility = View.GONE }, 1500)
        }

        btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        // Кнопка блокировки
        btnLock.setOnClickListener {
            isLocked = !isLocked
            btnLock.setImageResource(if (isLocked) android.R.drawable.ic_lock_lock else android.R.drawable.ic_lock_power_off)
            tvEpg.text = if (isLocked) "Управление свайпами заблокировано." else "Управление свайпами разблокировано."
            handler.removeCallbacks(restoreEpgRunnable)
            handler.postDelayed(restoreEpgRunnable, 2000)
            showUI()
        }

        mDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (isLocked) {
                    showLockedMessage()
                    return false
                }

                if (e1 == null || channels.isEmpty()) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y

                if (abs(dy) > abs(dx)) {
                    // Свайп вверх => следующий, вниз => предыдущий
                    currentChannelIndex = (currentChannelIndex + if (dy < 0) 1 else -1 + channels.size) % channels.size
                    playChannel()
                    return true
                }

                if (dx > 120 && abs(dx) > abs(dy)) {
                    // Свайп вправо => список каналов
                    showChannelList()
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

    private fun showLockedMessage() {
        tvEpg.text = "Управление свайпами заблокировано! Разблокируйте!"
        showUI()
        handler.removeCallbacks(restoreEpgRunnable)
        handler.postDelayed(restoreEpgRunnable, 2000)
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
                    playChannel()
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
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        dialog.show()

        val dm = resources.displayMetrics
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout((dm.widthPixels * 0.67f).toInt(), (dm.heightPixels * 0.67f).toInt())
        }
    }

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val etPlaylistUrl = view.findViewById<EditText>(R.id.etPlaylistUrl)
        val etToken = view.findViewById<EditText>(R.id.etToken)
        val btnUseToken = view.findViewById<TextView>(R.id.btnApplyToken)
        val btnEpgSelect = view.findViewById<TextView>(R.id.btnEpgSelect)

        etPlaylistUrl.setText(getPlaylistUrl())

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", null)
            .create()

        btnUseToken.setOnClickListener {
            val token = etToken.text.toString().trim()
            if (token.isNotEmpty()) {
                etPlaylistUrl.setText(TOKEN_PREFIX + token + TOKEN_SUFFIX)
            } else {
                Toast.makeText(this, "Введите токен", Toast.LENGTH_SHORT).show()
            }
        }

        btnEpgSelect.setOnClickListener {
            val parsedSources = parseEpgSourcesFromPlaylist(currentPlaylistText)
            if (parsedSources.isNotEmpty()) {
                availableEpgSources = parsedSources
            }
            showEpgSelectionDialog()
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newUrl = etPlaylistUrl.text.toString().trim()
                if (newUrl.isBlank()) {
                    etPlaylistUrl.error = "Введите ссылку на плейлист"
                    return@setOnClickListener
                }

                savePlaylistUrl(newUrl)
                loadPlaylist(forceReload = true)
                dialog.dismiss()
            }
        }

        dialog.show()
        val dm = resources.displayMetrics
        dialog.window?.setLayout((dm.widthPixels * 0.67f).toInt(), (dm.heightPixels * 0.67f).toInt())
    }

    private fun showEpgSelectionDialog() {
        if (availableEpgSources.isEmpty()) {
            Toast.makeText(this, "В плейлисте не найден x-tvg-url", Toast.LENGTH_SHORT).show()
            return
        }

        val items = availableEpgSources.toTypedArray()
        val checked = BooleanArray(items.size) { idx ->
            selectedEpgSources.contains(items[idx])
        }

        AlertDialog.Builder(this)
            .setTitle("Выбор EPG")
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                if (isChecked) {
                    selectedEpgSources.add(items[which])
                } else {
                    selectedEpgSources.remove(items[which])
                }
            }
            .setPositiveButton("Применить") { _, _ ->
                saveSelectedEpgSources(selectedEpgSources)
                if (selectedEpgSources.isNotEmpty()) {
                    epgData.clear()
                    fetchEpgSources(selectedEpgSources.toList())
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun loadPlaylist(forceReload: Boolean = false) {
        thread {
            try {
                val playlistUrl = getPlaylistUrl()
                if (playlistUrl.isBlank()) {
                    handler.post {
                        tvEpg.text = "Откройте настройки и добавьте плейлист"
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

                    epgData.clear()
                    if (selectedEpgSources.isNotEmpty()) {
                        fetchEpgSources(selectedEpgSources.toList())
                    }

                    if (channels.isNotEmpty()) {
                        currentChannelIndex = currentChannelIndex.coerceIn(channels.indices)
                        playChannel()
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
                    tvEpg.text = "Ошибка загрузки плейлиста"
                    showUI()
                    if (forceReload) {
                        Toast.makeText(this, "Не удалось загрузить плейлист", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
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

    private fun fetchEpgSources(urls: List<String>) {
        thread {
            urls.forEach { sourceUrl ->
                val candidates = normalizeEpgUrls(sourceUrl)
                var parsed = false

                for (candidate in candidates) {
                    try {
                        parseEpgXml(getFinalInputStream(candidate))
                        parsed = true
                        break
                    } catch (_: Exception) {
                        // пробуем следующий вариант
                    }
                }

                if (!parsed) {
                    Log.w("EPG", "Не удалось обработать источник EPG: $sourceUrl")
                }
            }

            handler.post {
                updateEpgDisplay()
                refreshLogo()
            }
        }
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
                                it.tvgId.equals(tempId, true) ||
                                    it.tvgName.equals(tempId, true) ||
                                    it.name.equals(tempId, true)
                            }.forEach { it.logoFromEpg = src }
                        }

                        "programme" -> {
                            val chId = parser.getAttributeValue(null, "channel")?.lowercase()?.trim() ?: ""
                            val start = try {
                                sdf.parse(parser.getAttributeValue(null, "start"))?.time ?: 0L
                            } catch (_: Exception) {
                                0L
                            }
                            val stop = try {
                                sdf.parse(parser.getAttributeValue(null, "stop"))?.time ?: 0L
                            } catch (_: Exception) {
                                0L
                            }
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

    private fun playChannel() {
        val ch = channels.getOrNull(currentChannelIndex) ?: return
        mediaPlayer?.stop()
        val media = Media(libVlc, Uri.parse(ch.url))
        mediaPlayer?.media = media
        media.release()
        mediaPlayer?.play()
        tvChannelName.text = "${currentChannelIndex + 1}. ${ch.name}"
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
        handler.post {
            tvEpg.text = cur?.let { "${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.start))} - ${it.title}" }
                ?: "Загрузка программы..."
        }
    }

    private fun loadLogoWithGlide(url: String?, target: ImageView) {
        val glideUrl = if (url.isNullOrEmpty()) {
            null
        } else {
            GlideUrl(url, LazyHeaders.Builder().addHeader("User-Agent", userAgent).build())
        }
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
        libVlc = LibVLC(this, arrayListOf("--network-caching=3000"))
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
            playChannel()
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
                    playChannel()
                }
                return true
            }

            keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (channels.isNotEmpty()) {
                    currentChannelIndex = (currentChannelIndex - 1 + channels.size) % channels.size
                    playChannel()
                }
                return true
            }

            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                showChannelList()
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean = mDetector.onTouchEvent(e) || super.onTouchEvent(e)

    private fun getPlaylistUrl(): String = prefs.getString(PREF_PLAYLIST_URL, "")?.trim().orEmpty()

    private fun savePlaylistUrl(url: String) {
        prefs.edit().putString(PREF_PLAYLIST_URL, url).apply()
    }

    private fun getSelectedEpgSources(): MutableSet<String> =
        prefs.getStringSet(PREF_SELECTED_EPG, emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun saveSelectedEpgSources(sources: Set<String>) {
        prefs.edit().putStringSet(PREF_SELECTED_EPG, sources).apply()
    }

    private data class ChannelItemViewHolder(
        val tvName: TextView,
        val tvEpgItem: TextView,
        val ivLogoItem: ImageView,
        val btnWatch: TextView
    )
}
