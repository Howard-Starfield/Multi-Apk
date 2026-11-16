package dev.clonelab.app

import android.content.Context
import dev.clonelab.app.data.CloneRepository
import dev.clonelab.app.data.CloneStore
import dev.clonelab.app.engine.BlackBoxCloneEngine
import dev.clonelab.app.engine.CloneEngine
import java.io.File

/** Builds the app's single engine and repository on first use, so clone processes never create them. */
object ServiceLocator {
    private var engine: CloneEngine? = null
    private var store: CloneStore? = null
    private var repository: CloneRepository? = null

    @Synchronized
    fun engine(): CloneEngine =
        engine ?: BlackBoxCloneEngine().also { engine = it }

    @Synchronized
    fun store(context: Context): CloneStore =
        store ?: CloneStore(File(context.applicationContext.filesDir, "clones.json")).also { store = it }

    @Synchronized
    fun repository(context: Context): CloneRepository =
        repository ?: CloneRepository(
            engine = engine(),
            store = store(context),
            clock = System::currentTimeMillis,
        ).also { repository = it }
}
