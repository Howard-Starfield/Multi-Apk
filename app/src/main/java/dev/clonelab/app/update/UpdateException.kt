package dev.clonelab.app.update

import dev.clonelab.app.R
import dev.clonelab.app.ui.UserMessage

class UpdateException(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason(val messageRes: Int) {
        NETWORK(R.string.update_error_network),
        SERVER(R.string.update_error_server),
        UNTRUSTED_URL(R.string.update_error_url),
        SIZE_MISMATCH(R.string.update_error_size),
        STORAGE(R.string.update_error_storage),
        NOT_NEWER(R.string.update_error_not_newer),
    }

    fun toUserMessage() = UserMessage(reason.messageRes)
}
