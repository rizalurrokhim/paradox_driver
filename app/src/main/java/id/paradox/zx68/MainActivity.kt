package id.paradox.zx68

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.GradientDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import id.paradox.zx68.data.AssetRepository
import id.paradox.zx68.data.ProfileStore
import id.paradox.zx68.model.LightingSettings
import id.paradox.zx68.ui.AboutActivity
import id.paradox.zx68.ui.ConsoleActivity
import id.paradox.zx68.ui.EffectAdapter
import id.paradox.zx68.ui.KeymapActivity
import id.paradox.zx68.usb.UsbHidTransport
import id.paradox.zx68.usb.Zx68Session
import id.paradox.zx68.util.LogBus

/**
 * Layar utama, disusun menyerupai software driver Windows.
 *
 * Tata letak:
 * 1. **Header perangkat** — nama model + VID/PID + pil status.
 * 2. **Tab** — `Pencahayaan` (grid efek, warna, kecerahan, kecepatan) dan
 *    `Perangkat` (daftar perangkat USB, pemilih manual, alat lain, log TX/RX).
 * 3. **Bar aksi bawah** — kirim setelan, hubungkan, putuskan.
 *
 * ## Deteksi perangkat
 *
 * Pencocokan tidak mengandalkan VID/PID Paradox (`0x2726`) saja: banyak unit
 * ZX68 melaporkan ID generik controller-nya (mis. `0x1EA7:0x0907`). Pemilihan
 * dilakukan lewat bentuk interface (`UsbHidTransport.candidates()`), dan bila
 * masih salah pengguna dapat memilih perangkat secara manual di tab Perangkat.
 */
class MainActivity : AppCompatActivity() {

    // ---------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------

    private lateinit var tvStatusPill: TextView
    private lateinit var tvDeviceModel: TextView
    private lateinit var tvHint: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvBrightness: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvLightingResult: TextView
    private lateinit var colorRow: LinearLayout
    private lateinit var deviceList: LinearLayout
    private lateinit var btnConnect: MaterialButton
    private lateinit var btnApply: MaterialButton
    private lateinit var tabLighting: TextView
    private lateinit var tabDevice: TextView
    private lateinit var panelLighting: View
    private lateinit var panelDevice: View
    private lateinit var seekBrightness: SeekBar
    private lateinit var seekSpeed: SeekBar

    private lateinit var store: ProfileStore
    private lateinit var effects: List<AssetRepository.Effect>
    private lateinit var adapter: EffectAdapter

    private var settings = LightingSettings()
    private var connecting = false
    private var hintOverride: String? = null
    private var removeLogListener: (() -> Unit)? = null
    private var attachReceiver: BroadcastReceiver? = null

    /** Warna yang bisa dipilih (indeks = `LEColor` pada driver). */
    private val colorOptions = listOf(
        "Default" to 0xFF90A4AE.toInt(),
        "Merah" to 0xFFE53935.toInt(),
        "Hijau" to 0xFF43A047.toInt(),
        "Biru" to 0xFF1E88E5.toInt(),
        "Kuning" to 0xFFFDD835.toInt(),
        "Ungu" to 0xFF8E24AA.toInt(),
        "Cyan" to 0xFF00BCD4.toInt(),
        "Putih" to 0xFFFFFFFF.toInt(),
    )

    // ---------------------------------------------------------------------
    // Siklus hidup
    // ---------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<MaterialToolbar>(R.id.toolbar).title = getString(R.string.app_name)
        tvStatusPill = findViewById(R.id.tvStatusPill)
        tvDeviceModel = findViewById(R.id.tvDeviceModel)
        tvHint = findViewById(R.id.tvHint)
        tvLog = findViewById(R.id.tvLog)
        tvBrightness = findViewById(R.id.tvBrightness)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvLightingResult = findViewById(R.id.tvLightingResult)
        colorRow = findViewById(R.id.colorRow)
        deviceList = findViewById(R.id.deviceList)
        btnConnect = findViewById(R.id.btnConnect)
        btnApply = findViewById(R.id.btnApply)
        tabLighting = findViewById(R.id.tabLighting)
        tabDevice = findViewById(R.id.tabDevice)
        panelLighting = findViewById(R.id.panelLighting)
        panelDevice = findViewById(R.id.panelDevice)
        seekBrightness = findViewById(R.id.seekBrightness)
        seekSpeed = findViewById(R.id.seekSpeed)

