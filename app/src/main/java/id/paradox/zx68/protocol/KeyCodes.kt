package id.paradox.zx68.protocol

/**
 * Encoding `DriverValue` (4 byte per tombol) dan tabel HID usage.
 *
 * ## Asal encoding
 * Dari `profile_online_1.json` milik driver (profil bawaan ZX68) terlihat pola:
 *
 * ```
 * Index  2 : 0x02000001     -> LeftCtrl
 * Index  3 : 0x02000002     -> LeftShift
 * Index  4 : 0x02000004     -> LeftAlt
 * Index  5 : 0x02000008     -> LeftGui
 * Index 10 : 0x02000400     -> tombol "A" (HID usage 0x04)
 * Index 11 : 0x02000500     -> tombol "B" (HID usage 0x05)
 * Index  1 : 0x02006500     -> tombol "Application/Menu" (HID usage 0x65)
 * ```
 *
 * Jadi nilai 32-bit disusun **little-endian** menjadi:
 *
 * ```
 * byte 0 : modifier mask (bit 0 = LCtrl ... bit 7 = RGui)
 * byte 1 : HID keyboard usage (0x04 = A, 0x28 = Enter, ...)
 * byte 2 : 0x00
 * byte 3 : tipe entri, 0x02 = tombol keyboard biasa
 * ```
 *
 * Driver menuliskannya sebagai `dword` ke tabel 4 byte per index
 * (lihat `WriteDevice` di RVA `0x4b19`–`0x4b74`).
 */
object KeyCodes {

    /** Nilai tipe untuk tombol keyboard biasa. */
    const val TYPE_KEYBOARD = 0x02

    /** Bit modifier pada byte 0. */
    const val MOD_LEFT_CTRL = 0x01
    const val MOD_LEFT_SHIFT = 0x02
    const val MOD_LEFT_ALT = 0x04
    const val MOD_LEFT_GUI = 0x08
    const val MOD_RIGHT_CTRL = 0x10
    const val MOD_RIGHT_SHIFT = 0x20
    const val MOD_RIGHT_ALT = 0x40
    const val MOD_RIGHT_GUI = 0x80

    /** Satu tombol hasil decode `DriverValue`. */
    data class Binding(
        /** HID usage (0 bila hanya modifier). */
        val usage: Int,
        /** Modifier mask. */
        val modifiers: Int,
        /** Tipe entri (default 0x02). */
        val type: Int,
        /** Label siap tampil. */
        val label: String,
    )

    /** Nama modifier tunggal (mask -> label). */
    val MODIFIER_NAMES: Map<Int, String> = linkedMapOf(
        MOD_LEFT_CTRL to "LeftCtrl",
        MOD_LEFT_SHIFT to "LeftShift",
        MOD_LEFT_ALT to "LeftAlt",
        MOD_LEFT_GUI to "LeftGui",
        MOD_RIGHT_CTRL to "RightCtrl",
        MOD_RIGHT_SHIFT to "RightShift",
        MOD_RIGHT_ALT to "RightAlt",
        MOD_RIGHT_GUI to "RightGui",
    )

