package dev.clonelab.app.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.clonelab.app.R
import dev.clonelab.app.data.CloneItem
import dev.clonelab.app.databinding.ItemCloneBinding
import dev.clonelab.app.ui.AppVisuals

class CloneAdapter(
    private val onClick: (CloneItem) -> Unit,
    private val onLongClick: (CloneItem) -> Unit,
) : ListAdapter<CloneItem, CloneAdapter.Holder>(Diff) {

    class Holder(val binding: ItemCloneBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemCloneBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
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
            root.setOnClickListener { onClick(item) }
            root.setOnLongClickListener { onLongClick(item); true }
        }
    }

    private object Diff : DiffUtil.ItemCallback<CloneItem>() {
        override fun areItemsTheSame(a: CloneItem, b: CloneItem) = a.record.id == b.record.id
        override fun areContentsTheSame(a: CloneItem, b: CloneItem) = a == b
    }
}
