package dev.clonelab.app.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the keep-alive service back after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        KeepAliveController.sync(context) { pending.finish() }
    }
}
