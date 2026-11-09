package id.paradox.zx68.protocol

import id.paradox.zx68.model.LightingSettings
import id.paradox.zx68.usb.UsbHidTransport
import id.paradox.zx68.util.Hex
import id.paradox.zx68.util.LogBus

/**
 * Implementasi perintah tingkat tinggi untuk keyboard ZX68.
 *
 * Semua metode membungkus [M3Frame] + [UsbHidTransport] dan mengembalikan
 * [Result] agar UI bisa menampilkan pesan kesalahan apa adanya.
 *
 * Urutan pakai yang disarankan (sama dengan driver Windows saat start):
 * 1. [handshake] — membuka komunikasi dan membaca info matriks
 * 2. operasi setelan ([setLighting], [writeKeyTable], [changeMode], ...)
 * 3. [apply] — menuliskan konfigurasi ke flash perangkat
 *
 * Catatan: driver Windows mengirim setiap paket hingga **3 kali** bila tidak
 * ada balasan (lihat loop `cmp ebx, 3` pada RVA `0x3bd4`). Perilaku itu
 * direplikasi pada [transactWithRetry].
 */
class Zx68Protocol(private val transport: UsbHidTransport) {

    /** Hasil satu transaksi. */
    data class Result(
        /** `true` bila operasi dianggap berhasil. */
        val ok: Boolean,
        /** Paket balasan mentah, bila perangkat menjawab. */
        val response: ByteArray? = null,
        /** Pesan manusiawi untuk ditampilkan di UI/log. */
        val message: String = "",
        /** `false` bila paket sama sekali tidak bisa dikirim (kabel putus, dll). */
        val sent: Boolean = true,
    )

    /** Percobaan ulang maksimum, sama dengan driver (`cmp ebx, 3`). */
    private val maxAttempts = 3

    // ---------------------------------------------------------------------
    // Transaksi dasar
    // ---------------------------------------------------------------------

    /**
     * Mengirim frame dan (opsional) memeriksa balasan.
     *
     * @param frame     paket 64 byte yang sudah ber-CRC
     * @param expectReply bila `true`, balasan wajib ada agar dianggap sukses
     */
    fun transact(frame: ByteArray, expectReply: Boolean = false): Result {
        var last: ByteArray? = null
        var lastSent = -1

        repeat(maxAttempts) { attempt ->
            val transfer = transport.transact(
                request = frame,
                readTimeoutMs = 400,
                expectReply = expectReply,
            )
            lastSent = transfer.sentBytes
            last = transfer.response

            // Gagal kirim: ulangi seperti driver (maksimal 3 kali).
            if (!transfer.sentOk) {
                LogBus.log("Kirim gagal (attempt ${attempt + 1}/$maxAttempts)")
                return@repeat
            }

            if (!expectReply) {
                // Perintah tulis: cukup paket benar-benar keluar.
                return Result(
                    ok = true,
                    response = transfer.response,
                    message = "terkirim (${transfer.sentBytes} byte)",
                )
            }

            val response = transfer.response
            if (response != null && response.size >= M3Frame.PACKET_SIZE) {
                val cmdOk = (response[0].toInt() and 0xFF) == (frame[0].toInt() and 0xFF)
                val crcOk = M3Frame.isValidResponse(response)
                if (cmdOk && crcOk) {
                    return Result(ok = true, response = response, message = "balasan valid")
                }
                LogBus.log(
                    "Balasan tidak valid (attempt ${attempt + 1}): cmd=$cmdOk crc=$crcOk",
                )
            } else {
                LogBus.log("Tidak ada balasan (attempt ${attempt + 1}/$maxAttempts)")
            }
        }

        return Result(
            ok = false,
            response = last,
            message = if (lastSent < 0) {
                "gagal mengirim ke perangkat (periksa kabel/izin USB)"
            } else {
                "perangkat tidak menjawab"
            },
            sent = lastSent > 0,
        )
    }

