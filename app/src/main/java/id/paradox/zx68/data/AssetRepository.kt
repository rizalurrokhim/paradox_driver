package id.paradox.zx68.data

import android.content.Context
import id.paradox.zx68.util.LogBus
import org.json.JSONArray

/**
 * Membaca aset yang diekstrak dari driver Windows.
 *
 * Aset berada di `app/src/main/assets/`:
 * - `zx68_layout.json`  — daftar tombol + posisi (dari `keymap.js`)
 * - `zx68_effects.json` — daftar efek LED (dari `lelist_en.json`)
 *
 * Keduanya dibaca sekali lalu di-cache di memori.
 */
object AssetRepository {

    /** Satu efek LED dari pustaka driver. */
    data class Effect(
        /** GUID efek pada driver Windows (untuk referensi/silang-cek). */
        val guid: String,
        /** Nama efek yang ditampilkan. */
        val name: String,
    )

    private var layoutCache: List<KeyLayoutEntry>? = null
    private var effectCache: List<Effect>? = null

    /** Daftar tombol ZX68, terurut sesuai file layout. */
    fun loadLayout(context: Context): List<KeyLayoutEntry> {
        layoutCache?.let { return it }
        val json = readAsset(context, "zx68_layout.json")
        val array = JSONArray(json)
        val entries = ArrayList<KeyLayoutEntry>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val rect = obj.getJSONArray("rect")
            entries += KeyLayoutEntry(
                name = obj.getString("name"),
                show = obj.getString("show"),
                logic = obj.getInt("logic"),
                location = obj.getInt("location"),
                left = rect.getInt(0),
                top = rect.getInt(1),
                width = rect.getInt(2),
                height = rect.getInt(3),
            )
        }
        layoutCache = entries
        LogBus.log("Layout dimuat: ${entries.size} tombol")
        return entries
    }

    /** Daftar efek LED yang dikenali driver. */
    fun loadEffects(context: Context): List<Effect> {
        effectCache?.let { return it }
        val json = readAsset(context, "zx68_effects.json")
        val array = JSONArray(json)
        val effects = ArrayList<Effect>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            effects += Effect(guid = obj.getString("guid"), name = obj.getString("name"))
        }
        effectCache = effects
        LogBus.log("Daftar efek dimuat: ${effects.size} efek")
        return effects
    }

    /** Membaca isi aset sebagai teks UTF-8. */
    private fun readAsset(context: Context, name: String): String =
        context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
