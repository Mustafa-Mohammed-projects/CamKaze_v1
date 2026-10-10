package org.kaze.camkaze

import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.kaze.camkaze.data.Photo
import org.kaze.camkaze.data.SecureItem

sealed class Route(
    val isSecure: Boolean = false,
    val needsUnlock: Boolean = false,
    val showNav: Boolean = true,
) {
    data object Camera : Route()
    data object Gallery : Route()
    data class Viewer(val photo: Photo) : Route(showNav = false)
    data object Lock : Route(isSecure = true)
    data object SecureGallery : Route(isSecure = true, needsUnlock = true)
    data object SecureCamera : Route(isSecure = true, needsUnlock = true, showNav = false)
    data class SecureViewer(val item: SecureItem) : Route(true, true, false)
}

class AppState(private val activity: ComponentActivity) {
    var route: Route by mutableStateOf(Route.Camera)
        private set
    var galleryVersion by mutableIntStateOf(0)
    var secureVersion by mutableIntStateOf(0)
    var unlockAction: (() -> Unit)? by mutableStateOf(null)

    val snackbar = SnackbarHostState()
    private val handler = Handler(Looper.getMainLooper())

    fun post(r: () -> Unit) { handler.post(r) }

    fun toast(msg: String) {
        activity.lifecycleScope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg, duration = SnackbarDuration.Short)
        }
    }

    /** FLAG_SECURE is applied BEFORE the secure route is composed. */
    fun go(target: Route) {
        val r = if (target.needsUnlock && !Services.vault.isUnlocked) Route.Lock else target
        setSecureWindow(r.isSecure)
        route = r
    }

    fun back() {
        when (route) {
            is Route.Viewer -> go(Route.Gallery)
            is Route.SecureViewer, Route.SecureCamera -> go(Route.SecureGallery)
            Route.Camera -> {}
            else -> go(Route.Camera)
        }
    }

    /** Ask for the password if needed, then run [action]. */
    fun requireUnlocked(action: () -> Unit) {
        val v = Services.vault
        when {
            v.isUnlocked -> action()
            !v.isConfigured -> { toast("Set up Secure mode first"); go(Route.Lock) }
            else -> unlockAction = action
        }
    }

    fun lockNow() {
        Services.vault.lock()
        secureVersion++
        go(Route.Lock)
    }

    /** App paused: wipe the key and drop decrypted content. */
    fun onBackground() {
        Services.vault.lock()
        unlockAction = null
        if (route.isSecure) go(Route.Lock)
    }

    private fun setSecureWindow(on: Boolean) {
        val w = activity.window
        if (on) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        try { activity.setRecentsScreenshotEnabled(!on) } catch (_: Throwable) {}
    }
}
