package org.kaze.camkaze

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.kaze.camkaze.data.ShareCache
import org.kaze.camkaze.ui.CamKazeApp

class MainActivity : ComponentActivity() {
    private lateinit var state: AppState

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        state = AppState(this)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onPause(owner: LifecycleOwner) = state.onBackground()
        })
        setContent { CamKazeApp(state) }
    }

    override fun onResume() {
        super.onResume()
        ShareCache.purge(this, ShareCache.MAX_AGE_MS)
    }

    override fun onDestroy() {
        Services.vault.lock()
        super.onDestroy()
    }
}
