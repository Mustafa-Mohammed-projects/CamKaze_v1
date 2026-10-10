package org.kaze.camkaze.ui

import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/**
 * MediaPlayer + SurfaceView with play/pause and a seek bar. [prepare] sets the data source
 * (a content Uri for normal videos, a MediaDataSource for encrypted ones). [onRelease] runs
 * after the player is released (used to close the encrypted source and zero its key).
 */
@Composable
fun VideoPlayer(
    prepare: (MediaPlayer) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRelease: () -> Unit = {},
) {
    val mp = remember { MediaPlayer() }
    var ready by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var duration by remember { mutableIntStateOf(0) }
    var position by remember { mutableIntStateOf(0) }
    var ratio by remember { mutableFloatStateOf(16f / 9f) }
    var seeking by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        mp.setOnPreparedListener { duration = it.duration; ready = true; it.start(); playing = true }
        mp.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) ratio = w.toFloat() / h }
        mp.setOnCompletionListener { playing = false; position = duration }
        mp.setOnErrorListener { _, what, extra -> onError("Playback error ($what/$extra)"); true }
        try {
            prepare(mp)
            mp.prepareAsync()
        } catch (e: Exception) {
            onError(e.message ?: "Cannot play video")
        }
        onDispose {
            try { mp.release() } catch (_: Exception) {}
            onRelease()
        }
    }

    LaunchedEffect(ready) {
        while (ready) {
            if (!seeking) position = try { mp.currentPosition } catch (e: Exception) { 0 }
            delay(250)
        }
    }

    Column(modifier.background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { c ->
                    SurfaceView(c).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(h: SurfaceHolder) {
                                try { mp.setDisplay(h) } catch (_: Exception) {}
                            }

                            override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hh: Int) {}

                            override fun surfaceDestroyed(h: SurfaceHolder) {
                                try { mp.setDisplay(null) } catch (_: Exception) {}
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                try {
                    if (playing) {
                        mp.pause(); playing = false
                    } else {
                        if (duration > 0 && position >= duration - 200) mp.seekTo(0)
                        mp.start(); playing = true
                    }
                } catch (_: Exception) {}
            }, enabled = ready) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/Pause", tint = Color.White)
            }
            Slider(
                value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                onValueChange = { seeking = true; position = it.toInt() },
                onValueChangeFinished = {
                    try { mp.seekTo(position) } catch (_: Exception) {}
                    seeking = false
                },
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                enabled = ready, modifier = Modifier.weight(1f),
            )
            Text("${fmtDuration(position.toLong())} / ${fmtDuration(duration.toLong())}",
                color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(8.dp))
    }
}
