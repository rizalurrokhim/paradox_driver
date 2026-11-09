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
import androidx.core.content.ContextCompat
import id.paradox.zx68.protocol.DeviceIds
import id.paradox.zx68.protocol.M3Frame
import id.paradox.zx68.util.Hex
import id.paradox.zx68.util.LogBus

/**
 * Transport HID di atas **Android USB Host API** (tanpa root).
 *
 * ## Kenapa bukan hidapi
 * Driver Windows `HIDComm.dll` membuka perangkat lewat `CreateFileW` lalu
 * mengirim data dengan `WriteFile` (lihat `?Write@CHIDComm@@UAEJ...` di RVA
 * `0x1370` dan import `KERNEL32!WriteFile`). Di Android, padanannya adalah:
 *
 * 1. `UsbManager.openDevice()` → [UsbDeviceConnection]
 * 2. `claimInterface(iface, force = true)` — `force` akan **melepas driver
 *    kernel** (keyboard berhenti menjadi input sistem) sehingga aplikasi bisa
 *    memakai endpoint-nya, seperti `CreateFileW` di Windows.
 * 3. `bulkTransfer(outEndpoint, ...)` untuk mengirim 64 byte.
 * 4. `bulkTransfer(inEndpoint, ...)` untuk membaca balasan perangkat.
 *
 * Bila perangkat tidak mengekspos interrupt-OUT (beberapa firmware HID hanya
 * punya control pipe), kelas ini otomatis memakai `SET_REPORT` lewat
 * `controlTransfer` — setara dengan `HidD_SetOutputReport` di Windows.
 *
 * ## Izin
 * Android mewajibkan izin runtime per perangkat. [requestPermission] memakai
 * `PendingIntent` + broadcast `ACTION_USB_PERMISSION`.
 */
class UsbHidTransport(private val context: Context) {

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var outEndpoint: UsbEndpoint? = null
    private var inEndpoint: UsbEndpoint? = null

    /** Perangkat yang sedang dibuka, atau null. */
    var device: UsbDevice? = null
        private set

    /** `true` bila koneksi aktif dan antarmuka sudah di-claim. */
    val isOpen: Boolean get() = connection != null

    companion object {
        /** Aksi broadcast hasil permintaan izin USB. */
        const val ACTION_USB_PERMISSION = "id.paradox.zx68.USB_PERMISSION"

        /** Timeout default operasi USB (milidetik). */
        const val DEFAULT_TIMEOUT_MS = 800
    }

    // ---------------------------------------------------------------------
    // Pencarian perangkat
    // ---------------------------------------------------------------------

    /** Semua perangkat USB yang terpasang. */
    fun listDevices(): List<UsbDevice> = usbManager.deviceList.values.toList()

    /**
     * Mencari keyboard ZX68 yang terpasang.
     *
     * @param allowFamily bila `true`, model satu keluarga (GH68/GH21/OPT64)
     *                    juga diterima — berguna untuk eksperimen protokol.
     */
    fun findDevice(allowFamily: Boolean = false): UsbDevice? =
        listDevices().firstOrNull {
            if (allowFamily) {
                DeviceIds.isSameFamily(it.vendorId, it.productId)
            } else {
                DeviceIds.isZx68(it.vendorId, it.productId)
            }
        }

