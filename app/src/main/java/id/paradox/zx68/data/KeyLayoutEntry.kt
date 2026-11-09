package id.paradox.zx68.data

/**
 * Satu tombol pada layout ZX68.
 *
 * Data ini berasal dari `keymap.js` milik driver Windows
 * (`CMSEngine/driver/device/656801940/data/keymap.js`), yang sudah dikonversi
 * ke `assets/zx68_layout.json` oleh skrip di `tools/extract_assets.py`.
 *
 * @param name     nama internal driver (mis. `Escape`)
 * @param show     label yang ditampilkan (mis. `Esc`)
 * @param logic    `LogicCode` — nomor logis tombol pada firmware
 * @param location `LocationCode` — posisi pada matriks
 * @param left     koordinat X pada gambar layout driver
 * @param top      koordinat Y pada gambar layout driver
 * @param width    lebar tombol pada gambar layout driver
 * @param height   tinggi tombol pada gambar layout driver
 */
data class KeyLayoutEntry(
    val name: String,
    val show: String,
    val logic: Int,
    val location: Int,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    /** Lebar relatif terhadap layout (untuk digambar di layar). */
    val right: Int get() = left + width

    /** Tinggi relatif terhadap layout. */
    val bottom: Int get() = top + height
}
