package dev.clonelab.app

import android.app.Application
import android.content.Context
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.ClientConfiguration

/**
 * Runs in every process (main, the engine's :black server, and each clone's stub process),
 * so it only initialises the engine. App-only state lives in [ServiceLocator], which is lazy.
 */
class CloneLabApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
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
    }
}
