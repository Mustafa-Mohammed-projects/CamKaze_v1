package org.kaze.camkaze.data

import android.content.Context
import android.media.MediaMetadataRetriever
import java.io.FileInputStream
import java.io.IOException

/** "Move to Secure": read the original, encrypt it, never write a plaintext copy. */
object Importer {
    fun toSecure(ctx: Context, repo: SecureRepo, p: Photo) {
        if (p.isVideo) importVideo(ctx, repo, p) else importPhoto(ctx, repo, p)
    }

    private fun importPhoto(ctx: Context, repo: SecureRepo, p: Photo) {
        val bytes = MediaRepo.readBytes(ctx, p)
        try {
            repo.savePhoto(bytes, Images.exifRotation(bytes), p.takenMs)
        } finally {
            bytes.fill(0)
        }
    }

    private fun importVideo(ctx: Context, repo: SecureRepo, p: Photo) {
        val mmr = MediaMetadataRetriever()
        val thumb: ByteArray
        val duration: Long
        try {
            mmr.setDataSource(ctx, p.uri)
            duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: p.durationMs
            thumb = Images.compressThumb(mmr.getFrameAtTime(0) ?: throw IOException("Cannot read video frame"))
        } finally {
            mmr.release()
        }
        ctx.contentResolver.openFileDescriptor(p.uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { repo.saveVideo(it, pfd.statSize, p.takenMs, duration, thumb) }
        } ?: throw IOException("Cannot open video")
    }
}