    // ---------------------------------------------------------------------
    // Handshake / info perangkat
    // ---------------------------------------------------------------------

    /** Info perangkat hasil pembacaan awal. */
    data class DeviceInfo(
        /** Data mentah blok pertama (9 byte). */
        val capabilityBlock: ByteArray,
        /** Data mentah blok kedua (8 byte, biasanya versi firmware). */
        val versionBlock: ByteArray,
        /** Jumlah baris matriks LED (byte 0 blok kapabilitas). */
        val rows: Int,
        /** Jumlah kolom matriks LED (byte 1 blok kapabilitas). */
        val columns: Int,
    ) {
        /** Jumlah LED = rows * columns (dipakai untuk tabel LED per tombol). */
        val ledCount: Int get() = rows * columns
    }

    /**
     * Membuka komunikasi dan membaca identitas perangkat.
     *
     * Driver Windows melakukannya berurutan di RVA `0x1088`:
     * `cmd 0x01 sub 9` (kapabilitas) → `cmd 0x1b60` (sinkronisasi generik)
     * → `cmd 0x01 sub 8` (versi).
     */
    fun handshake(): Result {
        LogBus.log("Handshake: baca kapabilitas (cmd 0x01 sub 9)")
        val capability = readBlock(address = 0, length = 9)
        if (!capability.sent) {
            // Perangkat benar-benar tidak bisa dikirimi paket.
            return Result(false, capability.response, "handshake gagal: ${capability.message}", sent = false)
        }
        if (!capability.ok) {
            // Paket terkirim tetapi tidak dibalas: sebagian firmware berperilaku
            // begini dan tetap menerima perintah tulis, jadi jangan dianggap fatal.
            LogBus.log("Handshake tanpa balasan: ${capability.message}")
            return Result(
                ok = true,
                response = capability.response,
                message = "terhubung, tetapi perangkat tidak membalas handshake",
            )
        }
        LogBus.log("Handshake: baca versi (cmd 0x01 sub 8)")
        val version = readBlock(address = 0, length = 8)
        return Result(
            ok = true,
            response = capability.response,
            message = "handshake selesai (versi: ${Hex.format(version.response ?: ByteArray(0))})",
        )
    }

    /**
     * Membaca blok memori dari perangkat.
     *
     * @param address alamat 24-bit
     * @param length  jumlah byte yang diminta (maksimum 255)
     */
    fun readBlock(address: Int, length: Int): Result {
        val frame = M3Frame.build(
            command = ProtocolCommands.READ_MEMORY,
            sub = length,
            address = address,
            length = length,
        )
        return transact(frame, expectReply = true)
    }

    // ---------------------------------------------------------------------
    // Pencahayaan
    // ---------------------------------------------------------------------

