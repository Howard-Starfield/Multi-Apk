package dev.clonelab.app.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import dev.clonelab.app.R
import dev.clonelab.app.model.CloneRecord

/** Resolves what the UI shows for a clone. */
object AppVisuals {

    fun label(record: CloneRecord): String = record.displayName

    /** The source app's icon when it is installed on the phone, otherwise a placeholder. */
    fun icon(context: Context, record: CloneRecord): Drawable =
        try {
            context.packageManager.getApplicationIcon(record.id.packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            placeholder(context)
        }

    private fun placeholder(context: Context): Drawable =
        ContextCompat.getDrawable(context, R.drawable.ic_placeholder)!!
}
