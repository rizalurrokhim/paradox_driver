package id.paradox.zx68.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log ringan yang dibagi ke seluruh aplikasi.
 *
 * Semua aktivitas USB/protokol menulis ke sini sehingga pengguna dapat melihat
 * paket apa yang dikirim/diterima — penting saat memverifikasi perintah pada
 * perangkat keras asli.
 *
 * Log disimpan di memori (maksimal [MAX_LINES] baris) dan tidak butuh izin.
 */
object LogBus {

    private const val MAX_LINES = 500

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val lines = ArrayDeque<String>()
    private val listeners = mutableListOf<(String) -> Unit>()

    /** Menambahkan satu baris log. */
    fun log(message: String) {
        val line = "${timeFormat.format(Date())}  $message"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        synchronized(listeners) {
            listeners.toList().forEach { it(line) }
        }
    }

    /** Seluruh isi log (salinan). */
    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    /** Menghapus seluruh log. */
    fun clear() {
        synchronized(lines) { lines.clear() }
    }

    /** Mendaftarkan listener; mengembalikan fungsi untuk melepas listener. */
    fun addListener(listener: (String) -> Unit): () -> Unit {
        synchronized(listeners) { listeners.add(listener) }
        return { synchronized(listeners) { listeners.remove(listener) } }
    }
}
