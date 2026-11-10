package id.paradox.zx68.usb

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.usb.UsbDevice
import id.paradox.zx68.protocol.DeviceIds
import id.paradox.zx68.protocol.Zx68Protocol
import id.paradox.zx68.util.LogBus
import java.util.concurrent.Executors

/**
 * Sesi tunggal koneksi keyboard untuk seluruh aktivitas.
 *
 * Android hanya mengizinkan satu pemilik `UsbDeviceConnection` per perangkat,
 * jadi koneksi dibuka sekali di sini lalu dipakai bersama oleh semua layar
 * (pencahayaan, remap tombol, konsol).
 */
object Zx68Session {

    // Aman disimpan statis: transport hanya memegang application context,
    // sehingga tidak ada Activity yang bocor.
    @SuppressLint("StaticFieldLeak")
    private var transportInstance: UsbHidTransport? = null

    private var protocolInstance: Zx68Protocol? = null

    /** Label perangkat yang sedang terhubung (untuk UI). */
    var deviceLabel: String? = null
        private set

    /**
     * Eksekutor tunggal: semua operasi USB berjalan berurutan di thread ini
     * agar tidak ada dua transaksi yang saling menimpa.
     */
    val io = Executors.newSingleThreadExecutor()

    /** Transport (dibuat sekali per proses). */
    fun transport(context: Context): UsbHidTransport {
        transportInstance?.let { return it }
        val created = UsbHidTransport(context.applicationContext)
        transportInstance = created
        return created
    }

    /** Protokol siap pakai. */
    fun protocol(context: Context): Zx68Protocol {
        protocolInstance?.let { return it }
        val created = Zx68Protocol(transport(context))
        protocolInstance = created
        return created
    }

    /** `true` bila koneksi USB aktif. */
    fun isConnected(): Boolean = transportInstance?.isOpen == true

    /** Daftar perangkat yang bisa dipilih manual, paling layak lebih dulu. */
    fun candidates(context: Context): List<UsbHidTransport.Candidate> =
        transport(context).candidates()

    /** Hasil percobaan membuka koneksi. */
    sealed class ConnectResult {
        /** Koneksi terbuka. */
        data class Success(val label: String) : ConnectResult()

        /** Izin USB belum ada; dialog sudah diminta. */
        data object NeedPermission : ConnectResult()

        /** Tidak ada perangkat yang layak. */
        data class NotFound(val devices: List<String>) : ConnectResult()

        /** Perangkat ditemukan tetapi antarmuka gagal dibuka. */
        data class Failed(val label: String) : ConnectResult()
    }

    /**
     * Membuka koneksi ke [device] (atau perangkat terbaik bila `null`).
     *
     * Harus dipanggil dari thread [io]. Bila izin belum diberikan, fungsi ini
     * hanya mengembalikan [ConnectResult.NeedPermission]; pemanggil yang
     * mengurus dialog izin lalu memanggil ulang.
     */
    fun connect(context: Context, device: UsbDevice? = null): ConnectResult {
        val transport = transport(context)
        val target = device ?: transport.findDevice()
        if (target == null) {
            val seen = transport.listDevices().map { transport.describe(it) }
            LogBus.log("Tidak ada perangkat yang cocok. Terlihat: ${seen.size} perangkat")
            seen.forEach { LogBus.log("  · $it") }
            return ConnectResult.NotFound(seen)
        }

        if (!transport.hasPermission(target)) {
            LogBus.log("Butuh izin USB untuk ${transport.describe(target)}")
            return ConnectResult.NeedPermission
        }

        if (!transport.open(target)) {
            return ConnectResult.Failed(transport.describe(target))
        }

        val label = buildString {
            append(target.productName ?: "ZX68")
            append(" · ")
            append(DeviceIds.modelNameFor(target.vendorId, target.productId))
        }
        deviceLabel = label
        LogBus.log("Terhubung: $label")
        return ConnectResult.Success(label)
    }

    /** Menutup koneksi aktif. */
    fun disconnect() {
        transportInstance?.close()
        deviceLabel = null
        LogBus.log("Koneksi USB ditutup")
    }
}
