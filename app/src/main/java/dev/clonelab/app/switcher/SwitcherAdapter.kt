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

/**
 * Grid cells of the pill: cloned apps at the Apps level, clones at the Clones level. Icons are prepared off
 * the main thread by the caller; binding only assigns them.
 */
class SwitcherAdapter(
    private val onSelect: (CloneId) -> Unit,
    private val onSelectApp: (String) -> Unit,
) : RecyclerView.Adapter<SwitcherAdapter.Holder>() {

    private var cells: List<SwitcherCell> = emptyList()
    private var cloneIcons: Map<CloneId, Drawable> = emptyMap()
    private var appIcons: Map<String, Drawable> = emptyMap()

    fun update(cells: List<SwitcherCell>, cloneIcons: Map<CloneId, Drawable>, appIcons: Map<String, Drawable>) {
        this.cells = cells
        this.cloneIcons = cloneIcons
        this.appIcons = appIcons
        notifyDataSetChanged()
    }

    override fun getItemCount() = cells.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_switcher_clone, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        when (val cell = cells[position]) {
            is SwitcherItem -> bindClone(holder, cell)
            is SwitcherAppItem -> bindApp(holder, cell)
        }
    }

    private fun bindClone(holder: Holder, item: SwitcherItem) {
        holder.icon.setImageDrawable(cloneIcons[item.id])
        holder.badge.text = item.number.toString()
        holder.name.text = item.label
        holder.name.alpha = if (item.state == SwitcherState.IDLE) IDLE_NAME_ALPHA else 1f
        holder.itemView.contentDescription = item.label
        holder.ring.setBackgroundResource(if (item.state == SwitcherState.FOCUSED) R.drawable.bg_switcher_ring else 0)
        holder.dot.visibility = if (item.state == SwitcherState.RUNNING) View.VISIBLE else View.GONE
        holder.icon.alpha = if (item.state == SwitcherState.IDLE) IDLE_ALPHA else 1f
        holder.itemView.setOnClickListener {
            // The focused clone is already on screen: nothing to do.
            if (item.state != SwitcherState.FOCUSED) onSelect(item.id)
        }
    }

    private fun bindApp(holder: Holder, app: SwitcherAppItem) {
        holder.icon.setImageDrawable(appIcons[app.packageName])
        holder.badge.text = app.count.toString()
        holder.name.text = app.label
        holder.name.alpha = 1f
        holder.itemView.contentDescription = app.label
        holder.ring.setBackgroundResource(0)
        holder.dot.visibility = if (app.anyRunning) View.VISIBLE else View.GONE
        holder.icon.alpha = 1f
        holder.itemView.setOnClickListener { onSelectApp(app.packageName) }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val ring: FrameLayout = view.findViewById(R.id.ring)
        val icon: ImageView = view.findViewById(R.id.icon)
        val badge: TextView = view.findViewById(R.id.badge)
        val dot: View = view.findViewById(R.id.runningDot)
        val name: TextView = view.findViewById(R.id.name)
    }

    private companion object {
        const val IDLE_ALPHA = 0.45f
        const val IDLE_NAME_ALPHA = 0.7f
    }
}
