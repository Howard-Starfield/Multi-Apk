package dev.clonelab.app.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.recyclerview.widget.RecyclerView
import dev.clonelab.app.databinding.ItemAddTileBinding

/** The dashed "+" tile that ends a grid; shared by the app grid and the clone grid. */
class AddTileHolder(val binding: ItemAddTileBinding) : RecyclerView.ViewHolder(binding.root) {

    /** [enabled] false dims the tile and makes it inert (reorder mode). */
    fun bind(@StringRes caption: Int, enabled: Boolean, onClick: () -> Unit) {
        binding.caption.setText(caption)
        binding.root.alpha = if (enabled) 1f else 0.4f
        binding.root.isEnabled = enabled
        binding.root.setOnClickListener(if (enabled) View.OnClickListener { onClick() } else null)
        binding.root.isClickable = enabled
    }

    companion object {
        /** View type of the add tile in both adapters. Item views use 0. */
        const val VIEW_TYPE = 1

        fun create(parent: ViewGroup) =
            AddTileHolder(ItemAddTileBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }
}
