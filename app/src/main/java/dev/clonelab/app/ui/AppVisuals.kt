package dev.clonelab.app.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.core.content.ContextCompat
import dev.clonelab.app.R
import dev.clonelab.app.engine.CloneEngine
import dev.clonelab.app.model.CloneRecord
import dev.clonelab.app.model.IconKind
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves what the UI shows for a clone: custom image, text icon, source app icon, placeholder.
 *
 * Reading the engine-installed APK is slow, so it happens in [warmUp] off the main thread;
 * [icon] and [label] only read caches and are safe to call while binding views.
 */
object AppVisuals {

    private const val MAX_CACHED_IMAGES = 64

    private class ApkInfo(val label: String, val icon: Drawable.ConstantState?)

    /** APK-derived info per package, for clones whose source app is not installed on the phone. */
    private val apkInfo = ConcurrentHashMap<String, ApkInfo>()
    private val images = LruCache<String, Drawable.ConstantState>(MAX_CACHED_IMAGES)

    fun label(record: CloneRecord): String {
        if (record.customName != null) return record.displayName
        val apkLabel = apkInfo[record.id.packageName]?.label
        return if (apkLabel != null) "$apkLabel ${record.number}" else record.displayName
    }

    fun icon(context: Context, record: CloneRecord): Drawable {
        when (record.iconKind) {
            IconKind.IMAGE -> imageIcon(context, record)?.let { return it }
            IconKind.TEXT -> return textIcon(context, record)
            IconKind.APP -> Unit
        }
        return sourceIcon(context, record) ?: placeholder(context)
    }

    /**
     * Loads label and icon from the engine's copy of the APK for clones whose package is not
     * installed on the phone. Blocks: call from a background dispatcher.
     * @return true when something new was cached and views should rebind.
     */
    fun warmUp(context: Context, engine: CloneEngine, records: List<CloneRecord>): Boolean {
        var changed = false
        val pm = context.packageManager
        for (record in records) {
            val pkg = record.id.packageName
            if (apkInfo.containsKey(pkg) || isInstalledOnPhone(pm, pkg)) continue
            val info = readApk(pm, engine.apkPath(record.id)) ?: continue
            apkInfo[pkg] = info
            changed = true
        }
        return changed
    }

    private fun readApk(pm: PackageManager, path: String?): ApkInfo? {
        if (path == null) return null
        return try {
            val app = pm.getPackageArchiveInfo(path, 0)?.applicationInfo ?: return null
            app.sourceDir = path
            app.publicSourceDir = path
            ApkInfo(app.loadLabel(pm).toString(), app.loadIcon(pm)?.constantState)
        } catch (e: Exception) {
            null
        }
    }

    private fun sourceIcon(context: Context, record: CloneRecord): Drawable? {
        val pkg = record.id.packageName
        return try {
            context.packageManager.getApplicationIcon(pkg)
        } catch (e: PackageManager.NameNotFoundException) {
            apkInfo[pkg]?.icon?.newDrawable(context.resources)
        }
    }

    private fun imageIcon(context: Context, record: CloneRecord): Drawable? {
        val file = record.iconImagePath?.let(::File)?.takeIf { it.isFile } ?: return null
        val key = "${file.path}:${file.lastModified()}:${record.iconVersion}"
        images.get(key)?.let { return it.newDrawable(context.resources) }
        val bitmap = BitmapFactory.decodeFile(file.path) ?: return null
        val drawable = BitmapDrawable(context.resources, bitmap)
        drawable.constantState?.let { images.put(key, it) }
        return drawable
    }

    private fun textIcon(context: Context, record: CloneRecord): Drawable {
        val color = if (record.iconColor == 0) ContextCompat.getColor(context, R.color.brand) else record.iconColor
        return TextIconDrawable(TextIcon.letter(label(record)), record.number, color)
    }

    private fun isInstalledOnPhone(pm: PackageManager, pkg: String): Boolean =
        try {
            pm.getApplicationInfo(pkg, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    private fun placeholder(context: Context): Drawable =
        ContextCompat.getDrawable(context, R.drawable.ic_placeholder)!!
}
