package org.kaze.camkaze.data

import android.media.MediaDataSource
import org.kaze.camkaze.crypto.Chunked
import org.kaze.camkaze.crypto.Vault
import java.io.File
import java.io.IOException

/** Lets MediaPlayer play an encrypted video: chunks are decrypted on demand, in RAM only. */
class SecureVideoSource(file: File, vault: Vault) : MediaDataSource() {
    private val reader = Chunked.open(file, vault)

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        try { reader.read(position, buffer, offset, size) } catch (e: Exception) { throw IOException(e) }

    override fun getSize(): Long = reader.size

    override fun close() = reader.close()
}
