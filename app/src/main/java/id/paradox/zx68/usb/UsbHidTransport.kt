package id.paradox.zx68.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import id.paradox.zx68.protocol.DeviceIds
import id.paradox.zx68.util.LogBus

/**
 * Transport USB HID untuk keyboard Paradox/Ghost ZX68.
 *
 * ## Mengapa pencocokan tidak memakai VID/PID saja
 *
 * Driver Windows mencari perangkat lewat `modellist.json` yang hanya berisi
 * VID `0x2726` (PID `0x0094` dan `0x00A6`). Kenyataannya, pada banyak unit
 * ZX68 firmware menampilkan identitas **generik controller**-nya saat berada
 * di mode keyboard biasa — misalnya VID `0x1EA7` (SinoWealth) PID `0x0907`.
 * Perangkat itu tetap keyboard yang sama; yang berbeda hanya ID yang
 * dilaporkan ke host.
 *
 * Karena itu pencarian di sini memberi skor pada setiap perangkat yang
 * terpasang, dan memilih yang paling mungkin menyediakan **kanal konfigurasi
 * vendor**: interface HID (class 3, subclass 0, protocol 0) yang memiliki
 * endpoint interrupt OUT berukuran 64 byte. VID/PID yang dikenal hanya
 * menambah skor, bukan syarat mutlak — sehingga keyboard dengan ID generik
 * tetap bisa dibuka, dan perangkat yang jelas-jelas keyboard biasa
 * (interface boot subclass 1 protocol 1 saja) tidak dipilih.
 *
 * Pengguna juga bisa memilih perangkat secara manual lewat [candidates].
 */
class UsbHidTransport(private val context: Context) {

    /** Hasil satu transfer: bedakan "gagal kirim" dari "tidak ada balasan". */
    data class Transfer(
        /** `true` bila paket benar-benar keluar ke endpoint. */
        val sentOk: Boolean,
        /** Jumlah byte yang terkirim, atau `-1` bila gagal. */
        val sentBytes: Int,
        /** Balasan 64 byte bila ada. */
        val response: ByteArray?,
    )

    /** Satu perangkat beserta skor kelayakannya (untuk pemilih manual). */
    data class Candidate(
        val device: UsbDevice,
        val score: Int,
        val label: String,
    )

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var outEndpoint: UsbEndpoint? = null
    private var inEndpoint: UsbEndpoint? = null
    private var permissionReceiver: BroadcastReceiver? = null

    /** Nama perangkat yang sedang terbuka. */
    var openedDeviceLabel: String? = null
        private set

    /** `true` bila ada sesi aktif. */
    val isOpen: Boolean
        get() = connection != null && outEndpoint != null

    // ---------------------------------------------------------------------
    // Pemindaian perangkat
    // ---------------------------------------------------------------------

    /** Semua perangkat USB yang saat ini terpasang. */
    fun listDevices(): List<UsbDevice> =
        usbManager.deviceList.values.toList()

    /**
     * Deskripsi ringkas satu perangkat untuk log/pemilih: `VID/PID · nama`.
     */
    fun describe(device: UsbDevice): String {
        val vid = "%04X".format(device.vendorId)
        val pid = "%04X".format(device.productId)
        val name = device.productName ?: "USB device"
        return "VID 0x$vid PID 0x$pid · $name"
    }

    /**
     * Memilih perangkat paling mungkin sebagai keyboard ZX68.
     *
     * @param allowFamily bila `true`, perangkat tanpa ciri kanal vendor tetap
     *                    boleh dipilih (mode eksperimen).
     * @return perangkat terpilih, atau `null` bila tidak ada yang layak.
     */
    fun findDevice(allowFamily: Boolean = false): UsbDevice? {
        val ranked = candidates()
        if (ranked.isNotEmpty()) {
            val best = ranked.first()
            LogBus.log("Perangkat terpilih: ${best.label} (skor ${best.score})")
            return best.device
        }
        if (allowFamily) {
            val any = listDevices().firstOrNull()
            if (any != null) LogBus.log("Mode eksperimen: memakai ${describe(any)}")
            return any
        }
        return null
    }

