package id.paradox.zx68.usb

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.usb.UsbDevice
import android.os.Build
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

    // Aman disimpan statis: transport hanya memegang *application context*
    // (lihat UsbHidTransport), sehingga tidak ada Activity yang bocor.
    @SuppressLint("StaticFieldLeak")
    private var transportInstance: UsbHidTransport? = null

    private var protocolInstance: Zx68Protocol? = null

    /** Deskripsi perangkat yang sedang terhubung (untuk UI). */
    var deviceLabel: String? = null
        private set

    /**
     * Eksekutor tunggal: semua operasi USB berjalan berurutan di thread ini
     * agar tidak ada dua transaksi yang saling menimpa.
     */
    val io = Executors.newSingleThreadExecutor()

    /** Transport (dibuat sekali per proses). */
    fun transport(context: Context): UsbHidTransport {
        val existing = transportInstance
        if (existing != null) return existing
        val created = UsbHidTransport(context.applicationContext)
        transportInstance = created
        return created
    }

    /** Protokol siap pakai. */
    fun protocol(context: Context): Zx68Protocol {
        val existing = protocolInstance
        if (existing != null) return existing
        val created = Zx68Protocol(transport(context))
        protocolInstance = created
        return created
    }

    /** `true` bila koneksi USB aktif. */
    fun isConnected(): Boolean = transportInstance?.isOpen == true

    /**
     * Mencari keyboard, meminta izin bila perlu, lalu membuka koneksi.
     *
     * Fungsi ini harus dipanggil dari thread IO ([io]); callback dijalankan di
     * thread yang sama.
     *
     * @param allowFamily terima juga model sekeluarga (GH68/GH21/OPT64)
     * @return pasangan (perangkat, berhasil)
     */
    fun connect(
        context: Context,
        allowFamily: Boolean = false,
    ): Pair<UsbDevice?, Boolean> {
        val transport = transport(context)
        val device = transport.findDevice(allowFamily)
        if (device == null) {
            LogBus.log(
                "Perangkat tidak ditemukan (VID=0x%04X PID=%s)".format(
                    DeviceIds.VENDOR_ID,
                    DeviceIds.ZX68_PRODUCT_IDS.joinToString("/") { "0x%04X".format(it) },
                ),
            )
            return null to false
        }

        LogBus.log(
            "Ditemukan ${device.productName ?: "USB device"} " +
                "VID=0x%04X PID=0x%04X".format(device.vendorId, device.productId),
        )

        if (!transport.hasPermission(device)) {
            LogBus.log("Meminta izin USB…")
            // Koneksi memerlukan izin; pemanggil harus mengulang setelah dialog.
            return device to false
        }

        val opened = transport.open(device)
        if (opened) {
            deviceLabel = buildLabel(device)
            LogBus.log("Terhubung: $deviceLabel")
        }
        return device to opened
    }

    /** Menutup koneksi aktif. */
    fun disconnect() {
        transportInstance?.close()
        deviceLabel = null
        LogBus.log("Koneksi USB ditutup")
    }

    /** Label ringkas untuk UI. */
    private fun buildLabel(device: UsbDevice): String {
        val model = DeviceIds.modelNameFor(device.productId)
        val name = device.productName ?: "ZX68"
        val sdk = "Android ${Build.VERSION.RELEASE}"
        return "$name · $model · VID 0x%04X PID 0x%04X · $sdk"
            .format(device.vendorId, device.productId)
    }
}
