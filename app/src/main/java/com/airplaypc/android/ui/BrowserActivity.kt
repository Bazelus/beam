package com.airplaypc.android.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import android.Manifest
import android.content.pm.PackageManager
import com.airplaypc.android.R
import com.airplaypc.android.airplay.AtvDevice
import com.airplaypc.android.airplay.PyAtvBridge
import com.airplaypc.android.browser.BrowseHistory
import com.airplaypc.android.browser.CastSession
import com.airplaypc.android.browser.DetectedStream
import com.airplaypc.android.browser.HlsQualityParser
import com.airplaypc.android.browser.LocalPlaylistServer
import com.airplaypc.android.browser.MediaKind
import com.airplaypc.android.browser.StreamQuality
import com.airplaypc.android.browser.VideoDetector
import com.airplaypc.android.databinding.ActivityBrowserBinding
import com.airplaypc.android.databinding.SheetAirplayConnectBinding
import com.airplaypc.android.databinding.SheetCastQualityBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.concurrent.thread

class BrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBrowserBinding
    private lateinit var castSession: CastSession
    private lateinit var detector: VideoDetector
    private lateinit var history: BrowseHistory
    private val playlistServer get() = LocalPlaylistServer.shared

    private val devices = mutableListOf<AtvDevice>()
    private var latestStream: DetectedStream? = null
    private var pairingId: String? = null
    private var showingHome = true

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        history = BrowseHistory(this)
        castSession = CastSession(this)
        PyAtvBridge.init(this)
        updateConnectionUi()

        detector = VideoDetector { stream ->
            runOnUiThread { showCastBar(stream) }
        }

        setupWebView()
        setupChrome()
        setupHome()
        showHome()
        ensureCastBackgroundPermissions()
    }

    private fun ensureCastBackgroundPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001,
                )
            }
        }
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(i)
            }
        } catch (_: Throwable) {
        }
    }

    private fun setupChrome() {
        binding.btnBack.setOnClickListener {
            when {
                binding.webView.canGoBack() -> binding.webView.goBack()
                !showingHome -> showHome()
            }
        }
        binding.btnAirPlay.setOnClickListener { openConnectSheet() }
        binding.chipConnection.setOnClickListener { openConnectSheet() }
        binding.btnMore.setOnClickListener { openMoreMenu() }
        binding.castBar.setOnClickListener { openQualitySheet() }

        binding.btnClearUrl.setOnClickListener {
            binding.editUrl.setText("")
            binding.btnClearUrl.isVisible = false
            showHome()
        }

        binding.editUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.btnClearUrl.isVisible = !s.isNullOrEmpty()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        binding.editUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE
            ) {
                navigate(binding.editUrl.text?.toString().orEmpty())
                true
            } else false
        }

        binding.editUrl.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && showingHome) {
                // keep home visible until navigation
            }
        }
    }

    private fun setupHome() {
        val home = binding.homePanel
        home.chipYoutube.setOnClickListener { navigate("https://m.youtube.com") }
        home.chipVimeo.setOnClickListener { navigate("https://vimeo.com") }
        home.chipTwitch.setOnClickListener { navigate("https://m.twitch.tv") }
        home.chipDailymotion.setOnClickListener { navigate("https://www.dailymotion.com") }
        home.btnClearHistory.setOnClickListener {
            history.clear()
            refreshHistoryList()
        }
        refreshHistoryList()
    }

    private fun showHome() {
        showingHome = true
        binding.homePanel.root.isVisible = true
        binding.webView.isVisible = false
        binding.progress.isVisible = false
        hideCastBar()
        binding.editUrl.setText("")
        binding.btnClearUrl.isVisible = false
        refreshHistoryList()
    }

    private fun hideHome() {
        showingHome = false
        binding.homePanel.root.isVisible = false
        binding.webView.isVisible = true
    }

    private fun refreshHistoryList() {
        val home = binding.homePanel
        val entries = history.load()
        home.historyList.removeAllViews()
        home.textHistoryEmpty.isVisible = entries.isEmpty()
        home.btnClearHistory.isVisible = entries.isNotEmpty()
        for (entry in entries.take(12)) {
            val row = layoutInflater.inflate(R.layout.item_history, home.historyList, false)
            row.findViewById<TextView>(R.id.textHistoryTitle).text = entry.title
            row.findViewById<TextView>(R.id.textHistoryUrl).text = entry.url
            row.setOnClickListener { navigate(entry.url) }
            home.historyList.addView(row)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        val settings = binding.webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.userAgentString = settings.userAgentString + " Beam/1.0"
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true

        binding.webView.addJavascriptInterface(detector.JsBridge(), "AirPlayDetect")

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                hideHome()
                binding.progress.isVisible = true
                binding.progress.progress = 0
                detector.reset()
                hideCastBar()
                if (!url.isNullOrBlank()) {
                    detector.pageUrl = url
                    binding.editUrl.setText(url)
                    binding.btnClearUrl.isVisible = true
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progress.isVisible = false
                detector.pageTitle = view?.title.orEmpty()
                detector.injectProbe(binding.webView)
                if (!url.isNullOrBlank()) {
                    history.add(url, view?.title.orEmpty())
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): android.webkit.WebResourceResponse? {
                detector.onRequest(request)
                return super.shouldInterceptRequest(view, request)
            }
        }

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (showingHome) return
                binding.progress.progress = newProgress
                binding.progress.isVisible = newProgress in 1..99
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) detector.pageTitle = title
            }
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        val token = binding.editUrl.windowToken ?: binding.root.windowToken
        imm?.hideSoftInputFromWindow(token, 0)
        binding.editUrl.clearFocus()
    }

    private fun pauseLocalPlayback() {
        try {
            binding.webView.onPause()
        } catch (_: Throwable) {
        }
        binding.webView.evaluateJavascript(
            """
            (function(){
              try {
                document.querySelectorAll('video,audio').forEach(function(m){
                  try { m.pause(); m.muted = true; } catch(e) {}
                });
                document.querySelectorAll('iframe').forEach(function(f){
                  try {
                    var s = f.src || '';
                    if (s.indexOf('youtube') !== -1 || s.indexOf('vimeo') !== -1) {
                      f.contentWindow && f.contentWindow.postMessage &&
                        f.contentWindow.postMessage('{"event":"command","func":"pauseVideo","args":""}', '*');
                    }
                  } catch(e) {}
                });
              } catch(e) {}
            })();
            """.trimIndent(),
            null,
        )
    }

    private fun navigate(raw: String) {
        val input = raw.trim()
        if (input.isEmpty()) return
        hideKeyboard()
        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(input, "UTF-8")
        }
        hideHome()
        binding.webView.loadUrl(url)
    }

    private fun showCastBar(stream: DetectedStream) {
        latestStream = stream
        binding.castBar.isVisible = true
        binding.castTitle.text = when (stream.kind) {
            MediaKind.AUDIO -> getString(R.string.cast_audio_title)
            else -> getString(R.string.cast_bar_title)
        }
        val kindHint = when {
            stream.isHls -> "HLS"
            stream.isAudio -> "Audio"
            stream.isMp4 -> "MP4"
            else -> stream.pageHost
        }
        binding.castSubtitle.text = "${stream.title} · $kindHint"
        updateConnectionUi()
    }

    private fun hideCastBar() {
        latestStream = null
        binding.castBar.isVisible = false
    }

    private fun updateConnectionUi() {
        val linked = castSession.isLinked()
        val name = castSession.connectedLabel()
        if (linked) {
            binding.chipConnection.text = getString(R.string.connected_to, name)
            binding.chipConnection.chipIcon = ContextCompat.getDrawable(this, R.drawable.ic_airplay)
            binding.chipConnection.setChipBackgroundColorResource(R.color.md_theme_secondary_container)
            binding.chipConnection.setTextColor(
                ContextCompat.getColor(this, R.color.md_theme_on_secondary_container),
            )
            binding.connectDot.isVisible = true
            binding.castDeviceHint.text = name
        } else {
            binding.chipConnection.text = getString(R.string.not_connected)
            binding.chipConnection.chipIcon = null
            binding.chipConnection.setChipBackgroundColorResource(R.color.md_theme_surface_variant)
            binding.chipConnection.setTextColor(
                ContextCompat.getColor(this, R.color.md_theme_on_surface_variant),
            )
            binding.connectDot.isVisible = false
            binding.castDeviceHint.text = getString(R.string.connect_airplay)
        }
    }

    private fun openMoreMenu() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_name)
            .setItems(
                arrayOf(
                    getString(R.string.home),
                    getString(R.string.screenshare),
                    getString(R.string.reload),
                    getString(R.string.desktop_site),
                    if (castSession.isCasting) getString(R.string.stop_cast) else getString(R.string.stop_airplay),
                ),
            ) { _, which ->
                when (which) {
                    0 -> showHome()
                    1 -> startActivity(Intent(this, MainActivity::class.java))
                    2 -> if (!showingHome) binding.webView.reload()
                    3 -> {
                        binding.webView.settings.userAgentString =
                            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"
                        if (!showingHome) binding.webView.reload()
                    }
                    4 -> thread {
                        castSession.stop()
                        runOnUiThread {
                            Toast.makeText(this, R.string.stopped, Toast.LENGTH_SHORT).show()
                            updateConnectionUi()
                        }
                    }
                }
            }
            .show()
    }

    private fun openConnectSheet() {
        val dialog = BottomSheetDialog(this)
        val sheet = SheetAirplayConnectBinding.inflate(LayoutInflater.from(this))
        dialog.setContentView(sheet.root)
        sheet.textConnectStatus.text = castSession.connectedLabel()

        fun refreshSpinner() {
            val labels = if (devices.isEmpty()) {
                listOf(getString(R.string.no_devices))
            } else {
                devices.map {
                    val mark = if (it.paired) "✓ " else ""
                    "$mark${it.label}"
                }
            }
            sheet.spinnerAtv.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                labels,
            )
        }
        refreshSpinner()

        sheet.btnScanDevices.setOnClickListener {
            sheet.textConnectStatus.text = getString(R.string.scanning)
            val manual = sheet.editConnectIp.text?.toString().orEmpty()
            thread {
                try {
                    val extra = if (manual.isNotBlank()) listOf(manual.trim()) else emptyList()
                    val found = PyAtvBridge.scan(this, 8.0, extra)
                    runOnUiThread {
                        devices.clear()
                        devices.addAll(found)
                        refreshSpinner()
                        sheet.textConnectStatus.text = getString(R.string.devices_found, found.size)
                    }
                } catch (t: Throwable) {
                    runOnUiThread {
                        sheet.textConnectStatus.text = getString(R.string.error_prefix, t.message ?: "")
                    }
                }
            }
        }

        sheet.btnPairDevice.setOnClickListener {
            val device = devices.getOrNull(sheet.spinnerAtv.selectedItemPosition) ?: run {
                Toast.makeText(this, R.string.choose_device, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pairingId = device.identifier
            sheet.textConnectStatus.text = getString(R.string.pairing)
            thread {
                try {
                    PyAtvBridge.pairStart(device.identifier)
                    val pin = sheet.editConnectPin.text?.toString().orEmpty()
                    if (pin.isNotBlank()) {
                        PyAtvBridge.pairPin(pin)
                    }
                    if (pin.isNotBlank()) {
                        PyAtvBridge.pairFinish(device.identifier, device.name)
                        castSession.selectDevice(device)
                        runOnUiThread {
                            sheet.textConnectStatus.text = getString(R.string.paired_as, device.name)
                            updateConnectionUi()
                            Toast.makeText(this, R.string.toast_connected, Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        runOnUiThread {
                            sheet.textConnectStatus.text = getString(R.string.enter_pin_again)
                        }
                    }
                } catch (t: Throwable) {
                    runOnUiThread {
                        sheet.textConnectStatus.text = getString(R.string.pairing_error, t.message ?: "")
                    }
                }
            }
        }

        sheet.btnSelectDevice.setOnClickListener {
            val device = devices.getOrNull(sheet.spinnerAtv.selectedItemPosition)
            if (device == null) {
                Toast.makeText(this, R.string.choose_device, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val pin = sheet.editConnectPin.text?.toString().orEmpty()
            thread {
                try {
                    if (pin.isNotBlank() && pairingId == device.identifier) {
                        PyAtvBridge.pairPin(pin)
                        PyAtvBridge.pairFinish(device.identifier, device.name)
                    }
                    if (!PyAtvBridge.hasCredentials(device.identifier)) {
                        runOnUiThread {
                            sheet.textConnectStatus.text = getString(R.string.pair_first)
                        }
                        return@thread
                    }
                    castSession.selectDevice(device)
                    runOnUiThread {
                        updateConnectionUi()
                        sheet.textConnectStatus.text = getString(R.string.connected_as, device.name)
                        dialog.dismiss()
                    }
                } catch (t: Throwable) {
                    runOnUiThread {
                        sheet.textConnectStatus.text = t.message
                    }
                }
            }
        }

        dialog.show()
        sheet.btnScanDevices.performClick()
    }

    private fun openQualitySheet() {
        val stream = latestStream
        if (stream == null) {
            Toast.makeText(this, R.string.no_stream, Toast.LENGTH_SHORT).show()
            return
        }
        if (!castSession.isLinked()) {
            Toast.makeText(this, R.string.connect_first, Toast.LENGTH_SHORT).show()
            openConnectSheet()
            return
        }

        val dialog = BottomSheetDialog(this)
        val sheet = SheetCastQualityBinding.inflate(LayoutInflater.from(this))
        dialog.setContentView(sheet.root)
        sheet.textQualityHint.text = stream.url
        sheet.btnStopCast.isVisible = castSession.isCasting

        sheet.radioQualities.removeAllViews()
        val loading = RadioButton(this).apply {
            text = getString(R.string.loading_qualities)
            isEnabled = false
            id = View.generateViewId()
        }
        sheet.radioQualities.addView(loading)

        thread {
            val qualities: List<StreamQuality> = try {
                if (stream.isHls) {
                    HlsQualityParser.parse(stream.url, stream.title)
                } else {
                    listOf(StreamQuality(getString(R.string.quality_original), stream.url))
                }
            } catch (_: Throwable) {
                listOf(StreamQuality(getString(R.string.quality_original), stream.url))
            }
            runOnUiThread {
                sheet.radioQualities.removeAllViews()
                qualities.forEachIndexed { index, q ->
                    val rb = RadioButton(this).apply {
                        text = q.label
                        id = View.generateViewId()
                        tag = q
                        textSize = 16f
                        setPadding(8, 18, 8, 18)
                        isChecked = index == 0
                    }
                    sheet.radioQualities.addView(rb)
                }
            }
        }

        sheet.btnStartCast.setOnClickListener {
            val checkedId = sheet.radioQualities.checkedRadioButtonId
            val rb = sheet.radioQualities.findViewById<RadioButton>(checkedId)
            val quality = rb?.tag as? StreamQuality
            sheet.btnStartCast.isEnabled = false
            sheet.btnStartCast.text = getString(R.string.sending_cast)
            thread {
                try {
                    val playUrl = resolvePlayUrl(quality, stream)
                    val mediaType = if (stream.isAudio) "music" else "video"
                    castSession.play(playUrl, mediaType)
                    runOnUiThread {
                        pauseLocalPlayback()
                        Toast.makeText(
                            this,
                            getString(R.string.playing_on, castSession.connectedLabel()),
                            Toast.LENGTH_LONG,
                        ).show()
                        updateConnectionUi()
                        dialog.dismiss()
                    }
                } catch (t: Throwable) {
                    runOnUiThread {
                        sheet.btnStartCast.isEnabled = true
                        sheet.btnStartCast.text = getString(R.string.start_cast)
                        Toast.makeText(this, t.message ?: getString(R.string.cast_failed), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        sheet.btnStopCast.setOnClickListener {
            thread {
                castSession.stop()
                runOnUiThread {
                    Toast.makeText(this, R.string.stopped, Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
        }

        dialog.show()
    }

    private fun resolvePlayUrl(quality: StreamQuality?, stream: DetectedStream): String {
        val q = quality ?: return stream.url
        val body = q.localPlaylistBody
        if (!body.isNullOrBlank()) {
            return playlistServer.publish(body)
        }
        return q.url
    }

    override fun onResume() {
        super.onResume()
        if (!castSession.isCasting) {
            try {
                binding.webView.onResume()
            } catch (_: Throwable) {
            }
        }
        updateConnectionUi()
    }

    override fun onDestroy() {
        // Keep local playlist server alive while casting (screen lock / Activity recreate).
        if (!castSession.isCasting) {
            try {
                playlistServer.stopServer()
            } catch (_: Throwable) {
            }
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            binding.webView.canGoBack() -> binding.webView.goBack()
            !showingHome -> showHome()
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }
}
