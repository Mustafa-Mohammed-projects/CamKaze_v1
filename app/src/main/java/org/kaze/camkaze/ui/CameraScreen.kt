package org.kaze.camkaze.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.kaze.camkaze.AppState
import org.kaze.camkaze.Route
import org.kaze.camkaze.Services
import org.kaze.camkaze.data.MediaRepo
import org.kaze.camkaze.data.SecureDelete
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class RecHolder { var rec: Recording? = null }

/**
 * One camera screen, two modes, photo + video, pinch / slider zoom.
 *  normal: CameraX writes straight into MediaStore (Pictures|Movies/cam_kaze).
 *  secure photo: JPEG arrives as an in-memory ImageProxy -> encrypted -> only ciphertext on disk.
 *  secure video: CameraX can only record to a seekable file, so the clip is recorded into the
 *                app-private no-backup folder, streamed through chunked AES-GCM, and the plaintext
 *                file is shredded immediately (also at the next app start if interrupted).
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun CameraScreen(state: AppState, secure: Boolean) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val accent = if (secure) SecureAccent else NormalAccent
    val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
        Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = it[Manifest.permission.CAMERA] == true
    }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(perms) }

    val previewView = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val recorder = remember {
        Recorder.Builder().setQualitySelector(
            QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build()
    }
    val videoCapture = remember { VideoCapture.withOutput(recorder) }
    val rec = remember { RecHolder() }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var backCamera by remember { mutableStateOf(true) }
    var videoMode by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var recSeconds by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var minZoom by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(1f) }

    // stop an unfinished recording if the screen is left (the finalize listener still saves it)
    DisposableEffect(Unit) { onDispose { rec.rec?.stop() } }

    DisposableEffect(granted, backCamera, videoMode, owner) {
        var provider: ProcessCameraProvider? = null
        var live: LiveData<ZoomState>? = null
        val observer = Observer<ZoomState> { z -> zoom = z.zoomRatio; minZoom = z.minZoomRatio; maxZoom = z.maxZoomRatio }
        if (granted) {
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val selector = if (backCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                val second: UseCase = if (videoMode) videoCapture else imageCapture
                try {
                    provider?.unbindAll()
                    val cam = provider?.bindToLifecycle(owner, selector, preview, second)
                    camera = cam
                    live = cam?.cameraInfo?.zoomState
                    live?.observe(owner, observer)
                } catch (e: Exception) {
                    state.toast("Camera error: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(ctx))
        }
        onDispose {
            live?.removeObserver(observer)
            provider?.unbindAll()
            camera = null
        }
    }

    // flash for photos, torch for video
    LaunchedEffect(flash, backCamera, videoMode, camera) {
        imageCapture.flashMode = if (!videoMode && flash && backCamera) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        camera?.cameraControl?.enableTorch(videoMode && flash && backCamera)
    }

    fun hasAudio() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    // ------------------------------------------------------------------ photo
    fun capturePhoto() {
        if (busy) return
        busy = true
        if (secure) {
            imageCapture.takePicture(Services.io, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    var bytes: ByteArray? = null
                    try {
                        val rotation = image.imageInfo.rotationDegrees
                        val buf = image.planes[0].buffer
                        bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                        Services.secure.savePhoto(bytes, rotation, System.currentTimeMillis())
                        state.post { state.secureVersion++; state.toast("Saved securely"); busy = false }
                    } catch (e: Exception) {
                        state.post { state.toast("Secure save failed: ${e.message}"); busy = false }
                    } finally {
                        bytes?.fill(0)
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    state.post { state.toast("Capture failed"); busy = false }
                }
            })
        } else {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "CamKaze_${stamp()}.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, MediaRepo.PHOTO_PATH)
            }
            val options = ImageCapture.OutputFileOptions.Builder(
                ctx.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
            imageCapture.takePicture(options, ContextCompat.getMainExecutor(ctx),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        state.galleryVersion++; state.toast("Photo saved"); busy = false
                    }

                    override fun onError(exception: ImageCaptureException) {
                        state.toast("Capture failed: ${exception.message}"); busy = false
                    }
                })
        }
    }

    // ------------------------------------------------------------------ video
    fun startVideo() {
        if (rec.rec != null) return
        val pending: PendingRecording
        val file: File? = if (secure) {
            val name = ByteArray(6).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
            File(Services.tmpDir, "r$name.mp4")
        } else null
        if (file != null) {
            Services.vault.hold()   // keep the key alive until the clip is encrypted, even if the app pauses
            pending = recorder.prepareRecording(ctx, FileOutputOptions.Builder(file).build())
        } else {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "CamKaze_${stamp()}.mp4")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, MediaRepo.VIDEO_PATH)
            }
            pending = recorder.prepareRecording(ctx, MediaStoreOutputOptions.Builder(
                ctx.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build())
        }
        if (hasAudio()) pending.withAudioEnabled()
        recording = true
        recSeconds = 0
        try {
            rec.rec = pending.start(ContextCompat.getMainExecutor(ctx)) { event ->
                when (event) {
                    is VideoRecordEvent.Status ->
                        recSeconds = (event.recordingStats.recordedDurationNanos / 1_000_000_000L).toInt()
                    is VideoRecordEvent.Finalize -> {
                        rec.rec = null
                        recording = false
                        recSeconds = 0
                        val failed = event.hasError()
                        if (file != null) {
                            Services.io.execute {
                                try {
                                    if (failed) throw IOException("Recording failed")
                                    Services.secure.saveVideoFile(file)
                                    state.post { state.secureVersion++; state.toast("Video saved securely") }
                                } catch (e: Exception) {
                                    state.post { state.toast("Secure video failed: ${e.message}") }
                                } finally {
                                    SecureDelete.shred(file)
                                    Services.vault.release()
                                }
                            }
                        } else if (failed) {
                            state.toast("Recording failed")
                        } else {
                            state.galleryVersion++
                            state.toast("Video saved")
                        }
                    }
                    else -> {}
                }
            }
        } catch (e: Exception) {
            recording = false
            file?.let { SecureDelete.shred(it); Services.vault.release() }
            state.toast("Cannot record: ${e.message}")
        }
    }

    fun onShutter() {
        if (videoMode) {
            if (recording) rec.rec?.stop() else startVideo()
        } else {
            capturePhoto()
        }
    }

    // ------------------------------------------------------------------ UI
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Camera permission is required", color = Color.White)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { permLauncher.launch(perms) }) { Text("Grant permission") }
            }
        }

        // pinch to zoom
        Box(Modifier.fillMaxSize().pointerInput(camera) {
            detectTransformGestures { _, _, z, _ ->
                camera?.cameraControl?.setZoomRatio((zoom * z).coerceIn(minZoom, maxZoom))
            }
        })

        if (secure) {
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth().background(Color(0x992A1512)).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { state.go(Route.SecureGallery) }, enabled = !recording) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = SecureAccent)
                }
                Text(if (videoMode) "SECURE VIDEO - encrypted right after recording" else "SECURE MODE - encrypted in RAM",
                    style = MaterialTheme.typography.labelMedium, color = SecureAccent)
            }
        }

        // Photo | Video switch (+ recording timer)
        Row(Modifier.align(Alignment.TopCenter).padding(top = if (secure) 56.dp else 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (recording) {
                Text("REC  %d:%02d".format(recSeconds / 60, recSeconds % 60), color = Color.Red,
                    style = MaterialTheme.typography.titleMedium)
            } else {
                FilterChip(selected = !videoMode, onClick = { videoMode = false }, label = { Text("Photo") })
                FilterChip(selected = videoMode, onClick = { videoMode = true }, label = { Text("Video") })
            }
        }

        // zoom: ratio chip (tap = reset) + slider
        if (maxZoom - minZoom > 0.05f) {
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 128.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                AssistChip(onClick = { camera?.cameraControl?.setZoomRatio(1f.coerceIn(minZoom, maxZoom)) },
                    label = { Text("%.1fx".format(zoom)) })
                Slider(
                    value = zoom.coerceIn(minZoom, maxZoom),
                    onValueChange = { camera?.cameraControl?.setZoomRatio(it) },
                    valueRange = minZoom..maxZoom,
                    modifier = Modifier.fillMaxWidth(0.75f),
                )
            }
        }

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 28.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { flash = !flash }, enabled = backCamera) {
                Icon(if (flash) Icons.Filled.FlashOn else Icons.Filled.FlashOff, "Flash", tint = Color.White)
            }
            Box(
                Modifier.size(76.dp).clip(CircleShape)
                    .background(if (videoMode) Color.Red else if (busy) Color.Gray else accent)
                    .border(4.dp, Color.White, CircleShape)
                    .clickable(enabled = granted && (!busy || recording)) { onShutter() },
                contentAlignment = Alignment.Center,
            ) {
                if (recording) Box(Modifier.size(26.dp).background(Color.White, RoundedCornerShape(4.dp)))
            }
            IconButton(onClick = { backCamera = !backCamera }, enabled = !recording) {
                Icon(Icons.Filled.FlipCameraAndroid, "Switch camera", tint = Color.White)
            }
        }
    }
}
