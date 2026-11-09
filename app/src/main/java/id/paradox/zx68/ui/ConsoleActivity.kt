package id.paradox.zx68.ui

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import id.paradox.zx68.R
import id.paradox.zx68.protocol.Crc16Ccitt
import id.paradox.zx68.protocol.M3Frame
import id.paradox.zx68.protocol.ProtocolCommands
import id.paradox.zx68.usb.Zx68Session
import id.paradox.zx68.util.Hex

/**
 * Konsol protokol: mengirim frame 64 byte apa pun dan menampilkan balasan.
 *
 * Ini adalah alat verifikasi lapangan. Semua command yang dipakai aplikasi
 * (baca memori, keymap, LED, commit) bisa diuji manual di sini — penting
 * karena perilaku firmware dapat berbeda antar revisi ZX68.
 */
class ConsoleActivity : AppCompatActivity() {

    private lateinit var etFrame: TextInputEditText
    private lateinit var tvOutput: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_console)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        etFrame = findViewById(R.id.etFrame)
        tvOutput = findViewById(R.id.tvOutput)

        // Contoh awal: baca 9 byte kapabilitas (handshake driver).
        etFrame.setText(Hex.format(M3Frame.build(ProtocolCommands.READ_MEMORY, sub = 9, length = 9)))

        findViewById<MaterialButton>(R.id.btnBuildCrc).setOnClickListener { fillCrc() }
        findViewById<MaterialButton>(R.id.btnPresetRead).setOnClickListener {
            etFrame.setText(Hex.format(M3Frame.build(ProtocolCommands.READ_MEMORY, sub = 9, length = 9)))
        }
        findViewById<MaterialButton>(R.id.btnSend).setOnClickListener { send(expectReply = true) }
        findViewById<MaterialButton>(R.id.btnSendNoReply).setOnClickListener { send(expectReply = false) }
    }

    /** Menghitung ulang CRC dari isi kotak teks (byte 6..7 diabaikan). */
    private fun fillCrc() {
        try {
            val bytes = Hex.parse(etFrame.text?.toString().orEmpty())
            if (bytes.size != M3Frame.PACKET_SIZE) {
                Toast.makeText(this, "Frame harus 64 byte (${bytes.size} byte)", Toast.LENGTH_SHORT).show()
                return
            }
            val copy = bytes.copyOf()
            copy[M3Frame.OFFSET_CRC] = 0
            copy[M3Frame.OFFSET_CRC + 1] = 0
            val crc = Crc16Ccitt.compute(copy)
            copy[M3Frame.OFFSET_CRC] = (crc and 0xFF).toByte()
            copy[M3Frame.OFFSET_CRC + 1] = ((crc ushr 8) and 0xFF).toByte()
            etFrame.setText(Hex.format(copy))
            tvOutput.text = "CRC dihitung ulang: 0x%04X".format(crc)
        } catch (e: Exception) {
            tvOutput.text = "Format hex tidak valid: ${e.message}"
        }
    }

    /** Mengirim isi kotak teks ke perangkat. */
    private fun send(expectReply: Boolean) {
        if (!Zx68Session.isConnected()) {
            Toast.makeText(this, "Keyboard belum terhubung", Toast.LENGTH_SHORT).show()
            return
        }
        val bytes = try {
            Hex.parse(etFrame.text?.toString().orEmpty())
        } catch (e: Exception) {
            tvOutput.text = "Format hex tidak valid: ${e.message}"
            return
        }
        if (bytes.size != M3Frame.PACKET_SIZE) {
            tvOutput.text = "Frame harus tepat 64 byte, ditemukan ${bytes.size}"
            return
        }

        tvOutput.text = "Mengirim…"
        Zx68Session.io.execute {
            val protocol = Zx68Session.protocol(this)
            val result = protocol.sendRaw(bytes, expectReply = expectReply)
            val parsed = result.response?.let { runCatching { M3Frame.parse(it) }.getOrNull() }

            val text = buildString {
                appendLine("request : ${Hex.format(bytes)}")
                appendLine("terkirim: ${result.ok} (${result.message})")
                if (result.response != null) {
                    appendLine("response: ${Hex.format(result.response)}")
                    appendLine("CRC valid: ${M3Frame.isValidResponse(result.response)}")
                    if (parsed != null) {
                        appendLine(
                            "cmd=0x%02X sub=%d addr=0x%04X len=%d".format(
                                parsed.command, parsed.sub, parsed.address, parsed.length,
                            ),
                        )
                        appendLine("payload : ${Hex.format(parsed.payload)}")
                    }
                } else {
                    append("tidak ada balasan")
                }
            }
            runOnUiThread { tvOutput.text = text }
        }
    }
}
