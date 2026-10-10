package org.kaze.camkaze.data

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import org.kaze.camkaze.crypto.Chunked
import org.kaze.camkaze.crypto.Vault
import org.kaze.camkaze.crypto.holding
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom

data class SecureItem(
    val id: String, val file: File, val thumb: File, val size: Long,
    val kind: Int, val takenMs: Long, val durationMs: Long,
) {
    val isVideo: Boolean get() = kind == Payload.KIND_VIDEO
}

/**
 * Secure store. Only ciphertext reaches the disk:
 *   <id>.kaze    full photo (AES-GCM) or video (chunked AES-GCM)
 *   <id>.t.kaze  256px thumbnail carrying the metadata (kind, capture time, duration)
 * Names are random and every file gets the same fixed modification time, so neither the
 * names nor the file times reveal anything. Capture time lives inside the encryption.
 */
class SecureRepo(private val dir: File, private val vault: Vault) {
    private companion object { const val FIXED_MTIME = 1_000_000_000_000L }

    private val random = SecureRandom()
    private var cache: MutableList<SecureItem>? = null

    init { dir.mkdirs() }

    @Synchronized fun clearCache() { cache = null }

    @Synchronized
    fun list(): List<SecureItem> {
        cache?.let { return it.sortedByDescending { i -> i.takenMs } }
        val out = ArrayList<SecureItem>()
        for (t in dir.listFiles().orEmpty()) {
            if (!t.name.endsWith(".t.kaze")) continue
            val id = t.name.removeSuffix(".t.kaze")
            val full = File(dir, "$id.kaze")
            if (!full.isFile) continue
            try {
                val p = vault.decrypt(t.readBytes())
                val m = Payload.meta(p)
                p.fill(0)
                out += SecureItem(id, full, t, full.length(), m.kind, m.takenMs, m.extra)
            } catch (e: Exception) {
                // unreadable/corrupt entry: skip
            }
        }
        cache = out
        return out.sortedByDescending { it.takenMs }
    }

    // ------------------------------------------------------------------ save
    fun savePhoto(jpeg: ByteArray, rotation: Int, takenMs: Long): String = vault.holding {
        val thumb = Images.thumbJpeg(jpeg, rotation)
        val fullPlain = Payload.pack(Meta(Payload.KIND_PHOTO, rotation, takenMs, 0), jpeg)
        val thumbPlain = Payload.pack(Meta(Payload.KIND_PHOTO, 0, takenMs, 0), thumb)
        try {
            val id = newId()
            write(File(dir, "$id.kaze"), vault.encrypt(fullPlain))
            write(File(dir, "$id.t.kaze"), vault.encrypt(thumbPlain))
            register(id, Payload.KIND_PHOTO, takenMs, 0)
            id
        } finally {
            fullPlain.fill(0); thumbPlain.fill(0); thumb.fill(0)
        }
    }

    /** Streams [input] (plain video) through chunked encryption; [thumbJpeg] is a ready thumbnail. */
    fun saveVideo(input: InputStream, plainLen: Long, takenMs: Long, durationMs: Long, thumbJpeg: ByteArray): String =
        vault.holding {
            val id = newId()
            val full = File(dir, "$id.kaze")
            val tmp = File(dir, "$id.kaze.tmp")
            try {
                tmp.outputStream().buffered().use { Chunked.encrypt(vault, input, plainLen, it) }
                if (!tmp.renameTo(full)) throw IOException("Could not store video")
                full.setLastModified(FIXED_MTIME)
            } catch (e: Exception) {
                SecureDelete.shred(tmp)
                SecureDelete.shred(full)
                throw e
            }
            val thumbPlain = Payload.pack(Meta(Payload.KIND_VIDEO, 0, takenMs, durationMs), thumbJpeg)
            write(File(dir, "$id.t.kaze"), vault.encrypt(thumbPlain))
            thumbPlain.fill(0)
            register(id, Payload.KIND_VIDEO, takenMs, durationMs)
            id
        }

    /** Encrypt a just-recorded video file (caller shreds the plaintext file afterwards). */
    fun saveVideoFile(file: File): String {
        val mmr = MediaMetadataRetriever()
        val thumb: ByteArray
        val duration: Long
        try {
            mmr.setDataSource(file.absolutePath)
            duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val frame = mmr.getFrameAtTime(0) ?: throw IOException("Cannot read video frame")
            thumb = Images.compressThumb(frame)
        } finally {
            mmr.release()
        }
        return file.inputStream().use { saveVideo(it, file.length(), System.currentTimeMillis(), duration, thumb) }
    }

    // ------------------------------------------------------------------ read
    fun thumb(item: SecureItem): Bitmap {
        val p = vault.decrypt(item.thumb.readBytes())
        try { return Images.decodePayload(p, Images.THUMB) } finally { p.fill(0) }
    }

    fun full(item: SecureItem, maxSide: Int): Bitmap {
        val p = vault.decrypt(item.file.readBytes())
        try { return Images.decodePayload(p, maxSide) } finally { p.fill(0) }
    }

    fun openVideo(item: SecureItem): SecureVideoSource = SecureVideoSource(item.file, vault)

    /**
     * Decrypted copy for the explicit "share" action. Photos are re-written without any
     * EXIF (only orientation is kept); videos are streamed chunk by chunk.
     */
    fun exportTo(item: SecureItem, target: File) {
        try {
            if (item.isVideo) {
                Chunked.open(item.file, vault).use { r -> target.outputStream().buffered().use { r.copyTo(it) } }
            } else {
                val p = vault.decrypt(item.file.readBytes())
                try {
                    val jpeg = p.copyOfRange(Payload.HEADER, p.size)
                    val clean = JpegTools.sanitize(jpeg, Payload.meta(p).rotation)
                    target.writeBytes(clean)
                    jpeg.fill(0); clean.fill(0)
                } finally {
                    p.fill(0)
                }
            }
        } catch (e: Exception) {
            SecureDelete.shred(target)
            throw e
        }
    }

    // ------------------------------------------------------------------ delete
    @Synchronized
    fun delete(item: SecureItem) {
        SecureDelete.shred(item.file)
        SecureDelete.shred(item.thumb)
        cache?.removeAll { it.id == item.id }
    }

    /** Shred every secure file (the vault then destroys the keys = crypto-erase). */
    @Synchronized
    fun wipeAll() {
        SecureDelete.shredAll(dir)
        cache = null
    }

    // ------------------------------------------------------------------ helpers
    private fun newId() = ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private fun write(target: File, data: ByteArray) {
        val tmp = File(target.path + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(target)) throw IOException("Could not write ${target.name}")
        target.setLastModified(FIXED_MTIME)
    }

    @Synchronized
    private fun register(id: String, kind: Int, takenMs: Long, durationMs: Long) {
        val full = File(dir, "$id.kaze")
        cache?.add(SecureItem(id, full, File(dir, "$id.t.kaze"), full.length(), kind, takenMs, durationMs))
    }
}
