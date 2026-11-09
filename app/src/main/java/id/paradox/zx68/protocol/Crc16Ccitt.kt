package id.paradox.zx68.protocol

/**
 * CRC-16/CCITT-FALSE — checksum yang dipakai driver Windows ZX68.
 *
 * ## Asal algoritma
 * Di `COR-M3-MKDriver.dll` fungsi CRC ada di RVA `0x1250` (inline, tanpa tabel
 * di luar) dan pencetak CRC ke paket ada di RVA `0x1780`:
 *
 * ```asm
 * mov eax, 0xffff          ; nilai awal
 * mov edx, esi             ; pointer buffer 64 byte
 * mov ecx, 0x40            ; 64 byte
 * loop:
 *   movzx ebp, byte ptr [edx]
 *   mov   edi, eax
 *   shr   edi, 8
 *   xor   edi, ebp
 *   shl   eax, 8
 *   and   edi, 0xff
 *   xor   ax, word ptr [edi*2 + 0x1001a040]   ; tabel 256 word
 * ```
 *
 * Parameter algoritma:
 * - polynomial : `0x1021`
 * - initial    : `0xFFFF`
 * - refleksi   : tidak (MSB-first, `refin=false`, `refout=false`)
 * - xorout     : `0x0000`
 *
 * Dikenal juga sebagai **CRC-16/CCITT-FALSE** dengan check value
 * `0x29B1` untuk string `"123456789"` (dipakai unit test aplikasi).
 *
 * Sebelum menghitung, byte CRC di paket (offset 6 dan 7) **harus** sudah nol —
 * driver melakukannya lewat `mov byte ptr [esi+6], 0` / `[esi+7], 0`.
 */
object Crc16Ccitt {

    /** Nilai awal CRC sesuai driver (`0xFFFF`). */
    const val INITIAL = 0xFFFF

    /** Polynomial CCITT. */
    private const val POLYNOMIAL = 0x1021

    /**
     * Tabel 256 entri, dibangun ulang secara identik dengan tabel driver
     * (yang tertanam di `.data` RVA `0x1a040`).
     */
    private val TABLE: IntArray = IntArray(256) { index ->
        var crc = index shl 8
        repeat(8) {
            crc = if (crc and 0x8000 != 0) {
                (crc shl 1) xor POLYNOMIAL
            } else {
                crc shl 1
            }
            crc = crc and 0xFFFF
        }
        crc
    }

    /**
     * Menghitung CRC-16/CCITT-FALSE.
     *
     * @param data     buffer yang dihitung
     * @param from     indeks awal (inklusif)
     * @param to       indeks akhir (eksklusif)
     * @param initial  nilai awal CRC; driver selalu memakai [INITIAL]
     * @return CRC dalam bentuk `Int` 16-bit (0..0xFFFF)
     */
    fun compute(
        data: ByteArray,
        from: Int = 0,
        to: Int = data.size,
        initial: Int = INITIAL,
    ): Int {
        var crc = initial and 0xFFFF
        for (i in from until to) {
            val index = ((crc ushr 8) xor (data[i].toInt() and 0xFF)) and 0xFF
            crc = ((crc shl 8) and 0xFFFF) xor TABLE[index]
        }
        return crc and 0xFFFF
    }

    /** Versi praktis untuk potongan byte tunggal (mis. payload). */
    fun compute(data: ByteArray): Int = compute(data, 0, data.size)
}
