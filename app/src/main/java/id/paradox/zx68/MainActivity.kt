package id.paradox.zx68

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import id.paradox.zx68.protocol.DeviceIds
import id.paradox.zx68.ui.AboutActivity
import id.paradox.zx68.ui.ConsoleActivity
import id.paradox.zx68.ui.KeymapActivity
import id.paradox.zx68.ui.LightingActivity
import id.paradox.zx68.usb.UsbHidTransport
import id.paradox.zx68.usb.Zx68Session
import id.paradox.zx68.util.LogBus

/**
 * Layar utama aplikasi.
 *
 * Tanggung jawab:
 * 1. Menampilkan status koneksi USB (dan info perangkat).
 * 2. Menjalankan alur izin USB Android:
 *    `findDevice` → `hasPermission` → `requestPermission` → tunggu → `open`.
 * 3. Menyediakan navigasi ke fitur pencahayaan, remap, konsol, dan info.
 * 4. Menampilkan log TX/RX secara langsung.
 *
 * ## Kenapa izin ditunggu dengan polling
 * Hasil dialog izin USB dikirim lewat `PendingIntent` + broadcast. Pada
 * beberapa perangkat (dan pada Android 13+ dengan receiver *not exported*)
 * broadcast itu bisa tidak pernah sampai, sehingga aplikasi terlihat "macet"
 * setelah pengguna menekan **Izinkan**. Karena `UsbManager.hasPermission()`
 * adalah sumber kebenaran, alur di sini menunggu izin dengan polling singkat
 * dan tetap memakai broadcast sebagai jalur cepat.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvHint: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnConnect: MaterialButton

    /** Pelepas listener log agar tidak bocor saat activity dihancurkan. */
    private var removeLogListener: (() -> Unit)? = null

    /** `true` selama proses koneksi berjalan (mencegah tap ganda). */
    private var connecting = false

    /** Pesan status khusus yang tidak boleh ditimpa [updateStatus]. */
    private var hintOverride: String? = null

    /** Memantau keyboard dicabut/dicolok saat aplikasi terbuka. */
    private var attachReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<MaterialToolbar>(R.id.toolbar).title = getString(R.string.app_name)
        tvStatus = findViewById(R.id.tvStatus)
        tvHint = findViewById(R.id.tvHint)
        tvLog = findViewById(R.id.tvLog)
        btnConnect = findViewById(R.id.btnConnect)

        btnConnect.setOnClickListener { startConnect() }
        findViewById<MaterialButton>(R.id.btnDisconnect).setOnClickListener {
            Zx68Session.io.execute {
                Zx68Session.disconnect()
                runOnUiThread {
                    hintOverride = "Koneksi ditutup. Keyboard kembali dipakai sistem — " +
                        "bila tidak mengetik, cabut dan colok ulang kabel OTG."
                    updateStatus()
                }
            }
        }

        findViewById<MaterialButton>(R.id.btnLighting).setOnClickListener {
            startActivity(Intent(this, LightingActivity::class.java))
        }
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

        removeLogListener = LogBus.addListener { line ->
            runOnUiThread { appendLog(line) }
        }
        tvLog.text = LogBus.snapshot().takeLast(60).joinToString("\n")

        // Jika aplikasi dibuka karena keyboard dicolokkan (USB_DEVICE_ATTACHED).
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            LogBus.log("Aplikasi dibuka oleh event USB_DEVICE_ATTACHED")
        }

        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        registerAttachReceiver()
        updateStatus()
    }

    override fun onPause() {
        unregisterAttachReceiver()
        super.onPause()
    }

    override fun onDestroy() {
        removeLogListener?.invoke()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // Pemantauan colok/lepas
    // ---------------------------------------------------------------------

    /** Mendaftarkan receiver sistem untuk event colok/lepas USB. */
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
                        if (device != null && DeviceIds.isZx68(device.vendorId, device.productId)) {
                            // Jangan buka otomatis: sistem tetap butuh dialog izin.
                            hintOverride = "Keyboard ZX68 terdeteksi. Tekan “Hubungkan”."
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        LogBus.log("USB dicabut: ${name ?: "perangkat"}")
                        Zx68Session.io.execute { Zx68Session.disconnect() }
                        hintOverride = "Keyboard tercabut."
                    }
                }
                updateStatus()
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // Broadcast sistem (USB) tetap diterima dengan RECEIVER_NOT_EXPORTED.
        ContextCompat.registerReceiver(
            this,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        attachReceiver = receiver
    }

    private fun unregisterAttachReceiver() {
        attachReceiver?.let { runCatching { unregisterReceiver(it) } }
        attachReceiver = null
    }

    // ---------------------------------------------------------------------
    // Alur koneksi
    // ---------------------------------------------------------------------

    /**
     * Langkah 1: cari perangkat, pastikan izin, lalu buka koneksi.
     *
     * Berjalan di [Zx68Session.io] agar tidak memblokir UI saat menunggu
     * dialog izin atau `bulkTransfer`.
     */
    private fun startConnect() {
        if (connecting) return
        connecting = true
        btnConnect.isEnabled = false
        hintOverride = null
        tvStatus.text = getString(R.string.status_connecting)
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        tvHint.text = "Mencari keyboard ZX68 lewat USB…"

        val context = this
        Zx68Session.io.execute {
            val transport = Zx68Session.transport(context)
            val device = transport.findDevice()

            if (device == null) {
                finishConnect(
                    hint = "Keyboard ZX68 tidak ditemukan.\n" +
                        "Pastikan kabel OTG terpasang dan VID/PID 0x%04X:0x0094 " +
                        "atau 0x%04X:0x00A6 terdeteksi."
                            .format(DeviceIds.VENDOR_ID, DeviceIds.VENDOR_ID),
                )
                return@execute
            }

            var granted = transport.hasPermission(device)
            if (!granted) {
                LogBus.log("Meminta izin USB untuk ${device.deviceName}")
                // Broadcast dipakai sebagai jalur cepat; hasil sebenarnya dicek
                // lewat polling di bawah.
                runOnUiThread {
                    transport.requestPermission(device) { ok ->
                        LogBus.log(if (ok) "Broadcast izin: diberikan" else "Broadcast izin: ditolak")
                    }
                }
                granted = waitForPermission(transport, device)
            }

            if (!granted) {
                finishConnect(
                    hint = "Izin USB belum diberikan.\n" +
                        "Tekan “Hubungkan” lagi, lalu pilih “Izinkan” pada dialog Android.",
                )
                return@execute
            }

            val (openedDevice, ok) = Zx68Session.connect(context)
            if (!ok || openedDevice == null) {
                finishConnect(
                    hint = "Gagal membuka perangkat USB.\n" +
                        "Cek log di bawah: apakah antarmuka berhasil di-claim?",
                )
                return@execute
            }

            val handshake = Zx68Session.protocol(context).handshake()
            LogBus.log(
                "Handshake: ${if (handshake.ok) "OK" else "GAGAL"} — ${handshake.message}",
            )

            runOnUiThread {
                connecting = false
                btnConnect.isEnabled = true
                hintOverride = if (handshake.ok) null else
                    "Terhubung, tetapi handshake tidak dibalas: ${handshake.message}"
                updateStatus()
            }
        }
    }

    /**
     * Menunggu `hasPermission()` menjadi `true` selama maksimal ±15 detik.
     *
     * Dipanggil dari thread IO. Berhenti lebih awal bila perangkat tercabut.
     */
    private fun waitForPermission(
        transport: UsbHidTransport,
        device: UsbDevice,
    ): Boolean {
        repeat(60) {
            if (transport.hasPermission(device)) return true
            val stillPlugged = transport.listDevices().any { it.deviceId == device.deviceId }
            if (!stillPlugged) return false
            Thread.sleep(250)
        }
        return transport.hasPermission(device)
    }

    /** Menutup proses koneksi dengan pesan yang terlihat pengguna. */
    private fun finishConnect(hint: String) {
        runOnUiThread {
            connecting = false
            btnConnect.isEnabled = true
            hintOverride = hint
            updateStatus()
        }
    }

    // ---------------------------------------------------------------------
    // UI helper
    // ---------------------------------------------------------------------

    /** Menyesuaikan kartu status dengan keadaan sesi saat ini. */
    private fun updateStatus() {
        val label = Zx68Session.deviceLabel
        if (Zx68Session.isConnected() && label != null) {
            tvStatus.text = getString(R.string.status_connected_short, label)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
            tvHint.text = hintOverride
                ?: "Perintah dikirim lewat USB HID (endpoint 64 byte). " +
                "Selama terhubung, keyboard tidak mengetik ke Android."
        } else {
            tvStatus.text = getString(R.string.status_disconnected)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            tvHint.text = hintOverride ?: getString(R.string.hint_connect)
        }
        btnConnect.isEnabled = !connecting
    }

    /** Menambahkan baris log tanpa memicu penataan ulang seluruh teks. */
    private fun appendLog(line: String) {
        val current = tvLog.text?.toString().orEmpty()
        val merged = if (current.isEmpty()) line else "$current\n$line"
        val lines = merged.split('\n')
        tvLog.text = if (lines.size > 200) lines.takeLast(200).joinToString("\n") else merged
    }
}
