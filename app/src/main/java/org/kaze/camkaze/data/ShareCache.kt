package org.kaze.camkaze.data

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.kaze.camkaze.PurgeJobService
import org.kaze.camkaze.Services
import java.io.File
import java.security.SecureRandom

/**
 * Sharing a secure item as a normal file needs a plaintext copy other apps can read.
 * It is written to a private cache folder (random name; photos are stripped of EXIF), exposed
 * through FileProvider with a temporary read grant, and then shredded by THREE independent
 * mechanisms: a JobScheduler job (runs even if the app is closed), app start, and app resume.
 */
object ShareCache {
    const val MAX_AGE_MS = 15 * 60 * 1000L
    private const val JOB_ID = 4242
    private val random = SecureRandom()

    private fun dir(ctx: Context) = File(ctx.cacheDir, "share").apply { mkdirs() }

    fun export(ctx: Context, item: SecureItem): Uri {
        val name = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val file = File(dir(ctx), (if (item.isVideo) "VID_" else "IMG_") + name + if (item.isVideo) ".mp4" else ".jpg")
        Services.secure.exportTo(item, file)
        schedulePurge(ctx)
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.share", file)
    }

    /** Opens the system share sheet (WhatsApp, Messenger, Bluetooth, Nearby Share, ...). */
    fun launch(ctx: Context, uri: Uri, video: Boolean) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (video) "video/mp4" else "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Share"))
    }

    private fun schedulePurge(ctx: Context) {
        try {
            val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, PurgeJobService::class.java))
                .setMinimumLatency(MAX_AGE_MS)
                .setOverrideDeadline(MAX_AGE_MS + 5 * 60 * 1000L)
                .build()
            ctx.getSystemService(JobScheduler::class.java).schedule(job)
        } catch (_: Exception) {
            // app start / resume purges still cover us
        }
    }

    /** Shred cached copies older than [maxAgeMs] (0 = all). */
    fun purge(ctx: Context, maxAgeMs: Long) {
        val now = System.currentTimeMillis()
        File(ctx.cacheDir, "share").listFiles()?.forEach {
            if (now - it.lastModified() >= maxAgeMs) SecureDelete.shred(it)
        }
    }
}