    /**
     * Mengirim setelan LED tingkat perangkat.
     *
     * Bentuk paket diambil dari tiga fungsi driver:
     *
     * | Command | RVA | Argumen |
     * |---------|-----|---------|
     * | `0x15 sub 1` | `0x2600` | **4 byte** payload: `[model, speed, brightness, direction]` |
     * | `0x15 sub 2` | `0x2750` | 1 byte payload: `[enable]` |
     * | `0x15 sub 3` | `0x28b0` | 1 byte payload: `[color]` |
     *
     * Penting: **byte length (offset 5) tetap 0** untuk ketiga sub ini.
     * Driver hanya mengisi `buf[1]` (sub) dan `buf[8..]` (payload); byte
     * panjang di `buf[5]` tidak pernah ditulis sehingga bernilai 0 setelah
     * buffer dibersihkan. Hanya command data (`0x04`, `0x16`, `0x27`) yang
     * mengisi byte panjang.
     *
     * Fungsi `0x2600` menerima empat argumen (`ret 0x10`) dan menulisnya ke
     * `buf[8..11]`, jadi urutan `LESet` mengikuti urutan yang dipakai driver
     * pada `WriteLE`. Urutan pasti antar field berkeyakinan **sedang** —
     * lihat `docs/PROTOCOL.md` §8 dan gunakan konsol untuk verifikasi.
     */
    fun setLighting(settings: LightingSettings): Result {
        val steps = mutableListOf<Result>()

        steps += send(
            M3Frame.build(
                command = ProtocolCommands.DEVICE_LED,
                sub = 1,
                // length = 0 mengikuti driver: buf[5] tidak pernah diisi.
                length = 0,
                payload = byteArrayOf(
                    settings.model.toByte(),
                    settings.speed.toByte(),
                    settings.brightness.toByte(),
                    settings.direction.toByte(),
                ),
            ),
            "LED model=${settings.model} speed=${settings.speed} " +
                "brightness=${settings.brightness} dir=${settings.direction}",
        )

        steps += send(
            M3Frame.build(
                command = ProtocolCommands.DEVICE_LED,
                sub = 2,
                length = 0,
                payload = byteArrayOf(settings.enable.toByte()),
            ),
            "LED enable=${settings.enable}",
        )

        steps += send(
            M3Frame.build(
                command = ProtocolCommands.DEVICE_LED,
                sub = 3,
                length = 0,
                payload = byteArrayOf(settings.color.toByte()),
            ),
            "LED color=${settings.color}",
        )

        val failed = steps.firstOrNull { !it.ok }
        return failed ?: Result(true, message = "setelan pencahayaan terkirim")
    }

    /**
     * Memilih efek LED lewat `cmd 0x21` (dua parameter).
     *
     * Dipakai driver pada RVA `0x5313` sebelum menulis data efek.
     */
    fun selectLedEffect(model: Int, subModel: Int): Result = send(
        M3Frame.build(
            command = ProtocolCommands.LED_SELECT,
            sub = model,
            length = subModel,
        ),
        "pilih efek model=$model sub=$subModel",
    )

    /**
     * Menulis tabel warna LED per tombol (`cmd 0x16` sub 3).
     *
     * Driver mengirimnya per 56 byte dengan alamat yang naik 0x38, dimulai
     * dari 0 (lihat RVA `0x4c15`–`0x4c64`). Panjang tabel = `rows * columns`
     * dikali jumlah byte per LED (3 byte RGB pada konfigurasi umum).
     */
    fun writeKeyLedTable(data: ByteArray): Result =
        writeChunked(
            data = data,
            command = ProtocolCommands.KEY_TABLE,
            sub = 3,
            label = "tabel LED per tombol",
        )

    // ---------------------------------------------------------------------
    // Keymap
    // ---------------------------------------------------------------------

    /**
     * Menulis tabel keymap (`cmd 0x16` sub 1).
     *
     * @param values `DriverValue` per index tombol (lihat [KeyCodes.encode]);
     *               index array = nomor tombol pada perangkat.
     */
    fun writeKeyTable(values: IntArray): Result {
        val bytes = ByteArray(values.size * 4)
        values.forEachIndexed { index, value ->
            bytes[index * 4] = (value and 0xFF).toByte()
            bytes[index * 4 + 1] = ((value ushr 8) and 0xFF).toByte()
            bytes[index * 4 + 2] = ((value ushr 16) and 0xFF).toByte()
            bytes[index * 4 + 3] = ((value ushr 24) and 0xFF).toByte()
        }
        return writeChunked(
            data = bytes,
            command = ProtocolCommands.KEY_TABLE,
            sub = 1,
            label = "tabel keymap",
        )
    }