    /**
     * Tabel HID Keyboard Usage Page (0x07) -> nama.
     *
     * Hanya berisi tombol yang realistis ada di keyboard 65%, ditambah tombol
     * multimedia yang umum. Nama mengikuti `keymap.js` driver agar konsisten
     * dengan aplikasi Windows.
     */
    val USAGE_NAMES: Map<Int, String> = buildMap {
        // Huruf
        put(0x04, "A"); put(0x05, "B"); put(0x06, "C"); put(0x07, "D")
        put(0x08, "E"); put(0x09, "F"); put(0x0A, "G"); put(0x0B, "H")
        put(0x0C, "I"); put(0x0D, "J"); put(0x0E, "K"); put(0x0F, "L")
        put(0x10, "M"); put(0x11, "N"); put(0x12, "O"); put(0x13, "P")
        put(0x14, "Q"); put(0x15, "R"); put(0x16, "S"); put(0x17, "T")
        put(0x18, "U"); put(0x19, "V"); put(0x1A, "W"); put(0x1B, "X")
        put(0x1C, "Y"); put(0x1D, "Z")
        // Angka
        put(0x1E, "1"); put(0x1F, "2"); put(0x20, "3"); put(0x21, "4")
        put(0x22, "5"); put(0x23, "6"); put(0x24, "7"); put(0x25, "8")
        put(0x26, "9"); put(0x27, "0")
        // Kontrol & simbol
        put(0x28, "Enter"); put(0x29, "Esc"); put(0x2A, "Backspace")
        put(0x2B, "Tab"); put(0x2C, "Space"); put(0x2D, "Minus")
        put(0x2E, "Equal"); put(0x2F, "LeftBracket"); put(0x30, "RightBracket")
        put(0x31, "Backslash"); put(0x32, "NonUSHash"); put(0x33, "Semicolon")
        put(0x34, "Quote"); put(0x35, "Grave"); put(0x36, "Comma")
        put(0x37, "Period"); put(0x38, "Slash"); put(0x39, "CapsLock")
        // Fungsi
        put(0x3A, "F1"); put(0x3B, "F2"); put(0x3C, "F3"); put(0x3D, "F4")
        put(0x3E, "F5"); put(0x3F, "F6"); put(0x40, "F7"); put(0x41, "F8")
        put(0x42, "F9"); put(0x43, "F10"); put(0x44, "F11"); put(0x45, "F12")
        // Navigasi
        put(0x46, "PrintScreen"); put(0x47, "ScrollLock"); put(0x48, "Pause")
        put(0x49, "Insert"); put(0x4A, "Home"); put(0x4B, "PageUp")
        put(0x4C, "Delete"); put(0x4D, "End"); put(0x4E, "PageDown")
        put(0x4F, "Right"); put(0x50, "Left"); put(0x51, "Down"); put(0x52, "Up")
        // Numpad (dipakai untuk Fn layer)
        put(0x53, "NumLock"); put(0x54, "NumSlash"); put(0x55, "NumAsterisk")
        put(0x56, "NumMinus"); put(0x57, "NumPlus"); put(0x58, "NumEnter")
        put(0x59, "Num1"); put(0x5A, "Num2"); put(0x5B, "Num3"); put(0x5C, "Num4")
        put(0x5D, "Num5"); put(0x5E, "Num6"); put(0x5F, "Num7"); put(0x60, "Num8")
        put(0x61, "Num9"); put(0x62, "Num0"); put(0x63, "NumDot")
        put(0x64, "NonUSBackslash"); put(0x65, "Application")
        put(0x66, "Power"); put(0x67, "NumEqual")
        put(0x68, "F13"); put(0x69, "F14"); put(0x6A, "F15"); put(0x6B, "F16")
        put(0x6C, "F17"); put(0x6D, "F18"); put(0x6E, "F19"); put(0x6F, "F20")
        put(0x70, "F21"); put(0x71, "F22"); put(0x72, "F23"); put(0x73, "F24")
        put(0x74, "Execute"); put(0x75, "Help"); put(0x76, "Menu")
        put(0x77, "Select"); put(0x78, "Stop"); put(0x79, "Again")
        put(0x7A, "Undo"); put(0x7B, "Cut"); put(0x7C, "Copy"); put(0x7D, "Paste")
        put(0x7E, "Find"); put(0x7F, "Mute"); put(0x80, "VolumeUp")
        put(0x81, "VolumeDown"); put(0x82, "LockingCapsLock")
        put(0x85, "NumComma"); put(0x87, "International1")
        put(0x88, "International2"); put(0x89, "International3")
        put(0x8A, "International4"); put(0x8B, "International5")
        put(0x90, "LANG1"); put(0x91, "LANG2")
        put(0xA8, "MediaPlayPause"); put(0xB0, "MediaNextTrack")
        put(0xB1, "MediaPreviousTrack"); put(0xB2, "MediaStop")
        put(0xB3, "MediaEject"); put(0xB4, "MediaVolumeUp")
        put(0xB5, "MediaVolumeDown"); put(0xB6, "MediaMute")
        put(0xB7, "MediaBrowser"); put(0xC3, "MediaCalculator")
        put(0xE0, "LeftCtrl"); put(0xE1, "LeftShift"); put(0xE2, "LeftAlt")
        put(0xE3, "LeftGui"); put(0xE4, "RightCtrl"); put(0xE5, "RightShift")
        put(0xE6, "RightAlt"); put(0xE7, "RightGui")
    }

    /** Daftar usage terurut untuk ditampilkan pada dialog pemilihan. */
    val SELECTABLE_USAGES: List<Pair<Int, String>> =
        USAGE_NAMES.entries.sortedBy { it.key }.map { it.key to it.value }

    /**
     * Membentuk `DriverValue` 32-bit.
     *
     * @param usage     HID usage (0 untuk tombol modifier murni)
     * @param modifiers mask modifier (lihat `MOD_*`)
     * @param type      tipe entri, default [TYPE_KEYBOARD]
     */
    fun encode(usage: Int, modifiers: Int = 0, type: Int = TYPE_KEYBOARD): Int =
        ((type and 0xFF) shl 24) or
            ((usage and 0xFF) shl 8) or
            (modifiers and 0xFF)

    /** Mengubah `DriverValue` menjadi [Binding]. */
    fun decode(value: Int): Binding {
        val modifiers = value and 0xFF
        val usage = (value ushr 8) and 0xFF
        val type = (value ushr 24) and 0xFF
        return Binding(usage = usage, modifiers = modifiers, type = type, label = labelOf(usage, modifiers))
    }

    /** Label tampilan untuk sebuah binding. */
    fun labelOf(usage: Int, modifiers: Int = 0): String {
        val keyName = USAGE_NAMES[usage] ?: if (usage == 0) "" else "0x%02X".format(usage)
        val modPrefix = MODIFIER_NAMES.entries
            .filter { (mask, _) -> modifiers and mask != 0 }
            .joinToString("+") { it.value }
        return when {
            modPrefix.isNotEmpty() && keyName.isNotEmpty() -> "$modPrefix+$keyName"
            keyName.isNotEmpty() -> keyName
            modPrefix.isNotEmpty() -> modPrefix
            else -> "—"
        }
    }

    /** `true` bila `DriverValue` kosong (tidak diatur). */
    fun isEmptyValue(value: Int): Boolean = value == 0
}