    /**
     * Semua perangkat yang terpasang, diurutkan dari yang paling layak.
     *
     * Skor dihitung dari ciri kanal konfigurasi (lihat dokumentasi kelas).
     * Dipakai juga oleh UI untuk menampilkan daftar pilihan manual bila
     * deteksi otomatis memilih perangkat yang salah.
     */
    fun candidates(): List<Candidate> {
        val result = ArrayList<Candidate>()
        for (device in listDevices()) {
            val score = scoreOf(device)
            if (score > 0) {
                result += Candidate(device, score, "${describe(device)} · skor $score")
            }
        }
        result.sortByDescending { it.score }
        return result
    }

    /**
     * Menghitung kelayakan satu perangkat.
     *
     * Rincian bobot:
     * - `+100` VID/PID ZX68 yang dikenal (`0x2726`)
     * - `+55`  VID/PID yang pernah teramati pada unit ZX68 (lihat [DeviceIds])
     * - `+45`  punya interface HID vendor (class 3, subclass 0, protocol 0)
     *   dengan endpoint interrupt OUT 64 byte — ini kanal perintah driver
     * - `+15`  minimal punya satu endpoint OUT
     * - `+10`  punya endpoint IN (balasan)
     * - `-30`  hanya interface keyboard boot (subclass 1/protocol 1)
     */
    private fun scoreOf(device: UsbDevice): Int {
        var score = 0
        if (device.vendorId == DeviceIds.VENDOR_ID &&
            DeviceIds.ZX68_PRODUCT_IDS.contains(device.productId)
        ) {
            score += 100
        } else if (DeviceIds.isObservedZx68(device.vendorId, device.productId)) {
            score += 55
        }

        var hasVendorChannel = false
        var hasAnyOut = false
        var hasAnyIn = false
        var onlyBootKeyboard = true

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val isHid = iface.interfaceClass == UsbConstants.USB_CLASS_HID
            val isBootKeyboard = isHid &&
                iface.interfaceSubclass == 1 &&
                iface.interfaceProtocol == 1
            if (!isBootKeyboard) onlyBootKeyboard = false

            var out64 = false
            var outAny = false
            var inAny = false
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                val isOut = ep.direction == UsbConstants.USB_DIR_OUT
                if (isOut) {
                    outAny = true
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                        ep.maxPacketSize >= 64
                    ) {
                        out64 = true
                    }
                } else {
                    inAny = true
                }
            }
            hasAnyOut = hasAnyOut || outAny
            hasAnyIn = hasAnyIn || inAny

            if (isHid && iface.interfaceSubclass == 0 &&
                iface.interfaceProtocol == 0 && out64
            ) {
                hasVendorChannel = true
            }
        }

        if (hasVendorChannel) score += 45
        if (hasAnyOut) score += 15
        if (hasAnyIn) score += 10
        if (onlyBootKeyboard) score -= 30
        return score
    }

    // ---------------------------------------------------------------------
    // Izin USB
    // ---------------------------------------------------------------------

    /** Apakah aplikasi sudah punya izin untuk perangkat ini. */
    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /**
     * Meminta izin USB.
     *
     * Android mengirim hasilnya lewat broadcast milik aplikasi ini, sehingga
     * `PendingIntent` harus memakai `FLAG_MUTABLE` (wajib di Android 12+ agar
     * sistem bisa mengisi extra hasil). Callback dipanggil sekali dengan hasil
     * akhir, baik dari broadcast maupun dari pemeriksaan `hasPermission()`.
     *
     * @param onResult dijalankan di main thread
     */
    fun requestPermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
        releasePermissionReceiver()

        val action = "${context.packageName}.USB_PERMISSION"
        val filter = IntentFilter(action)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != action) return
                val granted = intent.getBooleanExtra(EXTRA_GRANTED, false)
                LogBus.log("Broadcast izin USB: ${if (granted) "diberikan" else "ditolak"}")
                releasePermissionReceiver()
                onResult(granted)
            }
        }
        permissionReceiver = receiver

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val pending = PendingIntent.getBroadcast(
            context,
            0,
            Intent(action).setPackage(context.packageName),
            flags,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        LogBus.log("Meminta izin USB untuk ${describe(device)}")
        usbManager.requestPermission(device, pending)
    }

    /** Melepas receiver izin bila masih terpasang (mencegah kebocoran). */
    fun releasePermissionReceiver() {
        permissionReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        permissionReceiver = null
    }

    // ---------------------------------------------------------------------
    // Membuka & menutup sesi
    // ---------------------------------------------------------------------

    /**
     * Membuka perangkat dan meng-claim interface yang punya endpoint OUT.
     *
     * Interface dicoba dari yang paling cocok (HID vendor + OUT 64 byte).
     * `claimInterface(..., force = true)` diperlukan agar driver keyboard
     * kernel Android melepaskan antarmuka tersebut.
     *
     * @return `true` bila interface berhasil di-claim dan endpoint OUT ditemukan
     */
    fun open(device: UsbDevice): Boolean {
        close()

        val conn = usbManager.openDevice(device)
        if (conn == null) {
            LogBus.log("openDevice() = null (izin belum diberikan?)")
            return false
        }

        val order = (0 until device.interfaceCount).sortedByDescending { scoreInterface(device, it) }
        for (index in order) {
            val iface = device.getInterface(index)
            if (!conn.claimInterface(iface, true)) {
                LogBus.log("claimInterface gagal: iface $index")
                continue
            }

            val out = pickOutEndpoint(iface)
            if (out == null) {
                LogBus.log("iface $index tanpa endpoint OUT, dilewati")
                conn.releaseInterface(iface)
                continue
            }

            connection = conn
            claimedInterface = iface
            outEndpoint = out
            inEndpoint = pickInEndpoint(iface)
            openedDeviceLabel = buildString {
                append(device.productName ?: "ZX68")
                append(" · VID 0x").append("%04X".format(device.vendorId))
                append(" PID 0x").append("%04X".format(device.productId))
            }
            LogBus.log(
                "Terbuka: iface $index " +
                    "(class ${iface.interfaceClass}, subclass ${iface.interfaceSubclass}), " +
                    "OUT=0x%02X/${out.type}/${out.maxPacketSize}B, " +
                    "IN=${inEndpoint?.let { "0x%02X".format(it.address) } ?: "-"}",
            )
            return true
        }

        conn.close()
        LogBus.log("Tidak ada interface dengan endpoint OUT pada ${describe(device)}")
        return false
    }

    /** Bobot satu interface; dipakai untuk menentukan urutan percobaan claim. */
    private fun scoreInterface(device: UsbDevice, index: Int): Int {
        val iface = device.getInterface(index)
        var score = 0
        if (iface.interfaceClass == UsbConstants.USB_CLASS_HID) {
            score += 10
            if (iface.interfaceSubclass == 0 && iface.interfaceProtocol == 0) score += 20
            if (iface.interfaceSubclass == 1 && iface.interfaceProtocol == 1) score -= 15
        }
        for (e in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(e)
            val isOut = ep.direction == UsbConstants.USB_DIR_OUT
            if (isOut) {
                score += 10
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) score += 20
                if (ep.maxPacketSize >= 64) score += 20
            }
        }
        return score
    }

    /** Endpoint kirim: interrupt OUT 64 byte lebih dulu, lalu bulk OUT. */
    private fun pickOutEndpoint(iface: UsbInterface): UsbEndpoint? {
        var best: UsbEndpoint? = null
        var bestScore = -1
        for (e in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(e)
            if (ep.direction != UsbConstants.USB_DIR_OUT) continue
            var score = 1
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) score += 10
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) score += 5
            if (ep.maxPacketSize >= 64) score += 5
            if (score > bestScore) {
                bestScore = score
                best = ep
            }
        }
        return best
    }

    /** Endpoint balasan: interrupt IN lebih dulu, lalu bulk IN. */
    private fun pickInEndpoint(iface: UsbInterface): UsbEndpoint? {
        var best: UsbEndpoint? = null
        var bestScore = -1
        for (e in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(e)
            if (ep.direction != UsbConstants.USB_DIR_IN) continue
            var score = 1
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_INT) score += 10
            if (ep.maxPacketSize >= 64) score += 5
            if (score > bestScore) {
                bestScore = score
                best = ep
            }
        }
        return best
    }

    /** Menutup sesi aktif; aman dipanggil berkali-kali. */
    fun close() {
        val conn = connection
        val iface = claimedInterface
        if (conn != null) {
            iface?.let { runCatching { conn.releaseInterface(it) } }
            runCatching { conn.close() }
        }
        connection = null
        claimedInterface = null
        outEndpoint = null
        inEndpoint = null
        openedDeviceLabel = null
    }

    // ---------------------------------------------------------------------
    // Transfer
    // ---------------------------------------------------------------------

    /**
     * Mengirim satu paket 64 byte, lalu (opsional) membaca balasan.
     *
     * @param request        paket 64 byte ber-CRC
     * @param readTimeoutMs  batas tunggu balasan
     * @param expectReply    `false` untuk perintah tulis yang tidak dibalas
     */
    fun transact(
        request: ByteArray,
        readTimeoutMs: Int = 400,
        expectReply: Boolean = false,
    ): Transfer {
        val conn = connection
        val out = outEndpoint
        if (conn == null || out == null) {
            return Transfer(sentOk = false, sentBytes = -1, response = null)
        }

        val sent = conn.bulkTransfer(out, request, request.size, WRITE_TIMEOUT_MS)
        if (sent < 0) {
            LogBus.log("TX gagal (${request.size} byte) ke endpoint 0x%02X".format(out.address))
            return Transfer(sentOk = false, sentBytes = sent, response = null)
        }
        LogBus.log("TX ${request.size}B: ${preview(request)}")

        if (!expectReply) {
            return Transfer(sentOk = true, sentBytes = sent, response = null)
        }

        val buffer = ByteArray(PACKET_SIZE)
        var total = 0
        if (inEndpoint != null) {
            total = conn.bulkTransfer(inEndpoint, buffer, buffer.size, readTimeoutMs)
        }
        if (total <= 0) {
            // Sebagian firmware membalas lewat control transfer (GET_REPORT).
            val asked = conn.controlTransfer(0xA1, 0x01, 0, claimedInterface?.id ?: 0, buffer, buffer.size, readTimeoutMs)
            total = if (asked > 0) asked else 0
        }

        if (total > 0) {
            LogBus.log("RX ${total}B: ${preview(buffer)}")
            return Transfer(sentOk = true, sentBytes = sent, response = buffer.copyOf(total))
        }
        return Transfer(sentOk = true, sentBytes = sent, response = null)
    }

    /** Cuplikan hex singkat untuk log (maksimum 16 byte pertama). */
    private fun preview(bytes: ByteArray): String =
        bytes.take(16).joinToString(" ") { "%02X".format(it) } +
            if (bytes.size > 16) " …" else ""

    private companion object {
        /** Ukuran paket protokol driver (selalu 64 byte). */
        const val PACKET_SIZE = 64

        /** Batas tunggu pengiriman; perangkat HID menolak bila terlalu lama. */
        const val WRITE_TIMEOUT_MS = 1000

        /** Extra hasil dialog izin USB. */
        const val EXTRA_GRANTED = "permission_granted"
    }
}
