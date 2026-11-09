package id.paradox.zx68.protocol

/**
 * Identitas USB perangkat yang dikenali driver Windows asli.
 *
 * Data diambil dari `CMSEngine/driver/device/modellist.json` di dalam installer
 * `Ghost ZX68_Driver ver.6.0.0.3.exe`:
 *
 * ```json
 * { "ModelID": 656801940, "FWID": "0x97220410", "Name": "ZX68" }
 * { "ModelID": 656801958, "FWID": "0x97220411", "Name": "ZX68" }
 * ```
 *
 * `ModelID` dikodekan sebagai `(vid << 16) | pid` sehingga:
 * - `656801940` = `0x27260094` → VID `0x2726`, PID `0x0094`
 * - `656801958` = `0x272600A6` → VID `0x2726`, PID `0x00A6`
 */
object DeviceIds {

    /** Vendor ID Paradox/Ghost untuk keluarga keyboard ini. */
    const val VENDOR_ID = 0x2726

    /** PID ZX68 firmware `0x97220410`. */
    const val PID_ZX68_A = 0x0094

    /** PID ZX68 firmware `0x97220411`. */
    const val PID_ZX68_B = 0x00A6

    /** PID model lain dalam keluarga yang sama (bukan ZX68). */
    val SIBLING_PRODUCT_IDS = mapOf(
        0x0031 to "GH68",
        0x005E to "GHOST OPT64",
        0x006B to "GHOST OPT64",
        0x00A4 to "GH21",
    )

    /** Semua PID ZX68 yang dikenali. */
    val ZX68_PRODUCT_IDS = intArrayOf(PID_ZX68_A, PID_ZX68_B)

    /**
     * Mengembalikan `true` bila VID/PID termasuk keyboard ZX68.
     */
    fun isZx68(vendorId: Int, productId: Int): Boolean =
        vendorId == VENDOR_ID && ZX68_PRODUCT_IDS.contains(productId)

    /**
     * Mengembalikan `true` untuk semua produk dalam keluarga chip yang sama
     * (berguna di mode "paksa" saat pengguna ingin bereksperimen).
     */
    fun isSameFamily(vendorId: Int, productId: Int): Boolean =
        vendorId == VENDOR_ID &&
            (ZX68_PRODUCT_IDS.contains(productId) || SIBLING_PRODUCT_IDS.containsKey(productId))

    /** Nama model untuk ditampilkan di UI. */
    fun modelNameFor(productId: Int): String = when (productId) {
        PID_ZX68_A -> "ZX68 (FWID 0x97220410)"
        PID_ZX68_B -> "ZX68 (FWID 0x97220411)"
        else -> SIBLING_PRODUCT_IDS[productId] ?: "Tidak dikenal"
    }
}