        store = ProfileStore(this)
        effects = AssetRepository.loadEffects(this)
        settings = store.loadLighting()

        setupTabs()
        setupEffectGrid()
        setupColors()
        setupSliders()

        btnConnect.setOnClickListener { startConnect() }
        findViewById<MaterialButton>(R.id.btnDisconnect).setOnClickListener { disconnect() }
        btnApply.setOnClickListener { sendLighting() }
        findViewById<MaterialButton>(R.id.btnScan).setOnClickListener { refreshDevices() }
        findViewById<MaterialButton>(R.id.btnPickDevice).setOnClickListener { showDevicePicker() }
        findViewById<MaterialButton>(R.id.btnKeymap).setOnClickListener {
            startActivity(Intent(this, KeymapActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnConsole).setOnClickListener {
            startActivity(Intent(this, ConsoleActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnAbout).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnClearLog).setOnClickListener {
            LogBus.clear()
            tvLog.text = ""
        }

        removeLogListener = LogBus.addListener { line -> runOnUiThread { appendLog(line) } }
        tvLog.text = LogBus.snapshot().takeLast(80).joinToString("\n")

        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            LogBus.log("Aplikasi dibuka oleh event USB_DEVICE_ATTACHED")
        }

        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        registerAttachReceiver()
        updateStatus()
        refreshDevices()
    }

    override fun onPause() {
        unregisterAttachReceiver()
        super.onPause()
    }

    override fun onDestroy() {
        removeLogListener?.invoke()
        Zx68Session.transport(this).releasePermissionReceiver()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // Tab
    // ---------------------------------------------------------------------

    private fun setupTabs() {
        tabLighting.setOnClickListener { selectTab(lighting = true) }
        tabDevice.setOnClickListener { selectTab(lighting = false) }
        selectTab(lighting = true)
    }

    /** Menampilkan salah satu panel dan menandai tab aktif. */
    private fun selectTab(lighting: Boolean) {
        panelLighting.visibility = if (lighting) View.VISIBLE else View.GONE
        panelDevice.visibility = if (lighting) View.GONE else View.VISIBLE
        tabLighting.setTextColor(
            ContextCompat.getColor(this, if (lighting) R.color.accent else R.color.text_secondary),
        )
        tabDevice.setTextColor(
            ContextCompat.getColor(this, if (lighting) R.color.text_secondary else R.color.accent),
        )
        if (!lighting) refreshDevices()
    }

    // ---------------------------------------------------------------------
    // Efek, warna, slider
    // ---------------------------------------------------------------------

    private fun setupEffectGrid() {
        adapter = EffectAdapter { position -> onEffectSelected(position) }
        adapter.submit(effects.map { it.name })
        adapter.selected = indexForModel(settings.model)

        findViewById<RecyclerView>(R.id.rvEffects).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 3)
            adapter = this@MainActivity.adapter
            setHasFixedSize(false)
        }
    }

    private fun onEffectSelected(position: Int) {
        adapter.selected = position
        settings = settings.copy(model = modelForEffect(position))
        tvLightingResult.text = "Efek: ${effects[position].name} (LEModel=${settings.model})"
    }

    /**
     * Menerjemahkan indeks daftar efek menjadi kode `LEModel`.
     *
     * Driver memakai `LEModel = 241` untuk efek RGB pada profil bawaan; efek
     * lain memakai indeks 1-based karena pemetaan lengkap ada di file `.le`
     * biner yang belum terbaca (lihat `docs/PROTOCOL.md` §8).
     */
    private fun modelForEffect(position: Int): Int {
        val rgbIndex = effects.indexOfFirst { it.name.contains("RGB", ignoreCase = true) }
        return if (position == rgbIndex) 241 else position + 1
    }

