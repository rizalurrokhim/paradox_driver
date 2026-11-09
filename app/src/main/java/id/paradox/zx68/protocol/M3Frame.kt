package id.paradox.zx68.protocol

/**
 * Satu paket HID ZX68 ("M3 frame").
 *
 * ## Layout 64 byte (hasil reverse engineering)
 *
 * ```
 * offset  ukuran  isi
 * ------  ------  ---------------------------------------------------------
 *   0       1     command  (lihat [ProtocolCommands])
 *   1       1     sub / length  (arti tergantung command)
 *   2       3     address 24-bit little-endian
 *   5       1     length byte (jumlah data valid / argumen byte)
 *   6       2     CRC-16/CCITT-FALSE little-endian (byte 6 & 7 di-nol-kan dulu)
 *   8      56     payload
 * ------  ------  ---------------------------------------------------------
 * ```
 *
 * Paket selalu dikirim penuh 64 byte (`WriteFile` di Windows, `bulkTransfer`
 * di Android). Ukuran 64 byte berasal dari instruksi `push 0x40` pada
 * RVA `0x3bad` / `0x2355` / `0x3313` di `COR-M3-MKDriver.dll`.
 *
 * Respons perangkat memakai layout yang sama: command dan sub di-echo kembali,
 * lalu CRC di offset 6..7 bisa diverifikasi dengan [isValidResponse].
 */
data class M3Frame(
    /** Command byte (offset 0). */
    val command: Int,
    /** Sub-command/argumen di offset 1. */
    val sub: Int,
    /** Alamat 24-bit little-endian (offset 2..4). */
    val address: Int,
    /** Byte panjang di offset 5. */
    val length: Int,
    /** Isi payload, maksimal [PAYLOAD_SIZE] byte. */
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is M3Frame) return false
        return command == other.command &&
            sub == other.sub &&
            address == other.address &&
            length == other.length &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = command
        result = 31 * result + sub
        result = 31 * result + address
        result = 31 * result + length
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        /** Panjang total satu paket HID. */
        const val PACKET_SIZE = 64

        const val OFFSET_COMMAND = 0
        const val OFFSET_SUB = 1
        const val OFFSET_ADDRESS = 2
        const val OFFSET_LENGTH = 5
        const val OFFSET_CRC = 6
        const val OFFSET_PAYLOAD = 8

        /** Ukuran payload maksimum dalam satu paket (56 byte). */
        const val PAYLOAD_SIZE = PACKET_SIZE - OFFSET_PAYLOAD

        /**
         * Membangun buffer 64 byte siap kirim, lengkap dengan CRC.
         *
         * Contoh:
         * ```
         * val frame = M3Frame.build(
         *     command = ProtocolCommands.READ_MEMORY,
         *     sub = 9,
         *     address = 0,
         * )
         * ```
         *
         * @param command command byte
         * @param sub     sub/argumen byte pertama
         * @param address alamat 24-bit (hanya 24 bit rendah yang dipakai)
         * @param length  isi byte offset 5; nilai negatif berarti memakai
         *                panjang payload yang benar-benar dikirim
         * @param payload data yang ditempel di offset 8 (dipotong ke 56 byte)
         */
        fun build(
            command: Int,
            sub: Int = 0,
            address: Int = 0,
            length: Int = -1,
            payload: ByteArray = ByteArray(0),
        ): ByteArray {
            val payloadLength = minOf(payload.size, PAYLOAD_SIZE)
            val lengthByte = if (length < 0) payloadLength else length

            val buffer = ByteArray(PACKET_SIZE)
            buffer[OFFSET_COMMAND] = (command and 0xFF).toByte()
            buffer[OFFSET_SUB] = (sub and 0xFF).toByte()
            buffer[OFFSET_ADDRESS] = (address and 0xFF).toByte()
            buffer[OFFSET_ADDRESS + 1] = ((address ushr 8) and 0xFF).toByte()
            buffer[OFFSET_ADDRESS + 2] = ((address ushr 16) and 0xFF).toByte()
            buffer[OFFSET_LENGTH] = (lengthByte and 0xFF).toByte()

            if (payloadLength > 0) {
                payload.copyInto(buffer, OFFSET_PAYLOAD, 0, payloadLength)
            }

            // Byte 6 & 7 masih nol di titik ini, sesuai yang diharapkan driver.
            val crc = Crc16Ccitt.compute(buffer)
            buffer[OFFSET_CRC] = (crc and 0xFF).toByte()
            buffer[OFFSET_CRC + 1] = ((crc ushr 8) and 0xFF).toByte()
            return buffer
        }

        /** Membaca paket (request atau response) menjadi [M3Frame]. */
        fun parse(buffer: ByteArray): M3Frame {
            require(buffer.size >= PACKET_SIZE) {
                "Paket harus 64 byte, ditemukan ${buffer.size}"
            }
            val payload = buffer.copyOfRange(OFFSET_PAYLOAD, PACKET_SIZE)
            return M3Frame(
                command = buffer[OFFSET_COMMAND].toInt() and 0xFF,
                sub = buffer[OFFSET_SUB].toInt() and 0xFF,
                address = (buffer[OFFSET_ADDRESS].toInt() and 0xFF) or
                    ((buffer[OFFSET_ADDRESS + 1].toInt() and 0xFF) shl 8) or
                    ((buffer[OFFSET_ADDRESS + 2].toInt() and 0xFF) shl 16),
                length = buffer[OFFSET_LENGTH].toInt() and 0xFF,
                payload = payload,
            )
        }

        /**
         * Memeriksa CRC sebuah respons.
         *
         * CRC dihitung ulang dengan byte CRC di-nol-kan lebih dulu, sama seperti
         * driver (`0x1250` dipanggil setelah byte 6..7 dikosongkan).
         */
        fun isValidResponse(response: ByteArray): Boolean {
            if (response.size < PACKET_SIZE) return false
            val copy = response.copyOf(PACKET_SIZE)
            val expected = (copy[OFFSET_CRC].toInt() and 0xFF) or
                ((copy[OFFSET_CRC + 1].toInt() and 0xFF) shl 8)
            copy[OFFSET_CRC] = 0
            copy[OFFSET_CRC + 1] = 0
            return Crc16Ccitt.compute(copy) == expected
        }
    }
}
