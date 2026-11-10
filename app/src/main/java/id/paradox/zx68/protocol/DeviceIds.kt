package id.paradox.zx68.protocol

/**
 * Identitas USB perangkat yang dikenali driver Windows asli, plus ID generik
 * yang terbukti muncul pada unit ZX68 di lapangan.
 *
 * ## Sumber data
 *
 * `CMSEngine/driver/device/modellist.json` di dalam installer
 * `Ghost ZX68_Driver ver.6.0.0.3.exe`:
 *
 * ```json
 * { "ModelID": 656801940, "FWID": "0x97220410", "Name": "ZX68" }
 * { "ModelID": 656801958, "FWID": "0x97220411", "Name": "ZX68" }
 * ```
 *
 * `ModelID` dikodekan sebagai `(vid << 16) | pid`, sehingga:
 * - `656801940` = `0x27260094` → VID `0x2726`, PID `0x0094`
 * - `656801958` = `0x272600A6` → VID `0x2726`, PID `0x00A6`
 *
 * ## Mengapa ada ID "generik"
 *
 * Sebagian unit ZX68 tidak menampilkan VID/PID Paradox saat berada di mode
 * keyboard biasa; yang dilaporkan adalah ID controller-nya, misalnya
 * VID `0x1EA7` (SinoWealth) PID `0x0907`. Perangkat itu tetap keyboard yang
 * sama — kanal konfigurasinya tetap ada sebagai interface HID vendor.
 *
 * Karena itu ID generik di [OBSERVED_DEVICE_IDS] hanya dipakai sebagai
 * **petunjuk tambahan** (menambah skor di `UsbHidTransport`), bukan syarat
 * mutlak. Pencocokan utama dilakukan lewat bentuk interface (endpoint
 * interrupt OUT 64 byte).
 */
object DeviceIds {

    /** Vendor ID Paradox/Ghost untuk keluarga keyboard ini. */
    const val VENDOR_ID = 0x2726

    /** PID ZX68 firmware `0x97220410`. */
    const val PID_ZX68_A = 0x0094

    /** PID ZX68 firmware `0x97220411`. */
    const val PID_ZX68_B = 0x00A6

    /** Semua PID ZX68 yang dikenali driver. */
    val ZX68_PRODUCT_IDS = intArrayOf(PID_ZX68_A, PID_ZX68_B)

    /**
     * VID/PID generik yang pernah teramati pada unit ZX68.
     *
     * `0x1EA7:0x0907` = controller SinoWealth; muncul saat keyboard berjalan
     * di mode keyboard biasa, bukan mode vendor Paradox.
     */
    val OBSERVED_DEVICE_IDS: List<Pair<Int, Int>> = listOf(
        0x1EA7 to 0x0907,
    )

    /** PID model lain dalam keluarga yang sama (bukan ZX68). */
    val SIBLING_PRODUCT_IDS = mapOf(
        0x0031 to "GH68",
        0x005E to "GHOST OPT64",
        0x006B to "GHOST OPT64",
        0x00A4 to "GH21",
    )

    /** Mengembalikan `true` bila VID/PID termasuk PID ZX68 resmi driver. */
    fun isZx68(vendorId: Int, productId: Int): Boolean =
        vendorId == VENDOR_ID && ZX68_PRODUCT_IDS.contains(productId)

    /** Mengembalikan `true` bila VID/PID pernah teramati pada unit ZX68. */
    fun isObservedZx68(vendorId: Int, productId: Int): Boolean =
        OBSERVED_DEVICE_IDS.any { it.first == vendorId && it.second == productId }

    /** Mengembalikan `true` untuk semua produk dalam keluarga chip yang sama. */
    fun isSameFamily(vendorId: Int, productId: Int): Boolean =
        vendorId == VENDOR_ID &&
            (ZX68_PRODUCT_IDS.contains(productId) || SIBLING_PRODUCT_IDS.containsKey(productId))

    /** Nama model untuk ditampilkan di UI. */
    fun modelNameFor(vendorId: Int, productId: Int): String = when {
        isZx68(vendorId, productId) -> "ZX68"
        isObservedZx68(vendorId, productId) ->
            "ZX68 (mode keyboard generik 0x%04X:0x%04X)".format(vendorId, productId)
        SIBLING_PRODUCT_IDS.containsKey(productId) -> SIBLING_PRODUCT_IDS.getValue(productId)
        else -> "Tidak dikenal"
    }
}