    /** `true` bila izin untuk [device] sudah diberikan. */
    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /**
     * Meminta izin pemakaian perangkat secara asinkron.
     *
     * Callback **selalu** dipanggil pada thread broadcast receiver; UI wajib
     * memindahkannya ke main thread sendiri.
     */
    fun requestPermission(
        device: UsbDevice,
        callback: (granted: Boolean) -> Unit,
    ) {
        if (usbManager.hasPermission(device)) {
            callback(true)
            return
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != ACTION_USB_PERMISSION) return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                try {
                    context.unregisterReceiver(this)
                } catch (_: IllegalArgumentException) {
                    // Sudah dilepas; abaikan.
                }
                LogBus.log(if (granted) "Izin USB diberikan" else "Izin USB ditolak")
                callback(granted)
            }
        }

        // ContextCompat menangani perbedaan API level (flag ekspor wajib di
        // Android 13+, sementara Android 6–7 hanya punya overload lama).
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent, pendingFlags or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        usbManager.requestPermission(device, pendingIntent)
    }

    // ---------------------------------------------------------------------
    // Buka / tutup
    // ---------------------------------------------------------------------

    /**
     * Membuka perangkat, memilih antarmuka yang **punya endpoint OUT**, lalu
     * meng-claim-nya.
     *
     * Keyboard gaming biasanya mengekspos lebih dari satu antarmuka HID:
     * antarmuka 0 adalah keyboard boot (hanya IN), sedangkan antarmuka vendor
     * (1 atau 2) yang memegang endpoint OUT 64 byte untuk protokol konfigurasi.
     * Karena itu antarmuka **tidak** dipilih berdasarkan urutan, melainkan
     * berdasarkan skor endpoint (lihat [scoreInterface]) — memilih antarmuka
     * yang salah membuat semua perintah tulis tidak berpengaruh.
     *
     * @return `true` bila siap mengirim/menerima paket.
     */
    fun open(usbDevice: UsbDevice): Boolean {
        close()

        val conn = usbManager.openDevice(usbDevice) ?: run {
            LogBus.log("Gagal openDevice (izin belum diberikan?)")
            return false
        }

        val candidates = candidateInterfaces(usbDevice)
        if (candidates.isEmpty()) {
            LogBus.log("Perangkat tidak mengekspos antarmuka apa pun")
            conn.close()
            return false
        }
        LogBus.log(
            "Kandidat antarmuka: " +
                candidates.joinToString { "#${it.id}(class ${it.interfaceClass})" },
        )

        // Cadangan bila tidak ada antarmuka yang punya endpoint OUT sama sekali.
        var fallback: Pair<UsbInterface, UsbEndpoint?>? = null

        for (iface in candidates) {
            // force = true -> lepaskan driver kernel (setara CreateFileW).
            val claimed = conn.claimInterface(iface, true)
            LogBus.log(
                "claimInterface(#${iface.id}, force=true) = $claimed " +
                    "(${iface.interfaceClass}/${iface.interfaceSubclass}/${iface.interfaceProtocol})",
            )
            if (!claimed) continue

            val epOut = findEndpoint(iface, UsbConstants.USB_DIR_OUT)
            val epIn = findEndpoint(iface, UsbConstants.USB_DIR_IN)
            LogBus.log(
                "  endpoint OUT=${epOut?.let { describeEndpoint(it) } ?: "-"}, " +
                    "IN=${epIn?.let { describeEndpoint(it) } ?: "-"}",
            )

            if (epOut != null) {
                adopt(conn, iface, epOut, epIn, usbDevice)
                return true
            }

            // Simpan sebagai cadangan (mode SET_REPORT), lalu coba antarmuka lain.
            if (fallback == null) fallback = iface to epIn
            runCatching { conn.releaseInterface(iface) }
        }

        val spare = fallback
        if (spare != null && conn.claimInterface(spare.first, true)) {
            adopt(conn, spare.first, null, spare.second, usbDevice)
            LogBus.log("Tidak ada endpoint OUT; memakai SET_REPORT lewat control transfer")
            return true
        }

        conn.close()
        LogBus.log("Gagal meng-claim antarmuka USB yang bisa dipakai")
        return false
    }

    /** Menyimpan antarmuka + endpoint terpilih sebagai koneksi aktif. */
    private fun adopt(
        conn: UsbDeviceConnection,
        iface: UsbInterface,
        epOut: UsbEndpoint?,
        epIn: UsbEndpoint?,
        usbDevice: UsbDevice,
    ) {
        epOut?.let { warnPacketSize(it) }
        connection = conn
        usbInterface = iface
        outEndpoint = epOut
        inEndpoint = epIn
        device = usbDevice
    }

    /** Melepas antarmuka dan menutup koneksi. */
    fun close() {
        connection?.let { conn ->
            usbInterface?.let { iface ->
                runCatching { conn.releaseInterface(iface) }
                runCatching { conn.setInterface(iface) } // kembalikan ke driver sistem
            }
            runCatching { conn.close() }
        }
        connection = null
        usbInterface = null
        outEndpoint = null
        inEndpoint = null
        device = null
    }

    // ---------------------------------------------------------------------
    // Kirim / terima
    // ---------------------------------------------------------------------

    /**
     * Mengirim satu paket 64 byte.
     *
     * @return jumlah byte terkirim, atau `-1` bila gagal.
     */
    fun send(data: ByteArray, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Int {
        val conn = connection ?: return -1
        val out = outEndpoint

        val sent = if (out != null) {
            conn.bulkTransfer(out, data, data.size, timeoutMs)
        } else {
            // Fallback: control transfer SET_REPORT (interface HID).
            val iface = usbInterface ?: return -1
            conn.controlTransfer(
                0x21,                    // HOST_TO_DEVICE | CLASS | INTERFACE
                0x09,                    // SET_REPORT
                0x0200,                  // Report type OUTPUT (2) << 8 | reportId 0
                iface.id,
                data,
                data.size,
                timeoutMs,
            )
        }
        return if (sent < 0) -1 else sent
    }

    /**
     * Membaca balasan perangkat (maksimal [M3Frame.PACKET_SIZE] byte).
     *
     * @return paket respons, atau `null` bila tidak ada data/timeout.
     */
    fun receive(timeoutMs: Int = DEFAULT_TIMEOUT_MS): ByteArray? {
        val conn = connection ?: return null
        val buffer = ByteArray(M3Frame.PACKET_SIZE)
        val inEp = inEndpoint

        val read = if (inEp != null) {
            conn.bulkTransfer(inEp, buffer, buffer.size, timeoutMs)
        } else {
            val iface = usbInterface ?: return null
            conn.controlTransfer(
                0xA1,                    // DEVICE_TO_HOST | CLASS | INTERFACE
                0x01,                    // GET_REPORT
                0x0100,                  // Report type INPUT (1) << 8 | reportId 0
                iface.id,
                buffer,
                buffer.size,
                timeoutMs,
            )
        }
        return if (read > 0) buffer.copyOf(read) else null
    }

    /**
     * Hasil satu transaksi.
     *
     * Penting untuk membedakan **gagal kirim** (perangkat tidak terhubung /
     * `bulkTransfer` mengembalikan -1) dari **tidak ada balasan** (perintah
     * tulis memang tidak dibalas). Sebelumnya keduanya sama-sama `null`
     * sehingga UI bisa melaporkan "terkirim" padahal paket tidak pernah keluar.
     */
    data class Transfer(
        /** Jumlah byte terkirim; `-1` bila gagal. */
        val sentBytes: Int,
        /** Balasan perangkat bila diminta dan tersedia. */
        val response: ByteArray?,
    ) {
        /** `true` bila paket benar-benar keluar dari perangkat. */
        val sentOk: Boolean get() = sentBytes > 0
    }

    /**
     * Mengirim paket, lalu (opsional) membaca balasan.
     *
     * @param request      paket 64 byte
     * @param readTimeoutMs batas tunggu balasan
     * @param expectReply  `false` untuk perintah tulis: jangan menunggu balasan
     *                     sehingga perintah tidak tertahan 400 ms per paket.
     */
    fun transact(
        request: ByteArray,
        readTimeoutMs: Int = 400,
        expectReply: Boolean = true,
    ): Transfer {
        LogBus.log("TX ${Hex.format(request)}")
        val sent = send(request)
        if (sent < 0) {
            LogBus.log("TX gagal (bulkTransfer/controlTransfer = $sent)")
            return Transfer(sent, null)
        }
        if (!expectReply) return Transfer(sent, null)

        val response = receive(readTimeoutMs)
        if (response != null) {
            LogBus.log("RX ${Hex.format(response)}")
        } else {
            LogBus.log("RX (tidak ada balasan)")
        }
        return Transfer(sent, response)
    }

    // ---------------------------------------------------------------------
    // Helper internal
    // ---------------------------------------------------------------------

    /**
     * Antarmuka terurut dari yang paling mungkin memegang endpoint OUT 64 byte.
     *
     * Skor: interrupt OUT +100, bulk OUT +60, maxPacketSize >= 64 +20,
     * kelas HID +5.
     */
    private fun candidateInterfaces(device: UsbDevice): List<UsbInterface> =
        (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .sortedByDescending { scoreInterface(it) }

    private fun scoreInterface(iface: UsbInterface): Int {
        var score = if (iface.interfaceClass == UsbConstants.USB_CLASS_HID) 5 else 0
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.direction != UsbConstants.USB_DIR_OUT) continue
            score += when (ep.type) {
                UsbConstants.USB_ENDPOINT_XFER_INT -> 100
                UsbConstants.USB_ENDPOINT_XFER_BULK -> 60
                else -> 10
            }
            if (ep.maxPacketSize >= M3Frame.PACKET_SIZE) score += 20
        }
        return score
    }

    /** Memperingatkan bila endpoint tidak mampu membawa paket 64 byte. */
    private fun warnPacketSize(epOut: UsbEndpoint) {
        if (epOut.maxPacketSize < M3Frame.PACKET_SIZE) {
            LogBus.log(
                "Peringatan: maxPacketSize=${epOut.maxPacketSize} < 64; " +
                    "paket bisa terpotong oleh firmware",
            )
        }
    }

    /** Mencari endpoint pertama dengan arah tertentu; prioritaskan interrupt. */
    private fun findEndpoint(iface: UsbInterface, direction: Int): UsbEndpoint? {
        var bulkFallback: UsbEndpoint? = null
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.direction != direction) continue
            when (ep.type) {
                UsbConstants.USB_ENDPOINT_XFER_INT -> return ep
                UsbConstants.USB_ENDPOINT_XFER_BULK -> bulkFallback = ep
            }
        }
        return bulkFallback
    }

    private fun describeEndpoint(ep: UsbEndpoint): String =
        "addr=0x%02X type=%d maxPacket=%d".format(ep.address, ep.type, ep.maxPacketSize)
}