    /**
     * Menulis **satu entri** keymap (4 byte) pada alamat `logic * 4`.
     *
     * Dipakai aplikasi agar tombol yang tidak diubah tidak ikut tertimpa —
     * driver Windows sendiri menulis seluruh tabel sekaligus, tetapi untuk
     * porting di ponsel penulisan per tombol lebih aman.
     *
     * @param logic nomor logis tombol (`LogicCode` pada `keymap.js`)
     * @param entry 4 byte `DriverValue` little-endian
     */
    fun writeKeyTablePatch(logic: Int, entry: ByteArray): Result = send(
        M3Frame.build(
            command = ProtocolCommands.KEY_TABLE,
            sub = 1,
            address = logic * 4,
            length = entry.size,
            payload = entry,
        ),
        "keymap logic=$logic @0x%04X".format(logic * 4),
    )

    /**
     * Mengirim data panjang secara bertahap 56 byte.
     *
     * @param addressStart alamat awal di memori perangkat
     */
    private fun writeChunked(
        data: ByteArray,
        command: Int,
        sub: Int,
        label: String,
        addressStart: Int = 0,
    ): Result {
        var address = addressStart
        var offset = 0
        while (offset < data.size) {
            val size = minOf(ProtocolCommands.CHUNK_SIZE, data.size - offset)
            val chunk = data.copyOfRange(offset, offset + size)
            val frame = M3Frame.build(
                command = command,
                sub = sub,
                address = address,
                length = size,
                payload = chunk,
            )
            val result = send(frame, "$label @0x%04X ($size byte)".format(address))
            if (!result.ok) return result
            offset += size
            address += size
        }
        return Result(true, message = "$label terkirim (${data.size} byte)")
    }

    // ---------------------------------------------------------------------
    // Lain-lain
    // ---------------------------------------------------------------------

    /**
     * Mengganti mode/profil aktif (`cmd 0x03 sub 2`).
     *
     * Pembangun paket di RVA `0x21e0` tidak menerima argumen sama sekali
     * (`ret` tanpa operand) dan selalu mengirim `sub = 2` dengan payload nol,
     * jadi nomor profil tidak bisa dipilih lewat perintah ini.
     */
    fun changeMode(): Result = send(
        M3Frame.build(
            command = ProtocolCommands.CHANGE_MODE,
            sub = 2,
        ),
        "ganti mode",
    )

    /**
     * Commit konfigurasi (`cmd 0x17` sub 1, payload `F0 01 12 03`).
     *
     * Tanpa langkah ini sebagian firmware menyimpan setelan hanya di RAM.
     */
    fun apply(): Result = send(
        M3Frame.build(
            command = ProtocolCommands.APPLY,
            sub = 1,
            // Driver (RVA 0x2b50) menulis sub + payload F0 01 12 03 saja;
            // byte length tetap 0.
            length = 0,
            payload = ProtocolCommands.APPLY_PAYLOAD,
        ),
        "apply (commit)",
    )

    /** Menulis blok memori umum (`cmd 0x04`). */
    fun writeBlock(address: Int, data: ByteArray): Result = send(
        M3Frame.build(
            command = ProtocolCommands.WRITE_MEMORY,
            sub = data.size,
            address = address,
            length = data.size,
            payload = data,
        ),
        "tulis blok @0x%04X".format(address),
    )

    /** Menulis blok generik `cmd 0x27` (dipakai penulis profil driver). */
    fun writeGenericBlock(address: Int, data: ByteArray, sub: Int = data.size): Result = send(
        M3Frame.build(
            command = ProtocolCommands.WRITE_BLOCK,
            sub = sub,
            address = address,
            length = data.size,
            payload = data,
        ),
        "tulis generik @0x%04X".format(address),
    )

    /**
     * Mengirim satu frame mentah (dipakai halaman konsol protokol).
     */
    fun sendRaw(frame: ByteArray, expectReply: Boolean = true): Result {
        require(frame.size == M3Frame.PACKET_SIZE) {
            "Frame harus 64 byte, ditemukan ${frame.size}"
        }
        return transact(frame, expectReply = expectReply)
    }

    /** Helper: kirim + log ringkas. */
    private fun send(frame: ByteArray, label: String): Result {
        LogBus.log("→ $label")
        return transact(frame, expectReply = false)
    }
}
