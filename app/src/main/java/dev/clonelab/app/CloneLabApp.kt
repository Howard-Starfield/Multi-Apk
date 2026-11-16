package dev.clonelab.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import dev.clonelab.app.macro.RecorderHook
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.BActivityThread
import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback
import top.niunaijun.blackbox.app.configuration.ClientConfiguration
import java.io.File

/**
 * Runs in every process (main, the engine's :black server, and each clone's stub process),
 * so it only initialises the engine. App-only state lives in [ServiceLocator], which is lazy.
 */
class CloneLabApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Must be read here, from the host's own base context, before the engine starts redirecting
        // paths and hooking Context. Inside a clone process this is the only trustworthy way to learn
        // the host files directory, which the macro recorder uses to talk to the main process.
        // UNVERIFIED on a device: that this path is readable and writable from a clone process.
        hostFilesDir = base.filesDir
        val core = BlackBoxCore.get()
        core.onBeforeMainApplicationAttach(this, base)
        core.doAttachBaseContext(base, object : ClientConfiguration() {
            override fun getHostPackageName(): String = base.packageName
            override fun isEnableDaemonService(): Boolean = true
            override fun isHideRoot(): Boolean = false
            override fun getLogSenderChatId(): String? = null
        })
        core.onAfterMainApplicationAttach(this, base)
    }

    override fun onCreate() {
        super.onCreate()
        BlackBoxCore.get().doCreate()
        installRecorderHook()
    }

    /**
     * In clone processes only: hook the window of every resumed activity so the macro recorder can see
     * touches and keys. Guest activities are created by the engine, not by this Application, so the
     * engine's own callback (fired from its instrumentation delegate) is registered as well as a plain
     * one; installing twice on a window is harmless.
     */
    private fun installRecorderHook() {
        val core = BlackBoxCore.get()
        if (!core.isBlackProcess) return
        val hook = RecorderHook(hostFilesDir ?: return)
        val install = { activity: Activity ->
            val packageName = BActivityThread.getAppPackageName()
            if (packageName != null) hook.install(activity, packageName, BActivityThread.getUserId())
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = install(activity)
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        core.addAppLifecycleCallback(object : AppLifecycleCallback() {
            override fun onActivityResumed(activity: Activity) = install(activity)
        })
    }

    companion object {
        /** The host app's files directory, captured in [attachBaseContext]; see there. */
        @Volatile
        var hostFilesDir: File? = null
            private set
    }
}
