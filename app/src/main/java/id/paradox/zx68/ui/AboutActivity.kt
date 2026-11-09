package id.paradox.zx68.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import id.paradox.zx68.R
import id.paradox.zx68.data.AssetRepository
import id.paradox.zx68.protocol.Crc16Ccitt
import id.paradox.zx68.protocol.DeviceIds
import id.paradox.zx68.protocol.KeyCodes
import id.paradox.zx68.protocol.M3Frame
import id.paradox.zx68.protocol.ProtocolCommands
import id.paradox.zx68.usb.Zx68Session
import id.paradox.zx68.util.Hex

/**
 * Layar informasi perangkat + uji mandiri protokol.
 *
 * Uji mandiri berjalan **tanpa perangkat keras** dan memverifikasi bagian yang
 * paling mudah salah: CRC, susunan frame, parsing respons, encoding keymap,
 * dan pemuatan aset hasil ekstraksi driver.
 */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        val tvDevice = findViewById<TextView>(R.id.tvDevice)
        val tvProtocol = findViewById<TextView>(R.id.tvProtocol)
        val tvTestResult = findViewById<TextView>(R.id.tvTestResult)

        tvDevice.text = buildString {
            appendLine("Perangkat didukung: Paradox / Ghost ZX68")
            appendLine("VID = 0x%04X".format(DeviceIds.VENDOR_ID))
            appendLine("PID = 0x0094 dan 0x00A6 (FWID 0x97220410 / 0x97220411)")
            appendLine("Driver sumber: Ghost ZX68 Driver ver.6.0.0.3")
            appendLine()
            append(
                Zx68Session.deviceLabel?.let { "Terhubung: $it" }
                    ?: "Status: belum terhubung",
            )
        }

        tvProtocol.text = buildString {
            appendLine("frame  = 64 byte")
            appendLine("  [0]    command")
            appendLine("  [1]    sub / length")
            appendLine("  [2..4] address 24-bit LE")
            appendLine("  [5]    length byte")
            appendLine("  [6..7] CRC-16/CCITT-FALSE LE")
            appendLine("  [8..63] payload (56 byte)")
            appendLine()
            appendLine("cmd 0x01 baca memori (sub = panjang)")
            appendLine("cmd 0x03 ganti profil (sub 2)")
            appendLine("cmd 0x04 tulis memori")
            appendLine("cmd 0x0B setelan global/kecerahan")
            appendLine("cmd 0x15 setelan LED perangkat (sub 1-3)")
            appendLine("cmd 0x16 tabel keymap (sub 1) / LED (sub 3)")
            appendLine("cmd 0x17 commit (F0 01 12 03)")
            appendLine("cmd 0x21 pilih efek LED")
            appendLine("cmd 0x22 data efek LED")
            appendLine("cmd 0x27 tulis blok generik")
            appendLine()
            append("Keymap: DriverValue = (0x02<<24) | (usage<<8) | modifier")
        }

        findViewById<MaterialButton>(R.id.btnSelfTest).setOnClickListener {
            tvTestResult.text = runSelfTest()
        }
    }

    /** Menjalankan pemeriksaan internal dan mengembalikan laporan teks. */
    private fun runSelfTest(): String {
        val report = StringBuilder()
        var passed = 0
        var failed = 0

        fun check(name: String, condition: Boolean, detail: String = "") {
            if (condition) {
                passed++
                report.appendLine("PASS  $name${if (detail.isEmpty()) "" else "  ($detail)"}")
            } else {
                failed++
                report.appendLine("FAIL  $name${if (detail.isEmpty()) "" else "  ($detail)"}")
            }
        }

        // 1. CRC-16/CCITT-FALSE check value standar.
        val checkValue = Crc16Ccitt.compute("123456789".toByteArray(Charsets.US_ASCII))
        check(
            "CRC-16/CCITT '123456789' = 0x29B1",
            checkValue == 0x29B1,
            "hasil 0x%04X".format(checkValue),
        )

        // 2. Frame handshake: command 0x01, sub 9, panjang 64 byte, CRC valid.
        val frame = M3Frame.build(ProtocolCommands.READ_MEMORY, sub = 9, length = 9)
        check("Panjang frame 64 byte", frame.size == 64, "${frame.size} byte")
        check("cmd = 0x01", (frame[0].toInt() and 0xFF) == 0x01)
        check("sub = 9", (frame[1].toInt() and 0xFF) == 9)
        check("CRC frame dapat diverifikasi", M3Frame.isValidResponse(frame))

        // 3. Round-trip parse.
        val parsed = M3Frame.parse(frame)
        check(
            "Parse frame (cmd/sub/len)",
            parsed.command == 0x01 && parsed.sub == 9 && parsed.length == 9,
        )

        // 4. Encoding keymap: A tanpa modifier.
        val keyA = KeyCodes.encode(usage = 0x04)
        check(
            "DriverValue tombol A = 0x02000400",
            keyA == 0x02000400,
            "0x%08X".format(keyA),
        )
        check("Decode tombol A", KeyCodes.decode(keyA).label == "A")
        check(
            "Decode LeftCtrl (modifier)",
            KeyCodes.decode(0x02000001).label == "LeftCtrl",
        )

        // 5. Apply payload sesuai driver.
        check(
            "Payload commit F0 01 12 03",
            Hex.format(ProtocolCommands.APPLY_PAYLOAD) == "F0 01 12 03",
        )

        // 6. Aset hasil ekstraksi driver.
        val layout = AssetRepository.loadLayout(this)
        val effects = AssetRepository.loadEffects(this)
        check("Layout tombol dimuat", layout.isNotEmpty(), "${layout.size} tombol")
        check("Daftar efek dimuat", effects.isNotEmpty(), "${effects.size} efek")

        report.appendLine()
        report.appendLine("Ringkasan: $passed PASS, $failed FAIL")
        return report.toString()
    }
}
