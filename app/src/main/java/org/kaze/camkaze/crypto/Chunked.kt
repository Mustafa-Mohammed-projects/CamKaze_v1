package org.kaze.camkaze.crypto

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Streaming + random-access AES-GCM for videos (too big for one in-memory blob).
 *
 *   "KAZV" | 2 | fileSalt(16) | baseNonce(12) | chunkSize(int32) | plainLen(int64)    (45 bytes)
 *   then per chunk:  AES-GCM(ciphertext) | tag(16)      (chunkSize plaintext bytes, last one shorter)
 *
 * nonce(i) = baseNonce XOR i ; AAD = header || i  => chunks cannot be reordered/dropped/truncated.
 * Random access lets MediaPlayer seek without ever writing plaintext to disk.
 */
object Chunked {
    const val CHUNK = 1 shl 20
    const val HEADER = 4 + 1 + 16 + 12 + 4 + 8
    private const val TAG = 16
    private val MAGIC = byteArrayOf(0x4B, 0x41, 0x5A, 0x56) // "KAZV"

    private fun nonce(base: ByteArray, index: Long): ByteArray {
        val n = base.copyOf()
        for (k in 0 until 8) n[11 - k] = (n[11 - k].toInt() xor ((index ushr (8 * k)).toInt() and 0xFF)).toByte()
        return n
    }

    private fun cipher(mode: Int, key: ByteArray, header: ByteArray, base: ByteArray, index: Long): Cipher {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG * 8, nonce(base, index)))
        c.updateAAD(header)
        c.updateAAD(ByteBuffer.allocate(8).putLong(index).array())
        return c
    }

    fun encrypt(vault: Vault, input: InputStream, plainLen: Long, out: OutputStream, chunk: Int = CHUNK) {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val base = ByteArray(12).also(rnd::nextBytes)
        val header = ByteBuffer.allocate(HEADER).put(MAGIC).put(2).put(salt).put(base)
            .putInt(chunk).putLong(plainLen).array()
        val key = vault.fileKey(salt)
        val buf = ByteArray(chunk)
        try {
            out.write(header)
            var done = 0L
            var index = 0L
            while (done < plainLen) {
                val want = minOf(chunk.toLong(), plainLen - done).toInt()
                var got = 0
                while (got < want) {
                    val n = input.read(buf, got, want - got)
                    if (n < 0) throw IOException("Unexpected end of video data")
                    got += n
                }
                out.write(cipher(Cipher.ENCRYPT_MODE, key, header, base, index).doFinal(buf, 0, want))
                done += want
                index++
            }
        } finally {
            key.fill(0)
            buf.fill(0)
        }
    }

    /** Opens an encrypted video for random-access reading. */
    fun open(file: File, vault: Vault): Reader {
        val raf = RandomAccessFile(file, "r")
        try {
            val header = ByteArray(HEADER)
            raf.readFully(header)
            val bb = ByteBuffer.wrap(header)
            val magic = ByteArray(4).also(bb::get)
            if (!magic.contentEquals(MAGIC) || bb.get().toInt() != 2) throw IOException("Not a .kaze video")
            val salt = ByteArray(16).also(bb::get)
            val base = ByteArray(12).also(bb::get)
            val chunk = bb.getInt()
            val size = bb.getLong()
            if (chunk <= 0 || size < 0) throw IOException("Corrupted video header")
            val chunks = (size + chunk - 1) / chunk
            if (raf.length() != HEADER + size + chunks * TAG) throw IOException("Corrupted or truncated video")
            return Reader(raf, header, base, chunk, size, vault.fileKey(salt))
        } catch (e: Exception) {
            raf.close()
            throw e
        }
    }

    /** Thread-safe random-access reader over an encrypted video file. */
    class Reader internal constructor(
        private val raf: RandomAccessFile,
        private val header: ByteArray,
        private val base: ByteArray,
        private val chunk: Int,
        val size: Long,
        private val key: ByteArray,
    ) : Closeable {
        private var cachedIndex = -1L
        private var cachedPlain: ByteArray? = null

        @Synchronized
        private fun plainChunk(i: Long): ByteArray {
            if (i == cachedIndex) cachedPlain?.let { return it }
            val plainLen = minOf(chunk.toLong(), size - i * chunk).toInt()
            val enc = ByteArray(plainLen + TAG)
            raf.seek(HEADER + i * (chunk + TAG).toLong())
            raf.readFully(enc)
            val plain = try {
                cipher(Cipher.DECRYPT_MODE, key, header, base, i).doFinal(enc)
            } catch (e: GeneralSecurityException) {
                throw IOException("Video chunk failed authentication", e)
            }
            cachedPlain?.fill(0)
            cachedPlain = plain
            cachedIndex = i
            return plain
        }

        /** Returns bytes read, or -1 at end of file. */
        @Synchronized
        fun read(pos: Long, dst: ByteArray, off: Int, len: Int): Int {
            if (pos >= size) return -1
            var done = 0
            var p = pos
            while (done < len && p < size) {
                val plain = plainChunk(p / chunk)
                val inChunk = (p % chunk).toInt()
                val n = minOf(len - done, plain.size - inChunk)
                System.arraycopy(plain, inChunk, dst, off + done, n)
                done += n
                p += n
            }
            return done
        }

        fun copyTo(out: OutputStream) {
            val buf = ByteArray(64 * 1024)
            var pos = 0L
            while (pos < size) {
                val n = read(pos, buf, 0, buf.size)
                if (n <= 0) break
                out.write(buf, 0, n)
                pos += n
            }
            buf.fill(0)
        }

        @Synchronized
        override fun close() {
            key.fill(0)
            cachedPlain?.fill(0)
            cachedPlain = null
            raf.close()
        }
    }
}
