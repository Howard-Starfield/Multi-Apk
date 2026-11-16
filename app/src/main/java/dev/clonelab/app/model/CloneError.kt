package dev.clonelab.app.model

/**
 * Why a clone operation failed, as data. Pure code (repository, engine) reports these; the UI maps them to
 * string resources, so the text follows the system language. Free text from the engine is only ever a detail.
 */
sealed interface CloneError {
    data object EngineNotRunning : CloneError
    data object UnknownClone : CloneError
    data object UninstallFailed : CloneError
    data object NoPackageName : CloneError
    data object NoResult : CloneError

    /** The clone was installed but its record could not be stored; [detail] is the I/O message, if any. */
    data class SaveFailed(val detail: String?) : CloneError

    /** The package has no launchable activity (or the engine refused to start it). */
    data class NotLaunchable(val packageName: String) : CloneError

    /** The engine reported an install failure; [reason] is its own free text. */
    data class InstallFailed(val reason: String?) : CloneError

    /** An unexpected exception inside the engine; [detail] is its message. */
    data class Engine(val detail: String) : CloneError
}

/** Carries a [CloneError] through [Result] failures. */
open class CloneException(val error: CloneError, cause: Throwable? = null) :
    IllegalStateException(error.toString(), cause)
