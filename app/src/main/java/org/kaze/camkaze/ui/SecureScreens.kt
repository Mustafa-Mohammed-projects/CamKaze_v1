package org.kaze.camkaze.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kaze.camkaze.AppState
import org.kaze.camkaze.KazeApp
import org.kaze.camkaze.Route
import org.kaze.camkaze.Services
import org.kaze.camkaze.crypto.UnlockResult
import org.kaze.camkaze.crypto.Vault
import org.kaze.camkaze.data.MediaRepo
import org.kaze.camkaze.data.SecureItem
import org.kaze.camkaze.data.ShareCache
import java.text.DateFormat
import java.util.Date

// ------------------------------------------------------------------ create / unlock
@Composable
fun LockScreen(state: AppState) {
    val ctx = LocalContext.current
    val vault = Services.vault
    val scope = rememberCoroutineScope()
    var legacy by remember { mutableStateOf(KazeApp.legacyExists(ctx)) }
    var setup by remember { mutableStateOf(!vault.isConfigured) }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var wipeOpt by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var waitMs by remember { mutableLongStateOf(0L) }
    var failed by remember { mutableIntStateOf(0) }
    var wipeOn by remember { mutableStateOf(false) }
    var confirmErase by remember { mutableStateOf(false) }

    LaunchedEffect(setup) {
        while (true) {
            if (!setup && vault.isConfigured) {
                waitMs = vault.throttleRemainingMs()
                failed = vault.failedAttempts
                wipeOn = vault.wipeEnabled
            } else {
                waitMs = 0; failed = 0
            }
            delay(1000)
        }
    }

    fun submit() {
        if (busy || waitMs > 0) return
        if (setup) {
            if (pw.length < Vault.MIN_PASSWORD_LEN) { error = "Use at least ${Vault.MIN_PASSWORD_LEN} characters"; return }
            if (pw != pw2) { error = "Passwords do not match"; return }
        } else if (pw.isEmpty()) return
        val chars = pw.toCharArray(); pw = ""; pw2 = ""; busy = true; error = null
        scope.launch {
            var result: UnlockResult? = null
            var failure: String? = null
            withContext(Dispatchers.Default) {
                try {
                    if (setup) { vault.create(chars, wipeOpt); result = UnlockResult.Success }
                    else result = vault.unlock(chars)
                } catch (e: Exception) {
                    failure = e.message ?: "Unexpected error"
                } finally {
                    chars.fill('\u0000')
                }
                Unit
            }
            busy = false
            when (val r = result) {
                UnlockResult.Success -> state.go(Route.SecureGallery)
                is UnlockResult.Wrong -> error = "Wrong password" +
                    (r.attemptsBeforeWipe?.let { " - $it attempts left before ALL secure files are erased" } ?: "")
                is UnlockResult.Throttled -> waitMs = r.waitMs
                UnlockResult.Wiped -> { setup = true; error = "Too many failed attempts: the vault was erased." }
                UnlockResult.DeviceKeyLost -> error = "The device key of this vault is missing (reinstalled or restored app). It cannot be opened; erase it to start over."
                null -> error = failure
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Lock, null, tint = SecureAccent, modifier = Modifier.size(48.dp))

        if (legacy) {
            Text("An older vault format was found. This version cannot open it.",
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
            Button(onClick = {
                scope.launch { withContext(Dispatchers.IO) { KazeApp.purgeLegacy(ctx) }; legacy = false }
            }) { Text("Erase old vault and continue") }
        } else {
            Text(if (setup) "Create Secure Password" else "Secure Vault",
                style = MaterialTheme.typography.headlineSmall, color = SecureAccent)
            Text(
                if (setup) "At least ${Vault.MIN_PASSWORD_LEN} characters. There is NO way to recover it: if you forget it, secured files are lost. " +
                    "The key is also bound to THIS phone's hardware keystore."
                else "Enter your password to unlock.",
                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PasswordField(pw, { pw = it }, "Password", enabled = !busy && waitMs <= 0)
            if (setup) {
                PasswordField(pw2, { pw2 = it }, "Confirm password", enabled = !busy)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(wipeOpt, { wipeOpt = it })
                    Text("Erase all secure files after ${Vault.MAX_ATTEMPTS} failed unlock attempts",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
            if (!setup) {
                if (waitMs > 0) {
                    val s = (waitMs + 999) / 1000
                    Text("Too many failed attempts. Try again in %d:%02d".format(s / 60, s % 60),
                        color = MaterialTheme.colorScheme.error)
                }
                if (failed > 0) {
                    Text("Failed attempts: $failed" +
                        if (wipeOn) " (all secure files are erased at ${Vault.MAX_ATTEMPTS})" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(onClick = ::submit, enabled = !busy && waitMs <= 0) {
                Text(if (busy) "Working..." else if (setup) "Create vault" else "Unlock")
            }
            if (!setup) {
                TextButton(onClick = { confirmErase = true }) { Text("Forgot password? Erase vault") }
            }
        }
    }

    if (confirmErase) {
        ConfirmDialog("Erase the whole vault?",
            "All secure photos and videos will be permanently destroyed and the keys deleted. This cannot be undone.",
            confirmLabel = "Erase",
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) { vault.wipeNow() }
                    state.secureVersion++
                    setup = true; error = null
                    state.toast("Vault erased")
                }
            },
            onDismiss = { confirmErase = false })
    }
}

// ------------------------------------------------------------------ secure gallery
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecureGalleryScreen(state: AppState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<SecureItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<SecureItem?>(null) }
    var deleteFor by remember { mutableStateOf<SecureItem?>(null) }
    var detailsFor by remember { mutableStateOf<SecureItem?>(null) }
    var shareFor by remember { mutableStateOf<SecureItem?>(null) }
    var settings by remember { mutableStateOf(false) }

    LaunchedEffect(state.secureVersion) {
        items = withContext(Dispatchers.IO) { try { Services.secure.list() } catch (e: Exception) { emptyList() } }
        loaded = true
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Secure Vault", color = SecureAccent) },
            actions = {
                IconButton(onClick = { state.go(Route.SecureCamera) }) {
                    Icon(Icons.Filled.CameraAlt, "Secure camera", tint = SecureAccent)
                }
                IconButton(onClick = { settings = true }) { Icon(Icons.Filled.Settings, "Settings", tint = SecureAccent) }
                IconButton(onClick = { state.lockNow() }) { Icon(Icons.Filled.Lock, "Lock now", tint = SecureAccent) }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        )
        Box(Modifier.fillMaxSize()) {
            PhotoGrid(
                items = items, takenMs = { it.takenMs }, itemKey = { it.id },
                load = { Services.secure.thumb(it) },          // decrypted in RAM only
                onClick = { state.go(Route.SecureViewer(it)) },
                onLongClick = { sheetFor = it },
                placeholder = MaterialTheme.colorScheme.surfaceVariant,
                badge = { if (it.isVideo) fmtDuration(it.durationMs) else null },
            )
            if (loaded && items.isEmpty()) {
                Text("Vault is empty", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    sheetFor?.let { item ->
        ActionSheet(
            actions = listOf(
                SheetAction("Share as normal file", Icons.Filled.Share) { shareFor = item },
                SheetAction("Delete (shred)", Icons.Filled.Delete) { deleteFor = item },
                SheetAction("Details", Icons.Filled.Info) { detailsFor = item },
            ),
            onDismiss = { sheetFor = null },
        )
    }
    deleteFor?.let { item ->
        ConfirmDialog("Delete securely?", "The encrypted file will be overwritten and permanently deleted.",
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) { Services.secure.delete(item) }
                    state.secureVersion++; state.toast("Deleted")
                }
            },
            onDismiss = { deleteFor = null })
    }
    detailsFor?.let { item -> InfoDialog("Details", secureDetails(item)) { detailsFor = null } }
    shareFor?.let { item ->
        ConfirmShareDialog(onConfirm = { shareSecure(ctx, scope, state, item) }, onDismiss = { shareFor = null })
    }
    if (settings) SettingsDialog(state, onDismiss = { settings = false })
}

private fun secureDetails(i: SecureItem): String =
    "Type: ${if (i.isVideo) "Video" else "Photo"}\nID: ${i.id.take(8)}...\n" +
        "Captured: ${DateFormat.getDateTimeInstance().format(Date(i.takenMs))}\n" +
        (if (i.isVideo) "Duration: ${fmtDuration(i.durationMs)}\n" else "") +
        "Encrypted size: ${MediaRepo.fmtSize(i.size)}\nCipher: AES-256-GCM (.kaze v2)"

@Composable
private fun ConfirmShareDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = "Share as normal file?",
        text = "A decrypted copy (photos without EXIF data) will be sent to the app you choose. " +
            "It is shredded automatically after about 15 minutes. The encrypted original stays in the vault.",
        confirmLabel = "Share", onConfirm = onConfirm, onDismiss = onDismiss,
    )
}

/** Decrypt on a background thread, then open the system share sheet. */
private fun shareSecure(ctx: Context, scope: CoroutineScope, state: AppState, item: SecureItem) {
    state.toast("Preparing...")
    scope.launch {
        val uri = withContext(Dispatchers.IO) { try { ShareCache.export(ctx, item) } catch (e: Exception) { null } }
        if (uri == null) state.toast("Could not prepare file") else ShareCache.launch(ctx, uri, item.isVideo)
    }
}

// ------------------------------------------------------------------ settings
@Composable
private fun SettingsDialog(state: AppState, onDismiss: () -> Unit) {
    val vault = Services.vault
    val scope = rememberCoroutineScope()
    var wipe by remember { mutableStateOf(vault.wipeEnabled) }
    var confirmErase by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Secure settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Erase all secure files after ${Vault.MAX_ATTEMPTS} failed unlock attempts",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(wipe, { v ->
                        try { vault.wipeEnabled = v; wipe = v } catch (e: Exception) { state.toast("Could not change setting") }
                    })
                }
                Text("Failed attempts so far: ${vault.failedAttempts}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { confirmErase = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Erase vault now")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
    if (confirmErase) {
        ConfirmDialog("Erase the whole vault?",
            "All secure photos and videos are shredded and the keys destroyed. This cannot be undone.",
            confirmLabel = "Erase",
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) { vault.wipeNow() }
                    onDismiss()
                    state.secureVersion++
                    state.go(Route.Lock)
                    state.toast("Vault erased")
                }
            },
            onDismiss = { confirmErase = false })
    }
}

