package id.paradox.zx68.ui

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import id.paradox.zx68.R
import id.paradox.zx68.data.AssetRepository
import id.paradox.zx68.data.KeyLayoutEntry
import id.paradox.zx68.data.ProfileStore
import id.paradox.zx68.protocol.KeyCodes
import id.paradox.zx68.protocol.Zx68Protocol
import id.paradox.zx68.usb.Zx68Session

/**
 * Layar remap tombol.
 *
 * Pengguna mengetuk tombol pada gambar [KeymapView], memilih fungsi baru, lalu
 * menekan "Terapkan ke keyboard". Setiap perubahan dikirim sebagai satu paket
 * `cmd 0x16 sub 1` berisi 4 byte (`DriverValue`) pada alamat `logic * 4` —
 * sama seperti cara driver Windows menulis tabel keymap per 56 byte, tetapi
 * hanya untuk tombol yang berubah sehingga tombol lain tidak tersentuh.
 */
class KeymapActivity : AppCompatActivity() {

    private lateinit var keymapView: KeymapView
    private lateinit var tvSelected: TextView
    private lateinit var tvResult: TextView

    private lateinit var store: ProfileStore
    private lateinit var layout: List<KeyLayoutEntry>
    private var remap = mutableMapOf<Int, Int>()
    private var selected: KeyLayoutEntry? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_keymap)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        store = ProfileStore(this)
        layout = AssetRepository.loadLayout(this)
        remap = store.loadKeyMap().toMutableMap()

        keymapView = findViewById(R.id.keymapView)
        tvSelected = findViewById(R.id.tvSelected)
        tvResult = findViewById(R.id.tvResult)

        keymapView.bind(layout, remap)
        keymapView.onKeySelected = { entry -> onKeyTapped(entry) }

        updateSelectedLabel()

        findViewById<MaterialButton>(R.id.btnPick).setOnClickListener {
            val entry = selected
            if (entry == null) {
                Toast.makeText(this, "Ketuk tombol pada gambar dulu", Toast.LENGTH_SHORT).show()
            } else {
                showFunctionPicker(entry)
            }
        }

        findViewById<MaterialButton>(R.id.btnWrite).setOnClickListener { writeToDevice() }

        findViewById<MaterialButton>(R.id.btnReset).setOnClickListener {
            remap.clear()
            store.saveKeyMap(remap)
            keymapView.bind(layout, remap)
            updateSelectedLabel()
            tvResult.text = "Remap lokal direset. Kirim ke keyboard untuk menimpa di perangkat."
        }
    }

    private fun onKeyTapped(entry: KeyLayoutEntry) {
        selected = entry
        updateSelectedLabel()
    }

    private fun updateSelectedLabel() {
        val entry = selected
        if (entry == null) {
            tvSelected.text = getString(R.string.keymap_hint)
            return
        }
        val current = remap[entry.logic]
        val label = if (current != null) KeyCodes.decode(current).label else entry.show
        tvSelected.text = getString(
            R.string.keymap_selected,
            "${entry.name} (logic ${entry.logic}) → $label",
        )
    }

    /**
     * Dialog pemilihan fungsi tombol.
     *
     * Daftar berisi: kosongkan, 8 tombol modifier, lalu seluruh HID usage yang
     * dikenal beserta nilainya.
     */
    private fun showFunctionPicker(entry: KeyLayoutEntry) {
        data class Option(val label: String, val value: Int)

        val options = mutableListOf<Option>()
        options += Option("— Kosongkan (0x00000000)", 0)
        KeyCodes.MODIFIER_NAMES.forEach { (mask, name) ->
            options += Option("Modifier: $name", KeyCodes.encode(usage = 0, modifiers = mask))
        }
        KeyCodes.SELECTABLE_USAGES.forEach { (usage, name) ->
            options += Option("$name (0x%02X)".format(usage), KeyCodes.encode(usage = usage))
        }

        AlertDialog.Builder(this)
            .setTitle("Fungsi untuk ${entry.show}")
            .setItems(options.map { it.label }.toTypedArray()) { _, which ->
                val chosen = options[which]
                remap[entry.logic] = chosen.value
                store.saveKeyMap(remap)
                keymapView.bind(layout, remap)
                keymapView.select(entry.logic)
                updateSelectedLabel()
                tvResult.text = "Lokal: ${entry.show} → ${chosen.label}\n" +
                    "Tekan “Terapkan ke keyboard” untuk menulis ke perangkat."
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * Menulis perubahan ke perangkat.
     *
     * Hanya entri yang ada di peta remap yang dikirim, masing-masing 4 byte
     * pada alamat `logic * 4` (lihat `docs/PROTOCOL.md`).
     */
    private fun writeToDevice() {
        if (remap.isEmpty()) {
            Toast.makeText(this, "Belum ada perubahan", Toast.LENGTH_SHORT).show()
            return
        }
        if (!Zx68Session.isConnected()) {
            Toast.makeText(this, "Keyboard belum terhubung", Toast.LENGTH_SHORT).show()
            return
        }

        tvResult.text = "Menulis ${remap.size} tombol…"
        Zx68Session.io.execute {
            val protocol = Zx68Session.protocol(this)
            val lines = StringBuilder()

            remap.entries.sortedBy { it.key }.forEach { (logic, value) ->
                val bytes = byteArrayOf(
                    (value and 0xFF).toByte(),
                    ((value ushr 8) and 0xFF).toByte(),
                    ((value ushr 16) and 0xFF).toByte(),
                    ((value ushr 24) and 0xFF).toByte(),
                )
                val result: Zx68Protocol.Result = protocol.writeKeyTablePatch(logic, bytes)
                lines.appendLine(
                    "logic %3d = 0x%08X → %s".format(
                        logic, value, if (result.ok) "OK" else "GAGAL (${result.message})",
                    ),
                )
            }

            val commit = protocol.apply()
            lines.appendLine("commit → ${if (commit.ok) "OK" else "GAGAL"}")

            runOnUiThread { tvResult.text = lines.toString() }
        }
    }
}
