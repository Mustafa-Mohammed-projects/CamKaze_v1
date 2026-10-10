package org.kaze.camkaze.data

import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import java.io.FileOutputStream
import java.security.SecureRandom
import java.text.DateFormat
import java.util.Date

data class Photo(
    val id: Long, val uri: Uri, val name: String, val takenMs: Long, val size: Long,
    val width: Int, val height: Int, val isVideo: Boolean = false, val durationMs: Long = 0,
) {
    /** Image and video ids come from different tables, so the key must include the type. */
    val key: String get() = (if (isVideo) "v" else "i") + id
}

/** Normal photos/videos: MediaStore albums Pictures/cam_kaze and Movies/cam_kaze. */
object MediaRepo {
    const val PHOTO_PATH = "Pictures/cam_kaze"
    const val VIDEO_PATH = "Movies/cam_kaze"
    private val IMAGES: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private val VIDEOS: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    fun list(ctx: Context): List<Photo> =
        (query(ctx, video = false) + query(ctx, video = true)).sortedByDescending { it.takenMs }

    private fun query(ctx: Context, video: Boolean): List<Photo> {
        val base = if (video) VIDEOS else IMAGES
        val rel = if (video) VIDEO_PATH else PHOTO_PATH
        val cols = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT,
        ) + (if (video) arrayOf(MediaStore.MediaColumns.DURATION) else emptyArray<String>())
        val out = ArrayList<Photo>()
        ctx.contentResolver.query(
            base, cols, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$rel/%"),
            "${MediaStore.MediaColumns.DATE_TAKEN} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val taken = c.getLong(2).takeIf { it > 0 } ?: (c.getLong(3) * 1000)
                out += Photo(id, ContentUris.withAppendedId(base, id), c.getString(1) ?: "media",
                    taken, c.getLong(4), c.getInt(5), c.getInt(6),
                    isVideo = video, durationMs = if (video) c.getLong(7) else 0L)
            }
        }
        return out
    }

    fun thumb(ctx: Context, p: Photo): Bitmap =
        ctx.contentResolver.loadThumbnail(p.uri, Size(Images.THUMB, Images.THUMB), null)

    /** Downscaled, EXIF-aware decode of a photo. */
    fun loadFull(ctx: Context, p: Photo, maxSide: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, p.uri)) { dec, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > maxSide) {
                val s = longest.toFloat() / maxSide
                dec.setTargetSize((info.size.width / s).toInt(), (info.size.height / s).toInt())
            }
        }

    fun readBytes(ctx: Context, p: Photo): ByteArray =
        ctx.contentResolver.openInputStream(p.uri)?.use { it.readBytes() }
            ?: throw java.io.IOException("Cannot open photo")

    /**
     * Best effort: overwrite the original's bytes in place (random, then zeros, fsync) before it
     * is deleted. Works for media this app created; returns false when Android does not allow it.
     */
    fun overwrite(ctx: Context, p: Photo): Boolean = try {
        ctx.contentResolver.openFileDescriptor(p.uri, "rw")?.use { pfd ->
            val os = FileOutputStream(pfd.fileDescriptor)
            val len = pfd.statSize
            val buf = ByteArray(64 * 1024)
            for (pass in 0..1) {
                if (pass == 0) SecureRandom().nextBytes(buf) else buf.fill(0)
                os.channel.position(0)
                var left = len
                while (left > 0) {
                    val n = minOf(left, buf.size.toLong()).toInt()
                    os.write(buf, 0, n)
                    left -= n
                }
                os.flush()
                pfd.fileDescriptor.sync()
            }
            true
        } ?: false
    } catch (e: Exception) {
        false
    }

    /** Throws SecurityException if the item is not owned by this app (caller falls back). */
    fun delete(ctx: Context, p: Photo): Boolean = ctx.contentResolver.delete(p.uri, null, null) > 0

    fun share(ctx: Context, p: Photo) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (p.isVideo) "video/mp4" else "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, p.uri)
            clipData = ClipData.newRawUri("", p.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Share"))
    }

    fun details(p: Photo): String {
        val res = if (p.width > 0) "${p.width} x ${p.height}" else "unknown"
        val date = DateFormat.getDateTimeInstance().format(Date(p.takenMs))
        val dur = if (p.isVideo) "\nDuration: ${fmtMs(p.durationMs)}" else ""
        return "Name: ${p.name}\nTaken: $date\nSize: ${fmtSize(p.size)}\nResolution: $res$dur\n" +
            "Album: ${if (p.isVideo) VIDEO_PATH else PHOTO_PATH}"
    }

    fun fmtSize(n: Long): String =
        if (n >= 1_048_576) "%.2f MB".format(n / 1_048_576.0) else "%.1f KB".format(n / 1024.0)

    fun fmtMs(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