    /** Kebalikan dari [modelForEffect]. */
    private fun indexForModel(model: Int): Int {
        val rgbIndex = effects.indexOfFirst { it.name.contains("RGB", ignoreCase = true) }
        return if (model == 241 && rgbIndex >= 0) {
            rgbIndex
        } else {
            (model - 1).coerceIn(0, (effects.size - 1).coerceAtLeast(0))
        }
    }

    /** Deretan bulatan warna; yang dipilih diberi garis aksen. */
    private fun setupColors() {
        colorRow.removeAllViews()
        val density = resources.displayMetrics.density
        val size = (32 * density).toInt()
        val margin = (6 * density).toInt()

        colorOptions.forEachIndexed { index, (label, color) ->
            val swatch = View(this)
            val params = LinearLayout.LayoutParams(size, size)
            params.marginEnd = margin
            swatch.layoutParams = params
            swatch.contentDescription = label
            swatch.background = swatchDrawable(color, index == settings.color)
            swatch.setOnClickListener {
                settings = settings.copy(color = index)
                setupColors()
            }
            colorRow.addView(swatch)
        }
    }

    private fun swatchDrawable(color: Int, selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(
                resources.displayMetrics.density.toInt().coerceAtLeast(1) * 2,
                ContextCompat.getColor(
                    this@MainActivity,
                    if (selected) R.color.accent else R.color.surface_dark,
                ),
            )
        }

    private fun setupSliders() {
        seekBrightness.max = LightingSettings.MAX_BRIGHTNESS
        seekBrightness.progress = settings.brightness.coerceIn(0, LightingSettings.MAX_BRIGHTNESS)
        seekSpeed.max = LightingSettings.MAX_SPEED
        seekSpeed.progress = settings.speed.coerceIn(0, LightingSettings.MAX_SPEED)
        updateSliderLabels()

        seekBrightness.setOnSeekBarChangeListener(SimpleSeekListener { value ->
            settings = settings.copy(brightness = value)
            updateSliderLabels()
        })
        seekSpeed.setOnSeekBarChangeListener(SimpleSeekListener { value ->
            settings = settings.copy(speed = value)
            updateSliderLabels()
        })
    }

    private fun updateSliderLabels() {
        tvBrightness.text = getString(R.string.section_brightness, settings.brightness)
        tvSpeed.text = getString(R.string.section_speed, settings.speed)
    }

    // ---------------------------------------------------------------------
    // Koneksi
    // ---------------------------------------------------------------------

    private fun startConnect(device: UsbDevice? = null) {
        if (connecting) return
        connecting = true
        btnConnect.isEnabled = false
        hintOverride = null
        showStatus(getString(R.string.status_connecting), R.color.text_primary)
        tvHint.text = "Mencari keyboard…"

        val context = this
        Zx68Session.io.execute {
            val result = Zx68Session.connect(context, device)
            when (result) {
                is Zx68Session.ConnectResult.Success -> {
                    val handshake = Zx68Session.protocol(context).handshake()
                    LogBus.log(
                        "Handshake: ${if (handshake.ok) "OK" else "GAGAL"} — ${handshake.message}",
                    )
                    runOnUiThread {
                        connecting = false
                        btnConnect.isEnabled = true
                        hintOverride = if (handshake.ok) null
                        else "Terhubung, tetapi handshake tidak dibalas: ${handshake.message}"
                        updateStatus()
                        refreshDevices()
                    }
                }

                Zx68Session.ConnectResult.NeedPermission -> {
                    val transport = Zx68Session.transport(context)
                    val target = device ?: transport.findDevice()
                    runOnUiThread {
                        if (target == null) {
                            connecting = false
                            btnConnect.isEnabled = true
                            finishConnect("Tidak ada perangkat untuk dimintai izin.")
                        } else {
                            transport.requestPermission(target) { granted ->
                                connecting = false
                                btnConnect.isEnabled = true
                                if (granted) startConnect(target)
                                else finishConnect("Izin USB ditolak. Coba lagi lalu pilih “Izinkan”.")
                            }
                        }
                    }
                }

                is Zx68Session.ConnectResult.NotFound -> {
                    runOnUiThread {
                        connecting = false
                        btnConnect.isEnabled = true
                        val lines = result.devices
                        finishConnect(
                            buildString {
                                append("Tidak ada perangkat USB yang terlihat.\n")
                                if (lines.isEmpty()) {
                                    append("Daftar perangkat kosong: pastikan kabel OTG ")
                                    append("mendukung mode host dan keyboard mendapat daya.")
                                } else {
                                    append("Perangkat terlihat:\n")
                                    lines.take(8).forEach { append("  · $it\n") }
                                    append("Buka tab Perangkat untuk memilih manual.")
                                }
                            }.toString(),
                        )
                        renderDeviceRows(emptyList())
                    }
                }

                is Zx68Session.ConnectResult.Failed -> runOnUiThread {
                    connecting = false
                    btnConnect.isEnabled = true
                    finishConnect(
                        "Gagal membuka ${result.label}.\n" +
                            "Antarmuka mungkin dipegang sistem; cabut-colok ulang lalu coba lagi.",
                    )
                }
            }
        }
    }

