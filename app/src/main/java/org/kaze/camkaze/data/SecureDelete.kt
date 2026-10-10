package org.kaze.camkaze.data

import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom

/**
 * Best-effort shredding: overwrite (random, then zeros) with fsync, truncate, scrub the file
 * name, unlink. On flash storage (wear levelling / copy-on-write) an overwrite cannot be
 * guaranteed to hit the old blocks, so real protection is the encryption: every secure file is
 * ciphertext, and "Erase vault" also destroys the Keystore key (crypto-erase).
 */
object SecureDelete {
    private val rnd = SecureRandom()

    fun shred(file: File) {
        if (!file.exists()) return
        try {
            if (file.isFile) overwrite(file)
        } catch (_: Exception) {
            // still try to unlink below
        }
        val hex = ByteArray(8).also(rnd::nextBytes).joinToString("") { "%02x".format(it) }
        val renamed = File(file.parentFile, ".$hex")
        (if (file.renameTo(renamed)) renamed else file).delete()
    }

    fun shredAll(dir: File?) {
        dir?.listFiles()?.forEach { if (it.isDirectory) shredAll(it) else shred(it) }
    }

    private fun overwrite(file: File) {
        RandomAccessFile(file, "rw").use { raf ->
            val len = raf.length()
            val buf = ByteArray(64 * 1024)
            for (pass in 0..1) {
                if (pass == 0) rnd.nextBytes(buf) else buf.fill(0)
                raf.seek(0)
                var left = len
                while (left > 0) {
                    val n = minOf(left, buf.size.toLong()).toInt()
                    raf.write(buf, 0, n)
                    left -= n
                }
                raf.fd.sync()
            }
            raf.setLength(0)
            raf.fd.sync()
        }
    }
}
