package dev.clonelab.app.ui.home

import android.view.LayoutInflater
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import dev.clonelab.app.R
import dev.clonelab.app.data.CloneItem
import dev.clonelab.app.databinding.ItemCloneBinding
import dev.clonelab.app.model.CloneId
import dev.clonelab.app.ui.AppVisuals

class CloneAdapter(
    private val onClick: (CloneItem) -> Unit,
    private val onLongClick: (CloneItem) -> Unit,
    private val onAdd: () -> Unit,
    @StringRes private val addCaption: Int,
    private val onDragStart: (Holder) -> Unit = {},
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** While true, touching a tile starts a drag and tapping does nothing else. */
    var reorderMode = false
        set(value) {
            field = value
            notifyItemRangeChanged(0, itemCount)
        }

    /** The list as the user currently sees it, including moves not yet persisted. */
    private var working: List<CloneItem> = emptyList()

    /** The clones plus the trailing add tile, which is a view type and not an item. */
    override fun getItemCount() = working.size + 1

    override fun getItemViewType(position: Int) =
        if (position == working.size) AddTileHolder.VIEW_TYPE else CLONE_VIEW_TYPE

    fun isAddTile(holder: RecyclerView.ViewHolder) = holder is AddTileHolder

    /** Diffs synchronously so that adapter positions are always valid during a drag. */
    fun submitList(list: List<CloneItem>) {
        val old = working
        val result = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = list.size
            override fun areItemsTheSame(o: Int, n: Int) = Diff.areItemsTheSame(old[o], list[n])
            override fun areContentsTheSame(o: Int, n: Int) = Diff.areContentsTheSame(old[o], list[n])
        })
        working = list
        result.dispatchUpdatesTo(this)
    }

    fun move(from: Int, to: Int) {
        if (from !in working.indices || to !in working.indices) return
        val moved = working.toMutableList()
        moved.add(to, moved.removeAt(from))
        working = moved
        notifyItemMoved(from, to)
    }

    fun order(): List<CloneId> = working.map { it.record.id }

    class Holder(val binding: ItemCloneBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == AddTileHolder.VIEW_TYPE) {
            AddTileHolder.create(parent)
        } else {
            Holder(ItemCloneBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is AddTileHolder) {
            holder.bind(addCaption, enabled = !reorderMode, onClick = onAdd)
            return
        }
        holder as Holder
        val item = working[position]
        val context = holder.itemView.context
        with(holder.binding) {
            icon.setImageDrawable(AppVisuals.icon(context, item.record))
            name.text = if (item.installed) {
                AppVisuals.label(item.record)
            } else {
                context.getString(R.string.home_broken_label, AppVisuals.label(item.record))
            }
            root.alpha = if (item.installed) 1f else 0.5f
            runningDot.visibility = if (item.running) View.VISIBLE else View.GONE
            if (reorderMode) {
                root.setOnClickListener(null)
                root.setOnLongClickListener(null)
                root.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) onDragStart(holder)
                    false
                }
            } else {
                root.setOnTouchListener(null)
                root.setOnClickListener { onClick(item) }
                root.setOnLongClickListener { onLongClick(item); true }
            }
        }
    }

    private companion object {
        const val CLONE_VIEW_TYPE = 0
    }

    private object Diff : DiffUtil.ItemCallback<CloneItem>() {
        override fun areItemsTheSame(a: CloneItem, b: CloneItem) = a.record.id == b.record.id
        override fun areContentsTheSame(a: CloneItem, b: CloneItem) = a == b
    }
}
