package dev.clonelab.app.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import dev.clonelab.app.R
import dev.clonelab.app.databinding.ItemCloneBinding
import dev.clonelab.app.ui.AppVisuals

/** Home grid: one tile per app, then the add tile as a second view type (never an item). */
class AppGroupAdapter(
    private val onAppClick: (AppGroup) -> Unit,
    private val onAddClick: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var groups: List<AppGroup> = emptyList()

    override fun getItemCount() = groups.size + 1

    override fun getItemViewType(position: Int) =
        if (position == groups.size) AddTileHolder.VIEW_TYPE else APP_VIEW_TYPE

    fun submitList(list: List<AppGroup>) {
        val old = groups
        val result = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = list.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].packageName == list[n].packageName
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == list[n]
        })
        groups = list
        result.dispatchUpdatesTo(this)
    }

    class Holder(val binding: ItemCloneBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == AddTileHolder.VIEW_TYPE) {
            AddTileHolder.create(parent)
        } else {
            Holder(ItemCloneBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is AddTileHolder) {
            holder.bind(R.string.home_add_app, enabled = true, onClick = onAddClick)
            return
        }
        val group = groups[position]
        val context = holder.itemView.context
        with((holder as Holder).binding) {
            icon.setImageDrawable(AppVisuals.appIcon(context, group.representative))
            name.text = AppVisuals.appLabel(group.packageName, group.label)
            name.maxLines = 1
            countBadge.text = group.cloneCount.toString()
            countBadge.visibility = View.VISIBLE
            runningDot.visibility = if (group.anyRunning) View.VISIBLE else View.GONE
            root.alpha = 1f
            root.setOnClickListener { onAppClick(group) }
            root.setOnLongClickListener(null)
            root.isLongClickable = false
        }
    }

    private companion object {
        const val APP_VIEW_TYPE = 0
    }
}
