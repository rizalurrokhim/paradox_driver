package id.paradox.zx68.protocol

/**
 * Daftar command protokol ZX68 (keluarga chip "M3" / `COR-M3-MKDriver.dll`).
 *
 * Setiap konstanta diambil langsung dari instruksi `mov byte ptr [esi], imm8`
 * di dalam DLL driver. Kolom RVA menunjuk ke fungsi pembangun paketnya sehingga
 * hasilnya bisa diverifikasi ulang dengan disassembler.
 *
 * | Command | RVA builder | Dipakai untuk                                   |
 * |---------|-------------|-------------------------------------------------|
 * | `0x01`  | `0x1ef0`/`0x2090` | baca blok memori (sub = panjang)          |
 * | `0x03`  | `0x21e0`    | ganti mode / profil perangkat                   |
 * | `0x04`  | `0x2310`    | tulis blok memori (sub = panjang)               |
 * | `0x0B`  | `0x31b0`    | setelan angka global (mis. kecerahan)           |
 * | `0x15`  | `0x2600`–`0x28b0` | setelan LED perangkat (sub 1/2/3)         |
 * | `0x16`  | `0x29e0`    | tabel keymap (sub 1) & tabel LED per-tombol (sub 3) |
 * | `0x17`  | `0x2b50`    | commit/apply setelah menulis                    |
 * | `0x21`  | `0x32e0`    | pemilih efek LED (2 parameter)                  |
 * | `0x22`  | `0x3410`    | tulis data efek LED per 56 byte                 |
 * | `0x27`  | `0x3af0`    | tulis blok generik (dipakai penulis profil)     |
 *
 * Catatan tingkat keyakinan ada di `docs/PROTOCOL.md`.
 */
object ProtocolCommands {

    /** Baca blok memori. `sub` = jumlah byte yang diminta. */
    const val READ_MEMORY = 0x01

    /** Ganti mode/profil aktif. Driver memakai `sub = 2`. */
    const val CHANGE_MODE = 0x03

    /** Tulis blok memori. `sub` = panjang, `address` = alamat tujuan. */
    const val WRITE_MEMORY = 0x04

    /** Setelan angka global (dipakai untuk kecerahan LED). */
    const val GLOBAL_SETTING = 0x0B

    /** Setelan LED tingkat perangkat. `sub` 1, 2, atau 3. */
    const val DEVICE_LED = 0x15

    /** Tabel keymap (sub 1) dan tabel warna LED per tombol (sub 3). */
    const val KEY_TABLE = 0x16

    /** Commit / terapkan konfigurasi. Payload `F0 01 12 03`. */
    const val APPLY = 0x17

    /** Pemilih efek LED. Parameter: byte 1 dan byte 2. */
    const val LED_SELECT = 0x21

    /** Tulis data efek LED per 56 byte (alamat + panjang di header). */
    const val LED_DATA = 0x22

    /** Tulis blok generik: `sub` = panjang, `address`, byte panjang di offset 5. */
    const val WRITE_BLOCK = 0x27

    /** Payload commit yang dipakai driver (`0x2b50`, sub 1). */
    val APPLY_PAYLOAD = byteArrayOf(0xF0.toByte(), 0x01, 0x12, 0x03)

    /**
     * Ukuran potongan data yang dikirim driver dalam satu paket.
     *
     * Nilai 0x38 (56) muncul sebagai `push 0x38` pada semua loop tulis
     * (`0x58c1`, `0x4be3`, `0x5346`) dan sama dengan ukuran payload frame.
     */
    const val CHUNK_SIZE = 56
}