    private fun finishConnect(message: String) {
        connecting = false
        btnConnect.isEnabled = true
        hintOverride = message
        updateStatus()
    }

    private fun disconnect() {
        Zx68Session.io.execute {
            Zx68Session.disconnect()
            runOnUiThread {
                hintOverride = "Koneksi ditutup. Keyboard kembali dipakai sistem."
                updateStatus()
                refreshDevices()
            }
        }
    }

    /** Memindai perangkat lalu menampilkan barisnya di tab Perangkat. */
    private fun refreshDevices() {
        val context = this
        Zx68Session.io.execute {
            val transport = Zx68Session.transport(context)
            val candidates = Zx68Session.candidates(context)
            val all = transport.listDevices().map { device ->
                transport.describe(device) to device
            }
            runOnUiThread { renderDeviceRows(candidates, all) }
        }
    }

    private fun renderDeviceRows(
        candidates: List<UsbHidTransport.Candidate>,
        allDevices: List<Pair<String, UsbDevice>> = emptyList(),
    ) {
        deviceList.removeAllViews()
        if (candidates.isEmpty() && allDevices.isEmpty()) {
            deviceList.addView(emptyRow("Tidak ada perangkat USB terdeteksi."))
            return
        }

        candidates.forEach { candidate ->
            deviceList.addView(
                deviceRow(
                    title = candidate.label,
                    subtitle = "Kemungkinan keyboard — ketuk untuk menghubungkan",
                    onClick = { startConnect(candidate.device) },
                ),
            )
        }

        val seen = candidates.map { it.device.deviceId }.toSet()
        allDevices.filterNot { seen.contains(it.second.deviceId) }.forEach { (label, device) ->
            deviceList.addView(
                deviceRow(
                    title = label,
                    subtitle = "Perangkat lain — ketuk untuk mencoba",
                    onClick = { startConnect(device) },
                ),
            )
        }
    }

    private fun showDevicePicker() {
        val context = this
        Zx68Session.io.execute {
            val transport = Zx68Session.transport(context)
            val candidates = Zx68Session.candidates(context)
            val all = transport.listDevices().map { transport.describe(it) to it }
            runOnUiThread {
                if (all.isEmpty()) {
                    Toast.makeText(this, "Tidak ada perangkat USB", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val labels = all.mapIndexed { index, (label, _) ->
                    val hint = if (candidates.any { it.device.deviceId == all[index].second.deviceId })
                        " • disarankan" else ""
                    "$label$hint"
                }.toTypedArray()

                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Pilih perangkat")
                    .setItems(labels) { _, which -> startConnect(all[which].second) }
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .show()
            }
        }
    }

    /** Baris perangkat sederhana yang bisa diketuk. */
    private fun deviceRow(title: String, subtitle: String, onClick: () -> Unit): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            background = GradientDrawable().apply {
                cornerRadius = 12f * resources.displayMetrics.density
                setColor(ContextCompat.getColor(this@MainActivity, R.color.surface_dark_alt))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = (8 * resources.displayMetrics.density).toInt() }
            setOnClickListener { onClick() }
        }
        container.addView(
            TextView(this).apply {
                text = title
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 12f
            },
        )
        container.addView(
            TextView(this).apply {
                text = subtitle
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 10f
            },
        )
        return container
    }

