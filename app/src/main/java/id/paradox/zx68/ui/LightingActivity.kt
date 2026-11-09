package id.paradox.zx68.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import id.paradox.zx68.R
import id.paradox.zx68.data.AssetRepository
import id.paradox.zx68.data.ProfileStore
import id.paradox.zx68.model.LightingSettings
import id.paradox.zx68.usb.Zx68Session

/**
 * Layar pengaturan efek LED.
 *
 * Alur:
 * 1. Pilih efek dari daftar yang diekstrak dari driver (`lelist_en.json`).
 * 2. Atur kecerahan (0..18) dan kecepatan (0..3) — batas diambil dari profil
 *    bawaan ZX68 (`LELight = 18`, `LESpeed = 3`).
 * 3. "Kirim ke keyboard" menjalankan `Zx68Protocol.setLighting()`.
 *    "Kirim & simpan" menambahkan `cmd 0x17` (commit) seperti driver Windows.
 */
class LightingActivity : AppCompatActivity() {

    private lateinit var spinnerEffect: Spinner
    private lateinit var spinnerColor: Spinner
    private lateinit var seekBrightness: SeekBar
    private lateinit var seekSpeed: SeekBar
    private lateinit var tvBrightness: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvResult: TextView

    private lateinit var store: ProfileStore
    private lateinit var effects: List<AssetRepository.Effect>
    private var settings = LightingSettings()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lighting)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        store = ProfileStore(this)
        effects = AssetRepository.loadEffects(this)
        settings = store.loadLighting()

        spinnerEffect = findViewById(R.id.spinnerEffect)
        spinnerColor = findViewById(R.id.spinnerColor)
        seekBrightness = findViewById(R.id.seekBrightness)
        seekSpeed = findViewById(R.id.seekSpeed)
        tvBrightness = findViewById(R.id.tvBrightness)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvResult = findViewById(R.id.tvResult)

        setupEffectSpinner()
        setupColorSpinner()
        setupSliders()

        findViewById<MaterialButton>(R.id.btnApply).setOnClickListener { send(commit = false) }
        findViewById<MaterialButton>(R.id.btnApplyCommit).setOnClickListener { send(commit = true) }
    }

    /** Daftar efek: nama + indeks `LEModel` (indeks urutan di daftar efek). */
    private fun setupEffectSpinner() {
        val names = effects.mapIndexed { index, effect -> "${index + 1}. ${effect.name}" }
        spinnerEffect.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            names,
        )

        // Kembalikan pilihan terakhir: 241 = efek RGB, selain itu indeks 1-based.
        val position = indexForModel(settings.model)
        spinnerEffect.setSelection(position)
        spinnerEffect.onItemSelectedListener = SimpleItemSelectedListener { selected ->
            settings = settings.copy(model = modelForEffect(selected))
            tvResult.text = "Efek dipilih: ${effects[selected].name} (model=${settings.model})"
        }
        settings = settings.copy(model = modelForEffect(position))
    }

    /**
     * Menerjemahkan indeks daftar efek menjadi kode `LEModel`.
     *
     * Driver memakai `LEModel = 241` untuk efek RGB yang ada di profil bawaan.
     * Karena pemetaan lengkap GUID -> kode efek berada di data efek biner
     * (`.le`) yang tidak ikut terbaca, aplikasi memakai indeks 1-based di sini
     * dan menyediakan konsol protokol untuk verifikasi lanjutan.
     */
    private fun modelForEffect(position: Int): Int {
        val rgbIndex = effects.indexOfFirst { it.name.contains("RGB", ignoreCase = true) }
        return if (position == rgbIndex) 241 else position + 1
    }

    /** Kebalikan dari [modelForEffect]: kode `LEModel` → posisi di daftar efek. */
    private fun indexForModel(model: Int): Int {
        val rgbIndex = effects.indexOfFirst { it.name.contains("RGB", ignoreCase = true) }
        return if (model == 241 && rgbIndex >= 0) {
            rgbIndex
        } else {
            (model - 1).coerceIn(0, (effects.size - 1).coerceAtLeast(0))
        }
    }

    private fun setupColorSpinner() {
        val colors = listOf(
            "Default (0)", "Merah (1)", "Hijau (2)", "Biru (3)",
            "Kuning (4)", "Ungu (5)", "Cyan (6)", "Putih (7)",
        )
        spinnerColor.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            colors,
        )
        spinnerColor.setSelection(settings.color.coerceIn(0, colors.size - 1))
        spinnerColor.onItemSelectedListener = SimpleItemSelectedListener { position ->
            settings = settings.copy(color = position)
        }
    }

    private fun setupSliders() {
        seekBrightness.max = LightingSettings.MAX_BRIGHTNESS
        seekBrightness.progress = settings.brightness.coerceIn(0, LightingSettings.MAX_BRIGHTNESS)
        seekSpeed.max = LightingSettings.MAX_SPEED
        seekSpeed.progress = settings.speed.coerceIn(0, LightingSettings.MAX_SPEED)
        updateSliderLabels()

        seekBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                settings = settings.copy(brightness = progress)
                updateSliderLabels()
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                settings = settings.copy(speed = progress)
                updateSliderLabels()
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
    }

    private fun updateSliderLabels() {
        tvBrightness.text = "${getString(R.string.label_brightness)}: ${settings.brightness}"
        tvSpeed.text = "${getString(R.string.label_speed)}: ${settings.speed}"
    }

    /** Mengirim setelan (opsional dilanjutkan commit). */
    private fun send(commit: Boolean) {
        if (!Zx68Session.isConnected()) {
            Toast.makeText(this, "Keyboard belum terhubung", Toast.LENGTH_SHORT).show()
            return
        }
        store.saveLighting(settings)
        tvResult.text = "Mengirim…"

        Zx68Session.io.execute {
            val protocol = Zx68Session.protocol(this)
            val result = protocol.setLighting(settings)
            val commitResult = if (commit) protocol.apply() else null

            val summary = buildString {
                appendLine("setLighting: ${if (result.ok) "OK" else "GAGAL"} — ${result.message}")
                if (commitResult != null) {
                    appendLine("commit: ${if (commitResult.ok) "OK" else "GAGAL"} — ${commitResult.message}")
                }
                append("model=${settings.model} bright=${settings.brightness} " +
                    "speed=${settings.speed} color=${settings.color} enable=${settings.enable}")
            }
            runOnUiThread { tvResult.text = summary }
        }
    }

    /** Listener spinner sederhana agar tidak perlu menulis objek anonim berulang. */
    private class SimpleItemSelectedListener(
        private val onSelected: (Int) -> Unit,
    ) : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(
            parent: android.widget.AdapterView<*>?,
            view: android.view.View?,
            position: Int,
            id: Long,
        ) = onSelected(position)

        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }
}
