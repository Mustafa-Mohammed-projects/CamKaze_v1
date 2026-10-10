@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package org.kaze.camkaze.ui

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kaze.camkaze.AppState
import org.kaze.camkaze.Services
import org.kaze.camkaze.crypto.UnlockResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

// ------------------------------------------------------------------ bottom sheet
data class SheetAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** Material 3 modal bottom sheet opened on long-press. */
@Composable
fun ActionSheet(actions: List<SheetAction>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        actions.forEach { a ->
            ListItem(
                headlineContent = { Text(a.label) },
                leadingContent = { Icon(a.icon, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { onDismiss(); a.onClick() },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ------------------------------------------------------------------ dialogs
@Composable
fun ConfirmDialog(title: String, text: String, confirmLabel: String = "Delete",
                  onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun InfoDialog(title: String, text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun PasswordField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean = true) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, enabled = enabled,
        singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Password prompt used by "Move to Secure" when the vault is locked. */
@Composable
fun UnlockDialog(state: AppState, action: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy || pw.isEmpty()) return
        val chars = pw.toCharArray(); pw = ""; busy = true; error = null
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                try { Services.vault.unlock(chars) } catch (e: Exception) { null } finally { chars.fill('\u0000') }
            }
            busy = false
            when (result) {
                UnlockResult.Success -> { state.unlockAction = null; action() }
                is UnlockResult.Throttled -> error = "Too many attempts. Wait ${result.waitMs / 1000 + 1} s."
                is UnlockResult.Wrong -> error = "Wrong password" +
                    (result.attemptsBeforeWipe?.let { " ($it attempts left before ALL secure files are erased)" } ?: "")
                UnlockResult.Wiped -> { state.unlockAction = null; state.toast("Vault erased after too many failed attempts"); state.go(org.kaze.camkaze.Route.Lock) }
                UnlockResult.DeviceKeyLost -> error = "Device key missing: this vault cannot be opened."
                null -> error = "Unlock error"
            }
        }
    }

    AlertDialog(
        onDismissRequest = { state.unlockAction = null },
        title = { Text("Unlock Secure mode") },
        text = {
            Column {
                PasswordField(pw, { pw = it }, "Password", enabled = !busy)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = ::submit, enabled = !busy) { Text(if (busy) "Working..." else "Unlock") } },
        dismissButton = { TextButton(onClick = { state.unlockAction = null }) { Text("Cancel") } },
    )
}

// ------------------------------------------------------------------ thumbnails grid
private val dayFormat get() = SimpleDateFormat("EEE, d MMM yyyy", Locale.ENGLISH)

/**
 * Items grouped by capture day (newest first). LazyVerticalGrid only composes visible cells and
 * each cell decodes just a small thumbnail => bounded memory (OOM prevention).
 * [badge] returns e.g. a duration for videos (null for photos).
 */
@Composable
fun <T> PhotoGrid(
    items: List<T>,
    takenMs: (T) -> Long,
    itemKey: (T) -> Any,
    load: suspend (T) -> Bitmap?,
    onClick: (T) -> Unit,
    onLongClick: (T) -> Unit,
    placeholder: Color,
    badge: (T) -> String? = { null },
) {
    val groups = remember(items) {
        val f = dayFormat
        items.groupBy { f.format(Date(takenMs(it))) }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        groups.forEach { (day, list) ->
            item(key = "h_$day", span = { GridItemSpan(maxLineSpan) }) {
                Text(day, style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
            items(list, key = itemKey) { item ->
                AsyncThumb(item, load, { onClick(item) }, { onLongClick(item) }, placeholder, badge(item))
            }
        }
    }
}

@Composable
private fun <T> AsyncThumb(
    item: T, load: suspend (T) -> Bitmap?,
    onClick: () -> Unit, onLongClick: () -> Unit, placeholder: Color, badge: String?,
) {
    var bmp by remember(item) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item) {
        bmp = withContext(Dispatchers.IO) { try { load(item) } catch (e: Exception) { null } }
    }
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).background(placeholder)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        bmp?.let {
            Image(it.asImageBitmap(), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (badge != null) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(4.dp)
                    .background(Color(0x99000000)).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(14.dp))
                Text(badge, color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ------------------------------------------------------------------ viewer
@Composable
fun ViewerFrame(
    title: String, onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title, maxLines = 1) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        )
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), content = content)
    }
}

@Composable
fun ZoomableImage(bitmap: Bitmap?, modifier: Modifier = Modifier) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier.clipToBounds().pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(1f, 5f)
                offset = if (scale <= 1f) Offset.Zero else offset + pan
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null) CircularProgressIndicator()
        else Image(
            bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer(
                scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
        )
    }
}
