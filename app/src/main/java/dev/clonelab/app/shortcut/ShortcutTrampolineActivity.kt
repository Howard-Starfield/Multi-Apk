package dev.clonelab.app.shortcut

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.clonelab.app.R
import dev.clonelab.app.ServiceLocator
import dev.clonelab.app.model.CloneId
import dev.clonelab.app.ui.toUserMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Invisible entry point of pinned shortcuts: launches the clone it names, then finishes. */
class ShortcutTrampolineActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        if (packageName.isNullOrBlank()) {
            finish()
            return
        }
        val id = CloneId(packageName, intent.getIntExtra(EXTRA_USER_ID, 0))
        lifecycleScope.launch {
            val engine = ServiceLocator.engine()
            val ready = withContext(Dispatchers.IO) { engine.awaitReady(READY_TIMEOUT_MS) }
            val failure = if (ready) {
                ServiceLocator.repository(applicationContext).launch(id).exceptionOrNull()
                    ?.toUserMessage(R.string.error_launch_failed)?.resolve(this@ShortcutTrampolineActivity)
                    ?: return@launch finish()
            } else {
                getString(R.string.shortcut_engine_not_ready)
            }
            Toast.makeText(applicationContext, failure, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    companion object {
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_USER_ID = "userId"
        private const val READY_TIMEOUT_MS = 10_000L

        fun intent(context: Context, id: CloneId) = Intent(context, ShortcutTrampolineActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_PACKAGE, id.packageName)
            .putExtra(EXTRA_USER_ID, id.userId)
    }
}
