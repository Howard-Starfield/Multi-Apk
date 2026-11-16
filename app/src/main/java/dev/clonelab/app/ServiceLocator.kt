package dev.clonelab.app

import android.content.Context
import dev.clonelab.app.data.CloneRepository
import dev.clonelab.app.data.CloneStore
import dev.clonelab.app.engine.BlackBoxCloneEngine
import dev.clonelab.app.engine.CloneEngine
import dev.clonelab.app.keepalive.ManualStops
import dev.clonelab.app.macro.MacroRecorder
import dev.clonelab.app.macro.MacroStore
import java.io.File

/** Builds the app's single engine and repository on first use, so clone processes never create them. */
object ServiceLocator {
    private var engine: CloneEngine? = null
    private var store: CloneStore? = null
    private var repository: CloneRepository? = null
    private var macroStore: MacroStore? = null
    private var macroRecorder: MacroRecorder? = null

    @Synchronized
    fun engine(): CloneEngine =
        engine ?: BlackBoxCloneEngine().also { engine = it }

    @Synchronized
    fun store(context: Context): CloneStore =
        store ?: CloneStore(File(context.applicationContext.filesDir, "clones.json")).also { store = it }

    @Synchronized
    fun repository(context: Context): CloneRepository {
        ManualStops.install(context)
        return repository ?: CloneRepository(
            engine = engine(),
            store = store(context),
            clock = System::currentTimeMillis,
        ).also { repository = it }
    }

    /** Main process only. */
    @Synchronized
    fun macroStore(context: Context): MacroStore =
        macroStore ?: MacroStore(File(context.applicationContext.filesDir, "macros.json")).also { macroStore = it }

    /** Main process only. */
    @Synchronized
    fun macroRecorder(context: Context): MacroRecorder =
        macroRecorder ?: MacroRecorder(context.applicationContext.filesDir).also { macroRecorder = it }
}
