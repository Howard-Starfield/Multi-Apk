package dev.clonelab.app.shortcut

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import dev.clonelab.app.R
import dev.clonelab.app.model.CloneId
import dev.clonelab.app.model.CloneRecord
import dev.clonelab.app.ui.AppVisuals

/** Pins launcher shortcuts for clones and keeps already pinned ones in step with their record. */
class ShortcutPublisher(context: Context) {
    private val context = context.applicationContext

    fun isSupported(): Boolean = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /** Asks the launcher to pin a shortcut. @return false when pinning is not supported. */
    fun pin(record: CloneRecord): Boolean {
        if (!isSupported()) return false
        return ShortcutManagerCompat.requestPinShortcut(context, build(record), null)
    }

    /** Refreshes label and icon of a pinned shortcut after a rename or icon change. */
    fun update(record: CloneRecord) {
        try {
            ShortcutManagerCompat.updateShortcuts(context, listOf(build(record)))
        } catch (e: RuntimeException) {
            // Shortcut limits or a launcher problem must not break editing the clone.
        }
    }

    /** Disables the pinned shortcut of a removed clone. */
    fun disable(id: CloneId) {
        try {
            ShortcutManagerCompat.disableShortcuts(
                context,
                listOf(shortcutId(id)),
                context.getString(R.string.shortcut_disabled_message),
            )
        } catch (e: RuntimeException) {
            // Nothing pinned, or the launcher refused: nothing more to do.
        }
    }

    private fun build(record: CloneRecord): ShortcutInfoCompat {
        val bitmap = AppVisuals.icon(context, record).toBitmap(ICON_SIZE, ICON_SIZE)
        return ShortcutInfoCompat.Builder(context, shortcutId(record.id))
            .setShortLabel(AppVisuals.label(record))
            .setIcon(IconCompat.createWithBitmap(bitmap))
            .setIntent(ShortcutTrampolineActivity.intent(context, record.id))
            .build()
    }

    companion object {
        private const val ICON_SIZE = 192

        fun shortcutId(id: CloneId) = "${id.packageName}#${id.userId}"
    }
}
