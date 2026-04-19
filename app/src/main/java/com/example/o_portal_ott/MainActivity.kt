package com.example.o_portal_ott

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
import android.widget.GridView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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

// Модели данных
data class Channel(val name: String, val url: String, val tvgId: String?, val tvgName: String?, val logoFromPlaylist: String?, var logoFromEpg: String? = null)
data class Program(val title: String, val start: Long, val stop: Long)

// Держатель вьюх для оптимизации списка и устранения "ряби"
class ChannelViewHolder(v: View) {
    val tvName: TextView = v.findViewById(R.id.itemName)
    val tvEpgItem: TextView = v.findViewById(R.id.itemEpg)
    val ivLogoItem: ImageView = v.findViewById(R.id.itemLogo)
    val btnWatch: View = v.findViewById(R.id.btnWatch)
}

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
    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"

    // Задачи для Handler
    private val hideUiRunnable = Runnable { hideUI() }
    private val channelSwitchRunnable = Runnable { processChannelNumberInput() }
    private val restoreEpgRunnable = Runnable { updateEpgDisplay() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE } catch (e: Exception) {}
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

        // Кнопка Блокировки
        btnLock.setOnClickListener {
            isLocked = !isLocked
            btnLock.setImageResource(if (isLocked) android.R.drawable.ic_lock_lock else android.R.drawable.ic_lock_power_off)
            tvEpg.text = if (isLocked) "Управление свайпами заблокировано." else "Управление свайпами разблокировано."
            handler.removeCallbacks(restoreEpgRunnable)
            handler.postDelayed(restoreEpgRunnable, 2000)
            showUI()
        }

        // Жесты
        mDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (isLocked) {
                    tvEpg.text = "Управление свайпами заблокировано! Разблокируйте!"
                    showUI(); handler.removeCallbacks(restoreEpgRunnable); handler.postDelayed(restoreEpgRunnable, 2000)
                    return false
                }
                if (e1 != null && Math.abs(vy) > Math.abs(vx)) {
                    currentChannelIndex = (currentChannelIndex + (if (e1.y > e2.y) 1 else -1) + channels.size) % channels.size
                    playChannel()
                    return true
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isLocked) {
                    tvEpg.text = "Управление свайпами заблокировано! Разблокируйте!"
                    showUI(); handler.removeCallbacks(restoreEpgRunnable); handler.postDelayed(restoreEpgRunnable, 2000)
                    return false
                }
                showChannelList()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (controlsPanel.visibility == View.VISIBLE) hideUI() else showUI()
                return true
            }
        })
    }

    private fun showChannelList() {
        if (channelListDialog?.isShowing == true) return
        channelListDialog?.dismiss()

        val view = layoutInflater.inflate(R.layout.dialog_channel_list, null)
        val grid = view.findViewById<GridView>(R.id.gvChannels)

        // Затемняем ВЕСЬ экран под списком (фон активити)
        listBackgroundOverlay.apply {
            setBackgroundColor(Color.parseColor("#99000000")) // 60% затемнение всего экрана
            visibility = View.VISIBLE
        }

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
            .setView(view)
            .create()

        channelListDialog = dialog

        dialog.setOnDismissListener {
            listBackgroundOverlay.visibility = View.GONE
            channelListDialog = null
        }

        grid.adapter = object : ArrayAdapter<Channel>(this, 0, channels) {
            override fun getView(pos: Int, conv: View?, parent: ViewGroup): View {
                // Всегда новая вьюха для чистоты
                val v = layoutInflater.inflate(R.layout.item_channel, parent, false)
                val c = channels.getOrNull(pos) ?: return v

                val tvName = v.findViewById<TextView>(R.id.itemName)
                val tvEpgItem = v.findViewById<TextView>(R.id.itemEpg)
                val ivLogoItem = v.findViewById<ImageView>(R.id.itemLogo)
                val btnWatch = v.findViewById<TextView>(R.id.btnWatch) // Ищем как TextView

                tvName.text = "${pos + 1}. ${c.name}"

                val pList = epgData[c.tvgId?.lowercase()?.trim()] ?: epgData[c.name.lowercase().trim()]
                val cur = pList?.find { System.currentTimeMillis() in it.start until it.stop }
                tvEpgItem.text = cur?.title ?: "Нет программы"

                loadLogoWithGlide(c.logoFromEpg ?: c.logoFromPlaylist, ivLogoItem)

                // Обработка клика
                val startChannel = View.OnClickListener {
                    currentChannelIndex = pos
                    playChannel()
                    channelListDialog?.dismiss()
                }

                v.setOnClickListener(startChannel)
                btnWatch?.setOnClickListener(startChannel)

                return v
            }
        }

        grid.apply {
            setSelector(R.drawable.selector_channel_item)
            setLayerType(View.LAYER_TYPE_HARDWARE, null) // Оставляем HW для плавности
        }

        dialog.show()

        dialog.window?.apply {
            // Делаем окно диалога абсолютно прозрачным, так как фон уже есть у GridView
            setBackgroundDrawableResource(android.R.color.transparent)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

            // Растягиваем на весь экран
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)

            // Магия для устранения наслоений
            setFormat(android.graphics.PixelFormat.TRANSLUCENT)
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
        val programs = epgData[ch.tvgId?.lowercase()?.trim()] ?: epgData[ch.name.lowercase().trim()]
        val cur = programs?.find { now in it.start until it.stop }
        handler.post {
            tvEpg.text = cur?.let { "${SimpleDateFormat("HH:mm").format(Date(it.start))} - ${it.title}" }
                ?: "Загрузка программы..."
        }
    }

    private fun loadPlaylist() {
        thread {
            try {
                val content = URL("https://ott.avff.ru/my/syrn1k.m3u").readText()
                val parsed = M3uParser.parse(content)
                val epgUrls = if (content.contains("x-tvg-url=\"")) {
                    content.substringAfter("x-tvg-url=\"").substringBefore("\"").split(",").map { it.trim() }
                } else emptyList()
                handler.post {
                    channels.clear(); channels.addAll(parsed)
                    if (epgUrls.isNotEmpty()) fetchEpgSources(epgUrls)
                    if (channels.isNotEmpty()) playChannel()
                }
            } catch (e: Exception) { Log.e("M3U", "Error") }
        }
    }

    // --- Вспомогательные методы (EPG, Таймеры, VLC) ---
    private fun fetchEpgSources(urls: List<String>) {
        thread {
            urls.forEach { url -> try { parseEpgXml(getFinalInputStream(url)) } catch (e: Exception) {} }
            handler.post { updateEpgDisplay(); refreshLogo() }
        }
    }

    private fun getFinalInputStream(u: String): InputStream {
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", USER_AGENT)
        val bis = BufferedInputStream(conn.inputStream)
        bis.mark(1024); val h = ByteArray(2); bis.read(h); bis.reset()
        return if (h[0].toInt() and 0xFF == 0x1F && h[1].toInt() and 0xFF == 0x8B) GZIPInputStream(bis) else bis
    }

    private fun parseEpgXml(inputStream: InputStream) {
        val parser = Xml.newPullParser()
        parser.setInput(inputStream, "UTF-8")
        var eventType = parser.eventType
        var tempId = ""
        val sdf = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> tempId = parser.getAttributeValue(null, "id") ?: ""
                    "icon" -> {
                        val src = parser.getAttributeValue(null, "src")
                        channels.filter { it.tvgId == tempId || it.name == tempId }.forEach { it.logoFromEpg = src }
                    }
                    "programme" -> {
                        val chId = parser.getAttributeValue(null, "channel")?.lowercase()?.trim() ?: ""
                        val start = try { sdf.parse(parser.getAttributeValue(null, "start")).time } catch(e: Exception) { 0L }
                        val stop = try { sdf.parse(parser.getAttributeValue(null, "stop")).time } catch(e: Exception) { 0L }
                        var title = ""
                        while (!(parser.next() == XmlPullParser.END_TAG && parser.name == "programme")) {
                            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "title") title = parser.nextText()
                        }
                        if (chId.isNotEmpty()) epgData.getOrPut(chId) { mutableListOf() }.add(Program(title, start, stop))
                    }
                }
            }
            eventType = parser.next()
        }
    }

    private fun loadLogoWithGlide(url: String?, target: ImageView) {
        val glideUrl = if (url.isNullOrEmpty()) null else GlideUrl(url, LazyHeaders.Builder().addHeader("User-Agent", USER_AGENT).build())
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
        libVlc = LibVLC(this, ArrayList<String>().apply { add("--network-caching=3000") })
        mediaPlayer = MediaPlayer(libVlc)
        mediaPlayer?.attachViews(findViewById(R.id.videoLayout), null, false, false)
    }

    private fun showUI() {
        topInfoPanel.visibility = View.VISIBLE; controlsPanel.visibility = View.VISIBLE
        handler.removeCallbacks(hideUiRunnable); handler.postDelayed(hideUiRunnable, 5000)
    }

    private fun hideUI() { topInfoPanel.visibility = View.GONE; controlsPanel.visibility = View.GONE; hideSystemUI() }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    private fun processChannelNumberInput() {
        val idx = inputNumber.toIntOrNull()?.minus(1) ?: -1
        if (idx in channels.indices) { currentChannelIndex = idx; playChannel() }
        inputNumber = ""
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            inputNumber += (keyCode - KeyEvent.KEYCODE_0).toString()
            handler.removeCallbacks(channelSwitchRunnable); showUI()
            handler.postDelayed(channelSwitchRunnable, 2500); return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onTouchEvent(e: MotionEvent) = mDetector.onTouchEvent(e) || super.onTouchEvent(e)
}