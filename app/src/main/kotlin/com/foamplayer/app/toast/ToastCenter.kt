package com.foamplayer.app.toast

import android.content.Context
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Center for in-app toasts: small transient messages rendered above the mini
 * player by [com.foamplayer.app.ui.components.ToastStack].
 *
 * Toasts stack up to [MAX_STACKED] (the oldest is dropped beyond that) and each
 * auto-dismisses after [TOAST_DURATION_MS]. The name is deliberately "toast",
 * not "notification", which is reserved for the media session notification.
 *
 * Producers are typically non-UI singletons (e.g. AudioEngine reporting a track
 * that failed to load); the UI only reads [toasts].
 */
@Singleton
class ToastCenter @Inject constructor(
    @ApplicationContext private val context: Context
) {

    data class Toast(
        val id: Long,
        val text: String,
        val createdAtMs: Long
    )

    /** Owns the per-toast expiry timers, mirroring AudioEngine's private main scope. */
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val nextToastId = AtomicLong(0L)

    private val _toasts = MutableStateFlow<List<Toast>>(emptyList())

    /** Currently visible toasts, oldest first. */
    val toasts: StateFlow<List<Toast>> = _toasts.asStateFlow()

    /** Shows [text] above the mini player for [TOAST_DURATION_MS]. */
    fun push(text: String) {
        if (text.isBlank()) return
        val toast = Toast(
            id = nextToastId.incrementAndGet(),
            text = text,
            createdAtMs = System.currentTimeMillis()
        )
        _toasts.update { current ->
            (current + toast).takeLast(MAX_STACKED)
        }
        scope.launch {
            delay(TOAST_DURATION_MS)
            _toasts.update { current -> current.filterNot { it.id == toast.id } }
        }
    }

    /**
     * Shows a localized toast for [resId], optionally formatted with
     * [formatArg]. Lets non-UI producers (e.g. AudioEngine) stay independent
     * from the screen that happens to be visible.
     */
    fun pushRes(@StringRes resId: Int, formatArg: Any? = null) {
        push(
            if (formatArg != null) {
                context.getString(resId, formatArg)
            } else {
                context.getString(resId)
            }
        )
    }

    companion object {
        const val MAX_STACKED = 3
        const val TOAST_DURATION_MS = 5_000L
    }
}