// ------------------------------------------------------------------ secure viewer
@Composable
fun SecureViewerScreen(item: SecureItem, state: AppState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmShare by remember { mutableStateOf(false) }

    val actions: @Composable RowScope.() -> Unit = {
        IconButton(onClick = { confirmShare = true }) { Icon(Icons.Filled.Share, "Share", tint = SecureAccent) }
        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete", tint = SecureAccent) }
    }

    if (item.isVideo) {
        ViewerFrame(if (item.isVideo) "Secure video" else "Secure photo", onBack = { state.back() }, actions = actions) {
            // chunks are decrypted on demand in RAM; the source (and its key copy) is closed on release
            var source by remember(item.id) { mutableStateOf<org.kaze.camkaze.data.SecureVideoSource?>(null) }
            LaunchedEffect(item.id) {
                source = try { withContext(Dispatchers.IO) { Services.secure.openVideo(item) } } catch (e: Exception) {
                    state.toast("Cannot open video: ${e.message}"); null
                }
            }
            source?.let { src ->
                VideoPlayer(
                    prepare = { it.setDataSource(src) },
                    onError = { state.toast(it) },
                    onRelease = { try { src.close() } catch (_: Exception) {} },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    } else {
        var bmp by remember(item.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
        LaunchedEffect(item.id) {
            // NOTE: the bitmap is NOT recycled manually: Compose/RenderThread may still draw it
            // (recycling here caused a "recycled bitmap" crash). The GC frees it after we leave.
            bmp = withContext(Dispatchers.IO) { try { Services.secure.full(item, 2048) } catch (e: Exception) { null } }
        }
        ViewerFrame("Secure photo", onBack = { state.back() }, actions = actions) {
            ZoomableImage(bmp, Modifier.fillMaxSize())
        }
    }

    if (confirmShare) {
        ConfirmShareDialog(onConfirm = { shareSecure(ctx, scope, state, item) }, onDismiss = { confirmShare = false })
    }
    if (confirmDelete) {
        ConfirmDialog("Delete securely?", "The encrypted file will be overwritten and permanently deleted.",
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) { Services.secure.delete(item) }
                    state.secureVersion++; state.toast("Deleted"); state.back()
                }
            },
            onDismiss = { confirmDelete = false })
    }
}
