package dev.clonelab.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * viewModelScope.launch that cannot crash the app: an unexpected exception goes to [onError]
 * (normally the screen's error message) instead of the uncaught-exception handler.
 */
fun ViewModel.launchGuarded(
    onError: suspend (String) -> Unit,
    block: suspend CoroutineScope.() -> Unit,
): Job = viewModelScope.launch {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onError(e.message ?: e.javaClass.simpleName)
    }
}
