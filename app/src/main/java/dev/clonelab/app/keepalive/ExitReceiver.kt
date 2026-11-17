package dev.clonelab.app.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import kotlin.concurrent.thread

/**
 * Runs "Exit CloneLab" (see [ExitCloneLab]). Not exported: only our own explicit PendingIntent or
 * broadcast reaches it. The work is blocking, so it runs on its own thread under goAsync.
 */
class ExitReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ExitCloneLab.ACTION_EXIT) return
        val app = context.applicationContext
        val pending = goAsync()
        thread(name = "exit-clonelab") {
            try {
                ExitCloneLab.run(app)
            } finally {
                pending.finish()
                // Last: this process. Everything else is already gone.
                Process.killProcess(Process.myPid())
            }
        }
    }
}
