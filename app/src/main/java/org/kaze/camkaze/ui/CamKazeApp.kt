package org.kaze.camkaze.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.delay
import org.kaze.camkaze.AppState
import org.kaze.camkaze.R
import org.kaze.camkaze.Route
import org.kaze.camkaze.Services

@Composable
fun CamKazeApp(state: AppState) {
    val route = state.route
    KazeTheme(secure = route.isSecure) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(state.snackbar) },
                bottomBar = { if (route.showNav) KazeNavBar(state) },
            ) { pad ->
                Box(Modifier.padding(pad).fillMaxSize()) {
                    when (route) {
                        Route.Camera -> CameraScreen(state, secure = false)
                        Route.Gallery -> GalleryScreen(state)
                        is Route.Viewer -> ViewerScreen(route.photo, state)
                        Route.Lock -> LockScreen(state)
                        Route.SecureGallery -> SecureGalleryScreen(state)
                        Route.SecureCamera -> CameraScreen(state, secure = true)
                        is Route.SecureViewer -> SecureViewerScreen(route.item, state)
                    }
                }
            }
            state.unlockAction?.let { UnlockDialog(state, it) }
            SplashOverlay()
        }
        BackHandler(enabled = route != Route.Camera) { state.back() }
    }
}

/** Shows res/drawable/presplash.png for a moment right after the (plain dark) system splash. */
@Composable
private fun SplashOverlay() {
    var visible by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(900)
        visible = false
    }
    AnimatedVisibility(visible, exit = fadeOut()) {
        Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.presplash), contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }
}

@Composable
private fun KazeNavBar(state: AppState) {
    val selected = when (state.route) {
        Route.Camera -> 0
        Route.Gallery -> 1
        else -> 2
    }
    NavigationBar {
        NavigationBarItem(selected == 0, { state.go(Route.Camera) },
            { Icon(Icons.Filled.CameraAlt, null) }, label = { Text("Camera") })
        NavigationBarItem(selected == 1, { state.go(Route.Gallery) },
            { Icon(Icons.Filled.PhotoLibrary, null) }, label = { Text("Gallery") })
        NavigationBarItem(selected == 2,
            { state.go(if (Services.vault.isUnlocked) Route.SecureGallery else Route.Lock) },
            { Icon(Icons.Filled.Security, null) }, label = { Text("Secure") })
    }
}
