package dev.clonelab.app.ui

import android.content.Context
import androidx.annotation.StringRes
import dev.clonelab.app.R
import dev.clonelab.app.model.CloneError
import dev.clonelab.app.model.CloneException

/** A message as a string resource plus arguments; resolved against a Context only at the UI boundary. */
data class UserMessage(@StringRes val resId: Int, val args: List<Any> = emptyList()) {
    fun resolve(context: Context): String = context.getString(resId, *args.toTypedArray())
}

fun CloneError.toUserMessage(): UserMessage = when (this) {
    CloneError.EngineNotRunning -> UserMessage(R.string.error_engine_not_running)
    CloneError.UnknownClone -> UserMessage(R.string.error_not_found)
    CloneError.UninstallFailed -> UserMessage(R.string.error_uninstall_failed)
    CloneError.NoPackageName -> UserMessage(R.string.error_no_package_name)
    CloneError.NoResult -> UserMessage(R.string.error_engine_no_result)
    is CloneError.SaveFailed ->
        if (detail.isNullOrBlank()) UserMessage(R.string.error_save_failed)
        else UserMessage(R.string.error_save_failed_detail, listOf(detail))
    is CloneError.NotLaunchable -> UserMessage(R.string.error_not_launchable, listOf(packageName))
    is CloneError.InstallFailed ->
        if (reason.isNullOrBlank()) UserMessage(R.string.error_install_failed)
        else UserMessage(R.string.error_install_failed_reason, listOf(reason))
    is CloneError.Engine -> UserMessage(R.string.error_with_detail, listOf(detail))
}

/** Typed failures get their own sentence; anything else shows [fallback], with the exception text if any. */
fun Throwable.toUserMessage(@StringRes fallback: Int = R.string.error_generic): UserMessage {
    val text = message
    return when {
        this is CloneException -> error.toUserMessage()
        !text.isNullOrBlank() -> UserMessage(R.string.error_with_detail, listOf(text))
        else -> UserMessage(fallback)
    }
}
