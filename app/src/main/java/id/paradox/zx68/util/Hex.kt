package id.paradox.zx68.util

/** Utilitas konversi byte <-> teks heksadesimal. */
object Hex {

    /** `0x0A 0x1B ...` atau `0A1B...` (dengan/tanpa spasi) menjadi byte[]. */
    fun parse(text: String): ByteArray {
        val cleaned = text.replace(Regex("0[xX]"), "").replace(Regex("[\\s,:;-]"), "")
        require(cleaned.length % 2 == 0) { "Jumlah digit hex harus genap (${cleaned.length})" }
        return ByteArray(cleaned.length / 2) { i ->
            cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** Byte array menjadi `0A 1B 2C`. */
    fun format(data: ByteArray, from: Int = 0, to: Int = data.size): String =
        (from until to).joinToString(" ") { "%02X".format(data[it]) }

    /** Byte array menjadi teks hex tanpa spasi (untuk pengiriman cepat). */
    fun compact(data: ByteArray): String = data.joinToString("") { "%02X".format(it) }
}
