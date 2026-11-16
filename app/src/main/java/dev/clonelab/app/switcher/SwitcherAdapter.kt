package dev.clonelab.app.switcher

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.clonelab.app.R
import dev.clonelab.app.model.CloneId

/** Grid entries of the pill. Icons are prepared off the main thread by the caller; binding only assigns them. */
class SwitcherAdapter(
    private val onSelect: (CloneId) -> Unit,
) : RecyclerView.Adapter<SwitcherAdapter.Holder>() {

    private var items: List<SwitcherItem> = emptyList()
    private var icons: Map<CloneId, Drawable> = emptyMap()

    fun update(items: List<SwitcherItem>, icons: Map<CloneId, Drawable>) {
        this.items = items
        this.icons = icons
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_switcher_clone, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.icon.setImageDrawable(icons[item.id])
        holder.badge.text = item.number.toString()
        holder.itemView.contentDescription = item.label
        holder.ring.setBackgroundResource(if (item.state == SwitcherState.FOCUSED) R.drawable.bg_switcher_ring else 0)
        holder.dot.visibility = if (item.state == SwitcherState.RUNNING) View.VISIBLE else View.GONE
        holder.icon.alpha = if (item.state == SwitcherState.IDLE) IDLE_ALPHA else 1f
        holder.itemView.setOnClickListener {
            // The focused clone is already on screen: nothing to do.
            if (item.state != SwitcherState.FOCUSED) onSelect(item.id)
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val ring: FrameLayout = view.findViewById(R.id.ring)
        val icon: ImageView = view.findViewById(R.id.icon)
        val badge: TextView = view.findViewById(R.id.badge)
        val dot: View = view.findViewById(R.id.runningDot)
    }

    private companion object {
        const val IDLE_ALPHA = 0.45f
    }
}
