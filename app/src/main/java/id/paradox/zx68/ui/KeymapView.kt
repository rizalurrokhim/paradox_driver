package id.paradox.zx68.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import id.paradox.zx68.R
import id.paradox.zx68.data.KeyLayoutEntry
import id.paradox.zx68.protocol.KeyCodes
import kotlin.math.max
import kotlin.math.min

/**
 * Menggambar layout tombol ZX68 dari data driver dan menangani ketukan.
 *
 * Sumber data: `assets/zx68_layout.json` (hasil konversi `keymap.js`) yang
 * berisi koordinat asli dari aplikasi Windows, sehingga proporsi tombol sama
 * dengan yang dilihat pengguna di PC.
 *
 * View ini tidak menyimpan state sendiri; pemilik data adalah
 * [KeymapActivity] + [id.paradox.zx68.data.ProfileStore].
 */
class KeymapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var entries: List<KeyLayoutEntry> = emptyList()
    private var bindings: Map<Int, Int> = emptyMap()
    private var selectedLogic: Int? = null

    /** Dipanggil ketika pengguna mengetuk sebuah tombol. */
    var onKeySelected: ((KeyLayoutEntry) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val keyRects = LinkedHashMap<Int, RectF>()
    private var layoutBounds = RectF()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.surface_dark_alt)
        style = Paint.Style.FILL
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.surface_dark)
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_primary)
        textAlign = Paint.Align.CENTER
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
        textAlign = Paint.Align.CENTER
    }

    /** Memasang data layout + peta remap (logic -> DriverValue). */
    fun bind(layout: List<KeyLayoutEntry>, remap: Map<Int, Int>) {
        entries = layout
        bindings = remap
        layoutBounds = computeBounds(layout)
        recalcRects()
        invalidate()
    }

    /** Menandai tombol terpilih (atau `null` untuk menghapus pilihan). */
    fun select(logic: Int?) {
        selectedLogic = logic
        invalidate()
    }

    private fun computeBounds(layout: List<KeyLayoutEntry>): RectF {
        if (layout.isEmpty()) return RectF(0f, 0f, 1f, 1f)
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        layout.forEach {
            left = min(left, it.left.toFloat())
            top = min(top, it.top.toFloat())
            right = max(right, it.right.toFloat())
            bottom = max(bottom, it.bottom.toFloat())
        }
        return RectF(left, top, right, bottom)
    }

    private fun recalcRects() {
        keyRects.clear()
        val padding = 4f * density
        val availableWidth = width - padding * 2
        val availableHeight = height - padding * 2
        if (availableWidth <= 0 || availableHeight <= 0 || layoutBounds.width() <= 0f) return

        val scale = min(
            availableWidth / layoutBounds.width(),
            availableHeight / layoutBounds.height(),
        )
        val offsetX = padding + (availableWidth - layoutBounds.width() * scale) / 2f
        val offsetY = padding + (availableHeight - layoutBounds.height() * scale) / 2f

        entries.forEach { entry ->
            keyRects[entry.logic] = RectF(
                offsetX + (entry.left - layoutBounds.left) * scale,
                offsetY + (entry.top - layoutBounds.top) * scale,
                offsetX + (entry.right - layoutBounds.left) * scale,
                offsetY + (entry.bottom - layoutBounds.top) * scale,
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recalcRects()
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (keyRects.isEmpty()) {
            canvas.drawText("Layout belum dimuat", width / 2f, height / 2f, textPaint)
            return
        }

        val radius = 4f * density
        keyRects.forEach { (logic, rect) ->
            val isSelected = selectedLogic == logic
            canvas.drawRoundRect(rect, radius, radius, if (isSelected) selectedPaint else fillPaint)
            canvas.drawRoundRect(rect, radius, radius, strokePaint)

            val bindingValue = bindings[logic]
            val primary = if (bindingValue != null && !KeyCodes.isEmptyValue(bindingValue)) {
                KeyCodes.decode(bindingValue).label
            } else {
                entries.firstOrNull { it.logic == logic }?.show ?: ""
            }

            val keyHeight = rect.height()
            textPaint.textSize = (keyHeight * 0.42f).coerceIn(6f * density, 13f * density)
            textPaint.color = if (isSelected) {
                Color.parseColor("#00171C")
            } else {
                ContextCompat.getColor(context, R.color.text_primary)
            }

            val centerX = rect.centerX()
            val centerY = rect.centerY() + textPaint.textSize / 3f
            canvas.drawText(shorten(primary), centerX, centerY, textPaint)

            // Tampilkan nama asli tombol sebagai penanda bila sudah diremap.
            if (bindingValue != null && !KeyCodes.isEmptyValue(bindingValue)) {
                val original = entries.firstOrNull { it.logic == logic }?.show.orEmpty()
                subTextPaint.textSize = (keyHeight * 0.3f).coerceIn(5f * density, 9f * density)
                canvas.drawText(
                    shorten(original, 7),
                    centerX,
                    rect.top + subTextPaint.textSize + 1f,
                    subTextPaint,
                )
            }
        }
    }

    /** Memotong label panjang agar tidak melampaui tombol. */
    private fun shorten(text: String, maxChars: Int = 9): String =
        if (text.length <= maxChars) text else text.take(maxChars - 1) + "…"

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            keyRects.entries.firstOrNull { it.value.contains(event.x, event.y) }?.let { hit ->
                val entry = entries.firstOrNull { it.logic == hit.key }
                if (entry != null) {
                    selectedLogic = entry.logic
                    invalidate()
                    onKeySelected?.invoke(entry)
                }
                return true
            }
        }
        return true
    }
}
