package com.airplaypc.android.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.airplaypc.android.airplay.AtvDevice
import com.airplaypc.android.airplay.PyAtvBridge
import com.airplaypc.android.databinding.ActivityMainBinding
import com.airplaypc.android.encode.QualityPreset
import com.airplaypc.android.service.StreamService
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val devices = mutableListOf<AtvDevice>()
    private var pairingId: String? = null
    private var streaming = false

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            setStatus("Capture abgebrochen")
            return@registerForActivityResult
        }
        val device = selectedDevice()
        if (device == null) {
            setStatus("Kein Apple TV gewählt")
            return@registerForActivityResult
        }
        val quality = QualityPreset.fromLabel(binding.spinnerQuality.selectedItem?.toString() ?: "")
        StreamService.start(
            this,
            result.resultCode,
            data,
            quality,
            device.identifier,
            device.name,
        )
        streaming = true
        binding.btnStart.isEnabled = false
        binding.btnStop.isEnabled = true
        setStatus("Starte…")
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        appendLog("Permissions: $granted")
    }

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val msg = intent?.getStringExtra(StreamService.EXTRA_MSG) ?: return
            when (intent.action) {
                StreamService.ACTION_LOG -> appendLog(msg)
                StreamService.ACTION_STATUS -> setStatus(msg)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        PyAtvBridge.init(this)
        requestRuntimePermissions()

        binding.spinnerQuality.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            QualityPreset.entries.map { it.label },
        )
        binding.spinnerQuality.setSelection(QualityPreset.entries.indexOf(QualityPreset.P1080_30))

        binding.spinnerDevices.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Keine Geräte — Suchen tippen"),
        )

        binding.btnScan.setOnClickListener { scanDevices() }
        binding.btnPair.setOnClickListener { startPairing() }
        binding.btnPinOk.setOnClickListener { submitPin() }
        binding.btnStart.setOnClickListener { startShare() }
        binding.btnStop.setOnClickListener { stopShare() }

        val filter = IntentFilter().apply {
            addAction(StreamService.ACTION_LOG)
            addAction(StreamService.ACTION_STATUS)
        }
        ContextCompat.registerReceiver(this, logReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        appendLog("Bereit. Apple TV suchen…")
        scanDevices()
    }

    override fun onDestroy() {
        unregisterReceiver(logReceiver)
        super.onDestroy()
    }

    private fun requestRuntimePermissions() {
        val need = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
        )
        if (Build.VERSION.SDK_INT >= 33) {
            need += Manifest.permission.POST_NOTIFICATIONS
            need += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (Build.VERSION.SDK_INT in 23..32) {
            need += Manifest.permission.ACCESS_FINE_LOCATION
        }
        val missing = need.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun scanDevices() {
        setStatus("Suche Apple TVs…")
        binding.btnScan.isEnabled = false
        val manual = binding.editManualIp.text?.toString()?.trim().orEmpty()
        thread {
            try {
                val extra = if (manual.isNotEmpty()) listOf(manual) else emptyList()
                val found = PyAtvBridge.scan(this@MainActivity, timeoutSec = 8.0, extraHosts = extra)
                runOnUiThread {
                    devices.clear()
                    devices.addAll(found)
                    val labels = if (found.isEmpty()) {
                        listOf("Keine Geräte — IP eingeben oder erneut suchen")
                    } else {
                        found.map {
                            val tag = if (it.paired) "✓ " else ""
                            "$tag${it.label}"
                        }
                    }
                    binding.spinnerDevices.adapter = ArrayAdapter(
                        this,
                        android.R.layout.simple_spinner_dropdown_item,
                        labels,
                    )
                    setStatus("${found.size} Gerät(e)")
                    appendLog("Scan: ${found.size} Gerät(e)")
                    found.forEach { appendLog(" · ${it.name} ${it.address} (${it.model})") }
                    binding.btnScan.isEnabled = true
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    setStatus("Scan-Fehler")
                    appendLog("Scan: ${t.message}")
                    binding.btnScan.isEnabled = true
                }
            }
        }
    }

    private fun selectedDevice(): AtvDevice? {
        val idx = binding.spinnerDevices.selectedItemPosition
        return devices.getOrNull(idx)
    }

    private fun startPairing() {
        val device = selectedDevice() ?: run {
            Toast.makeText(this, "Gerät wählen", Toast.LENGTH_SHORT).show()
            return
        }
        pairingId = device.identifier
        binding.textPairStatus.text = "Pairing…"
        appendLog("Koppeln: ${device.name}")
        thread {
            try {
                val state = PyAtvBridge.pairStart(device.identifier)
                runOnUiThread {
                    binding.textPairStatus.text = "PIN eingeben ($state)"
                    setStatus("PIN vom Apple TV eingeben")
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    binding.textPairStatus.text = "Fehler"
                    appendLog("Pair: ${t.message}")
                }
            }
        }
    }

    private fun submitPin() {
        val pin = binding.editPin.text?.toString().orEmpty()
        val id = pairingId ?: selectedDevice()?.identifier
        if (id == null) {
            Toast.makeText(this, "Zuerst Koppeln tippen", Toast.LENGTH_SHORT).show()
            return
        }
        val name = selectedDevice()?.name.orEmpty()
        thread {
            try {
                PyAtvBridge.pairPin(pin)
                val res = PyAtvBridge.pairFinish(id, name)
                runOnUiThread {
                    binding.textPairStatus.text = "Gekoppelt ($res)"
                    appendLog("Pairing OK")
                    setStatus("Gekoppelt")
                    scanDevices()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    binding.textPairStatus.text = "Pairing fehlgeschlagen"
                    appendLog("PIN/Pair: ${t.message}")
                }
            }
        }
    }

    private fun startShare() {
        val device = selectedDevice()
        if (device == null) {
            setStatus("Bitte Apple TV wählen")
            return
        }
        if (!PyAtvBridge.hasCredentials(device.identifier)) {
            setStatus("Zuerst koppeln (Schritt 2)")
            return
        }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun stopShare() {
        StreamService.stop(this)
        streaming = false
        binding.btnStart.isEnabled = true
        binding.btnStop.isEnabled = false
        setStatus("Stoppe…")
    }

    private fun setStatus(text: String) {
        binding.textStatus.text = text
    }

    private fun appendLog(text: String) {
        binding.textLog.append(text + "\n")
    }
}
