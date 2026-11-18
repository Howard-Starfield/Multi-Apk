package dev.clonelab.app.tools

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.text.format.Formatter
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.clonelab.app.R
import dev.clonelab.app.databinding.ItemPhoneAppBinding

/**
 * One row per installed app: icon, label, package, version and size. Rows in [update]'s selection are
 * tinted and checked; apps with clones get a "cloned" tag.
 */
class PhoneAppAdapter(
    private val packageManager: PackageManager,
    private val onClick: (PhoneApp) -> Unit,
) : ListAdapter<PhoneApp, PhoneAppAdapter.Holder>(Diff) {

    private var selected: Set<String> = emptySet()
    private var cloned: Set<String> = emptySet()

    /** Selection and clone tags live outside the list items, so a change rebinds every row. */
    fun update(selected: Set<String>, cloned: Set<String>) {
        if (selected == this.selected && cloned == this.cloned) return
        this.selected = selected
        this.cloned = cloned
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemPhoneAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val app = getItem(position)
        val isSelected = app.packageName in selected
        with(holder.binding) {
            val context = root.context
            icon.setImageDrawable(iconOf(app.packageName))
            label.text = app.label
            packageName.text = app.packageName
            val size = Formatter.formatFileSize(context, app.apkBytes)
            details.text = if (app.versionName.isBlank()) size else context.getString(R.string.tools_version_size, app.versionName, size)
            clonedTag.visibility = if (app.packageName in cloned) View.VISIBLE else View.GONE
            selectedCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
            root.setBackgroundResource(if (isSelected) R.drawable.bg_tile_selected else rippleRes(root))
            root.setOnClickListener { onClick(app) }
        }
    }

    private fun iconOf(packageName: String): Drawable? = try {
        packageManager.getApplicationIcon(packageName)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun rippleRes(view: View): Int {
        val value = TypedValue()
        view.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        return value.resourceId
    }

    class Holder(val binding: ItemPhoneAppBinding) : RecyclerView.ViewHolder(binding.root)

    private object Diff : DiffUtil.ItemCallback<PhoneApp>() {
        override fun areItemsTheSame(a: PhoneApp, b: PhoneApp) = a.packageName == b.packageName
        override fun areContentsTheSame(a: PhoneApp, b: PhoneApp) = a == b
    }
}
