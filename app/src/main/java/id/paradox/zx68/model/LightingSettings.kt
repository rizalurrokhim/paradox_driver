package id.paradox.zx68.model

/**
 * Parameter efek LED, mengikuti struktur `LESet` pada profil driver Windows.
 *
 * Nilai default diambil dari `profile_online_1.json` (profil "RGB渐变" bawaan
 * untuk ZX68):
 *
 * ```json
 * { "LEModel": 241, "LESubModel": 1, "LELight": 18,
 *   "LESpeed": 3, "LEDir": 0, "LEColor": 0, "LEEnable": 15 }
 * ```
 */
data class LightingSettings(
    /** Kode efek (`LEModel`). 241 = RGB gradasi pada profil bawaan. */
    val model: Int = 241,
    /** Sub-model efek (`LESubModel`). */
    val subModel: Int = 1,
    /** Kecerahan LED (`LELight`), 0..18 pada profil bawaan. */
    val brightness: Int = 18,
    /** Kecepatan animasi (`LESpeed`), 0..3 pada profil bawaan. */
    val speed: Int = 3,
    /** Arah animasi (`LEDir`). */
    val direction: Int = 0,
    /** Indeks warna (`LEColor`), bergantung efek. */
    val color: Int = 0,
    /** Mask pilihan LED (`LEEnable`). 15 = semua zona aktif. */
    val enable: Int = 15,
) {
    companion object {
        /** Batas atas kecerahan pada profil bawaan. */
        const val MAX_BRIGHTNESS = 18

        /** Batas atas kecepatan pada profil bawaan. */
        const val MAX_SPEED = 3
    }
}
