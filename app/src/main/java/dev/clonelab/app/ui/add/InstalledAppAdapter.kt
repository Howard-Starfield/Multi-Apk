package dev.clonelab.app.ui.add

import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.clonelab.app.databinding.ItemInstalledAppBinding

class InstalledAppAdapter(
    private val packageManager: PackageManager,
    private val onClick: (InstalledApp) -> Unit,
) : ListAdapter<InstalledApp, InstalledAppAdapter.Holder>(Diff) {

    class Holder(val binding: ItemInstalledAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemInstalledAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val app = getItem(position)
        with(holder.binding) {
            icon.setImageDrawable(packageManager.getApplicationIcon(app.packageName))
            label.text = app.label
            packageName.text = app.packageName
            root.setOnClickListener { onClick(app) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<InstalledApp>() {
        override fun areItemsTheSame(a: InstalledApp, b: InstalledApp) = a.packageName == b.packageName
        override fun areContentsTheSame(a: InstalledApp, b: InstalledApp) = a == b
    }
}
