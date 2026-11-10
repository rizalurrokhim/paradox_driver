package id.paradox.zx68.ui

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import id.paradox.zx68.R

/**
 * Grid efek pencahayaan.
 *
 * Menampilkan daftar efek yang diekstrak dari driver Windows
 * (`assets/zx68_effects.json`) sebagai kotak-kotak, seperti tile efek pada
 * software aslinya. Hanya satu efek aktif pada satu waktu.
 *
 * @param onSelect dipanggil dengan indeks efek yang diketuk
 */
class EffectAdapter(
    private val onSelect: (Int) -> Unit,
) : RecyclerView.Adapter<EffectAdapter.TileHolder>() {

    private var names: List<String> = emptyList()

    /** Indeks efek terpilih; `-1` bila belum ada. */
    var selected: Int = -1
        set(value) {
            val old = field
            field = value
            // Jaga-jaga saat daftar efek masih kosong: notifyItemChanged dengan
            // indeks di luar jangkauan membuat LayoutManager tidak konsisten.
            if (old in names.indices) notifyItemChanged(old)
            if (value in names.indices) notifyItemChanged(value)
        }

    /** Mengganti daftar efek yang ditampilkan. */
    fun submit(effects: List<String>) {
        names = effects
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_effect, parent, false)
        return TileHolder(view)
    }

    override fun onBindViewHolder(holder: TileHolder, position: Int) {
        holder.bind(names[position], position == selected)
    }

    override fun getItemCount(): Int = names.size

    /** Satu kotak efek. */
    inner class TileHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val name: TextView = itemView.findViewById(R.id.tvEffectName)
        private val dot: View = itemView.findViewById(R.id.effectDot)

        fun bind(effectName: String, isSelected: Boolean) {
            val context = itemView.context
            name.text = effectName
            name.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (isSelected) R.color.text_primary else R.color.text_secondary,
                ),
            )

            val radius = 10f * context.resources.displayMetrics.density
            itemView.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(ContextCompat.getColor(context, R.color.surface_dark_alt))
                setStroke(
                    (1.5f * context.resources.displayMetrics.density).toInt(),
                    ContextCompat.getColor(
                        context,
                        if (isSelected) R.color.accent else R.color.surface_dark_alt,
                    ),
                )
            }
            dot.alpha = if (isSelected) 1f else 0.35f

            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onSelect(position)
            }
        }
    }
}
