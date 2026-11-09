package id.paradox.zx68.data

import android.content.Context
import id.paradox.zx68.model.LightingSettings
import id.paradox.zx68.protocol.KeyCodes
import id.paradox.zx68.util.LogBus
import org.json.JSONObject
import java.io.File

/**
 * Menyimpan setelan terakhir (keymap + pencahayaan) sebagai JSON di
 * `filesDir/zx68_settings.json`.
 *
 * Data ini hanya salinan lokal supaya UI tetap konsisten antar sesi; sumber
 * kebenaran tetap perangkat keras. Karena itu tidak ada I/O jaringan/perizinan.
 */
class ProfileStore(context: Context) {

    private val file = File(context.filesDir, "zx68_settings.json")

    /** Setelan pencahayaan tersimpan. */
    fun loadLighting(): LightingSettings {
        val root = readRoot() ?: return LightingSettings()
        val obj = root.optJSONObject("lighting") ?: return LightingSettings()
        val defaults = LightingSettings()
        return LightingSettings(
            model = obj.optInt("model", defaults.model),
            subModel = obj.optInt("subModel", defaults.subModel),
            brightness = obj.optInt("brightness", defaults.brightness),
            speed = obj.optInt("speed", defaults.speed),
            direction = obj.optInt("direction", defaults.direction),
            color = obj.optInt("color", defaults.color),
            enable = obj.optInt("enable", defaults.enable),
        )
    }

    /** Menyimpan setelan pencahayaan. */
    fun saveLighting(settings: LightingSettings) {
        val root = readRoot() ?: JSONObject()
        root.put(
            "lighting",
            JSONObject()
                .put("model", settings.model)
                .put("subModel", settings.subModel)
                .put("brightness", settings.brightness)
                .put("speed", settings.speed)
                .put("direction", settings.direction)
                .put("color", settings.color)
                .put("enable", settings.enable),
        )
        writeRoot(root)
    }

    /**
     * Memuat peta remap: `LogicCode` tombol -> `DriverValue`.
     *
     * Hanya tombol yang pernah diubah pengguna yang disimpan.
     */
    fun loadKeyMap(): MutableMap<Int, Int> {
        val result = mutableMapOf<Int, Int>()
        val root = readRoot() ?: return result
        val obj = root.optJSONObject("keymap") ?: return result
        obj.keys().forEach { key ->
            val logic = key.toIntOrNull() ?: return@forEach
            result[logic] = obj.optInt(key, KeyCodes.encode(0))
        }
        return result
    }

    /** Menyimpan peta remap. */
    fun saveKeyMap(map: Map<Int, Int>) {
        val root = readRoot() ?: JSONObject()
        val obj = JSONObject()
        map.forEach { (logic, value) -> obj.put(logic.toString(), value) }
        root.put("keymap", obj)
        writeRoot(root)
    }

    /** Menghapus semua setelan lokal. */
    fun clear() {
        if (file.exists()) file.delete()
        LogBus.log("Setelan lokal dihapus")
    }

    // ---------------------------------------------------------------------

    private fun readRoot(): JSONObject? = try {
        if (file.exists()) JSONObject(file.readText(Charsets.UTF_8)) else null
    } catch (e: Exception) {
        LogBus.log("Gagal membaca setelan lokal: ${e.message}")
        null
    }

    private fun writeRoot(root: JSONObject) {
        try {
            file.writeText(root.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            LogBus.log("Gagal menyimpan setelan lokal: ${e.message}")
        }
    }
}
