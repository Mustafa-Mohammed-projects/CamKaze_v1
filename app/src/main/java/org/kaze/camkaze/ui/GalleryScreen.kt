package org.kaze.camkaze.ui

import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kaze.camkaze.AppState
import org.kaze.camkaze.Route
import org.kaze.camkaze.Services
import org.kaze.camkaze.data.Importer
import org.kaze.camkaze.data.MediaRepo
import org.kaze.camkaze.data.Photo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(state: AppState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var photos by remember { mutableStateOf<List<Photo>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<Photo?>(null) }
    var deleteFor by remember { mutableStateOf<Photo?>(null) }
    var detailsFor by remember { mutableStateOf<Photo?>(null) }

    LaunchedEffect(state.galleryVersion) {
        photos = withContext(Dispatchers.IO) { try { MediaRepo.list(ctx) } catch (e: Exception) { emptyList() } }
        loaded = true
    }

    // Fallback when the item is not owned by this app (e.g. after reinstall): system prompt.
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        state.galleryVersion++
    }

    /** Overwrite the original's bytes (best effort), then delete it. */
    fun deleteOriginal(p: Photo) {
        scope.launch {
            withContext(Dispatchers.IO) { MediaRepo.overwrite(ctx, p) }
            try {
                if (MediaRepo.delete(ctx, p)) state.galleryVersion++
            } catch (e: SecurityException) {
                val pi = MediaStore.createDeleteRequest(ctx.contentResolver, listOf(p.uri))
                deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            } catch (e: Exception) {
                state.toast("Could not delete: ${e.message}")
            }
        }
    }

    /** read -> encrypt (RAM / streaming) -> .kaze -> overwrite + delete the original */
    fun moveToSecure(p: Photo) {
        state.toast("Securing...")
        scope.launch {
            val error = withContext(Dispatchers.IO) {
                try { Importer.toSecure(ctx, Services.secure, p); null } catch (e: Exception) { e.message ?: "unknown error" }
            }
            if (error != null) {
                state.toast("Could not secure: $error")
            } else {
                state.secureVersion++
                deleteOriginal(p)
                state.toast("Moved to Secure")
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Gallery") },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        Box(Modifier.fillMaxSize()) {
            PhotoGrid(
                items = photos, takenMs = { it.takenMs }, itemKey = { it.key },
                load = { MediaRepo.thumb(ctx, it) },
                onClick = { state.go(Route.Viewer(it)) },
                onLongClick = { sheetFor = it },
                placeholder = MaterialTheme.colorScheme.surfaceVariant,
                badge = { if (it.isVideo) fmtDuration(it.durationMs) else null },
            )
            if (loaded && photos.isEmpty()) {
                Text("No photos or videos yet", Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    sheetFor?.let { p ->
        ActionSheet(
            actions = listOf(
                SheetAction("Share", Icons.Filled.Share) {
                    try { MediaRepo.share(ctx, p) } catch (e: Exception) { state.toast("Share failed") }
                },
                SheetAction("Move to Secure", Icons.Filled.Lock) { state.requireUnlocked { moveToSecure(p) } },
                SheetAction("Delete", Icons.Filled.Delete) { deleteFor = p },
                SheetAction("Details", Icons.Filled.Info) { detailsFor = p },
            ),
            onDismiss = { sheetFor = null },
        )
    }
    deleteFor?.let { p ->
        ConfirmDialog("Delete?", "It will be overwritten and permanently deleted.",
            onConfirm = { deleteOriginal(p) }, onDismiss = { deleteFor = null })
    }
    detailsFor?.let { p -> InfoDialog("Details", MediaRepo.details(p)) { detailsFor = null } }
}

@Composable
fun ViewerScreen(photo: Photo, state: AppState) {
    val ctx = LocalContext.current
    val shareAction: @Composable RowScope.() -> Unit = {
        IconButton(onClick = { try { MediaRepo.share(ctx, photo) } catch (e: Exception) { state.toast("Share failed") } }) {
            Icon(Icons.Filled.Share, "Share")
        }
    }
    if (photo.isVideo) {
        ViewerFrame(photo.name, onBack = { state.back() }, actions = shareAction) {
            VideoPlayer(
                prepare = { it.setDataSource(ctx, photo.uri) },
                onError = { state.toast(it) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    } else {
        var bmp by remember(photo.key) { mutableStateOf<android.graphics.Bitmap?>(null) }
        LaunchedEffect(photo.key) {
            bmp = withContext(Dispatchers.IO) { try { MediaRepo.loadFull(ctx, photo, 2048) } catch (e: Exception) { null } }
        }
        ViewerFrame(photo.name, onBack = { state.back() }, actions = shareAction) {
            ZoomableImage(bmp, Modifier.fillMaxSize())
        }
    }
}