    private fun emptyRow(message: String): View = TextView(this).apply {
        text = message
        gravity = Gravity.START
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
        textSize = 11f
        setPadding(0, 8, 0, 8)
    }

    // ---------------------------------------------------------------------
    // Pencahayaan
    // ---------------------------------------------------------------------

    private fun sendLighting() {
        if (!Zx68Session.isConnected()) {
            Toast.makeText(this, "Keyboard belum terhubung", Toast.LENGTH_SHORT).show()
            return
        }
        store.saveLighting(settings)
        tvLightingResult.text = "Mengirim…"

        Zx68Session.io.execute {
            val protocol = Zx68Session.protocol(this)
            // 1. Kirim setelan LED (model, brightness, speed, color, enable)
            val result = protocol.setLighting(settings)
            // 2. Auto-commit (sama seperti driver Windows: setelan disimpan ke flash
            // RAM tanpa apply, jadi langkah ini wajib untuk persistensi).
            val applyResult = protocol.apply()
            val summary = buildString {
                appendLine("setLighting: ${if (result.ok) "OK" else "GAGAL"} — ${result.message}")
                appendLine("apply: ${if (applyResult.ok) "OK" else "GAGAL"} — ${applyResult.message}")
                append(
                    "model=${settings.model} brightness=${settings.brightness} " +
                        "speed=${settings.speed} color=${settings.color} enable=${settings.enable}",
                )
            }
            runOnUiThread { tvLightingResult.text = summary }
        }
    }

    // ---------------------------------------------------------------------
    // Status & log
    // ---------------------------------------------------------------------

    private fun updateStatus() {
        val label = Zx68Session.deviceLabel
        if (Zx68Session.isConnected() && label != null) {
            showStatus(getString(R.string.status_connected_pill), R.color.success)
            tvDeviceModel.text = label
            tvHint.text = hintOverride
                ?: "Perintah dikirim lewat USB HID (endpoint 64 byte). " +
                "Selama terhubung, keyboard tidak mengetik ke Android."
        } else {
            showStatus(getString(R.string.status_disconnected), R.color.text_secondary)
            tvDeviceModel.text = getString(R.string.device_model_idle)
            tvHint.text = hintOverride ?: getString(R.string.hint_connect)
        }
        btnConnect.isEnabled = !connecting
        btnApply.isEnabled = Zx68Session.isConnected()
    }

    private fun showStatus(text: String, colorRes: Int) {
        tvStatusPill.text = text
        tvStatusPill.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun appendLog(line: String) {
        val current = tvLog.text?.toString().orEmpty()
        val merged = if (current.isEmpty()) line else "$current\n$line"
        val lines = merged.split('\n')
        tvLog.text = if (lines.size > 300) lines.takeLast(300).joinToString("\n") else merged
    }

    // ---------------------------------------------------------------------
    // Colok/lepas
    // ---------------------------------------------------------------------

    private fun registerAttachReceiver() {
        if (attachReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val device = intent?.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                val name = device?.let { "VID 0x%04X PID 0x%04X".format(it.vendorId, it.productId) }
                when (intent?.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        LogBus.log("USB dicolok: ${name ?: "perangkat"}")
                        hintOverride = null
                        updateStatus()
                        refreshDevices()
                    }

                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        LogBus.log("USB dicabut: ${name ?: "perangkat"}")
                        Zx68Session.io.execute { Zx68Session.disconnect() }
                        hintOverride = "Keyboard tercabut."
                        updateStatus()
                        refreshDevices()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        attachReceiver = receiver
    }

    private fun unregisterAttachReceiver() {
        attachReceiver?.let { runCatching { unregisterReceiver(it) } }
        attachReceiver = null
    }

    /** Listener slider sederhana agar tidak menulis objek anonim berulang. */
    private class SimpleSeekListener(
        private val onChanged: (Int) -> Unit,
    ) : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) =
            onChanged(progress)

        override fun onStartTrackingTouch(bar: SeekBar?) = Unit
        override fun onStopTrackingTouch(bar: SeekBar?) = Unit
    }
}
