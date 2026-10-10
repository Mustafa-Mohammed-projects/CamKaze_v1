package org.kaze.camkaze

import org.junit.Assert.*
import org.junit.Test
import org.kaze.camkaze.crypto.Chunked
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

class ChunkedTest {
    private val plain = ByteArray(2500) { (it * 7).toByte() }

    private fun encrypted(chunk: Int = 1000): Pair<org.kaze.camkaze.crypto.Vault, File> {
        val v = newVault()
        v.create(PW.toCharArray(), true)
        val f = File.createTempFile("vid", ".kaze")
        f.outputStream().use { Chunked.encrypt(v, ByteArrayInputStream(plain), plain.size.toLong(), it, chunk) }
        return v to f
    }

    @Test fun fullRoundTrip() {
        val (v, f) = encrypted()
        Chunked.open(f, v).use { r ->
            assertEquals(2500L, r.size)
            val out = ByteArrayOutputStream()
            r.copyTo(out)
            assertArrayEquals(plain, out.toByteArray())
        }
    }

    @Test fun randomAccessAcrossChunkBoundaries() {
        val (v, f) = encrypted()
        Chunked.open(f, v).use { r ->
            for ((pos, len) in listOf(0 to 10, 995 to 20, 1990 to 30, 2400 to 100, 1000 to 1000)) {
                val buf = ByteArray(len)
                val n = r.read(pos.toLong(), buf, 0, len)
                val expected = minOf(len, 2500 - pos)
                assertEquals(expected, n)
                assertArrayEquals(plain.copyOfRange(pos, pos + expected), buf.copyOf(expected))
            }
            assertEquals(-1, r.read(2500, ByteArray(4), 0, 4))
        }
    }

    @Test fun tamperedChunkIsRejected() {
        val (v, f) = encrypted()
        val bytes = f.readBytes()
        bytes[Chunked.HEADER + 5] = (bytes[Chunked.HEADER + 5].toInt() xor 1).toByte()
        f.writeBytes(bytes)
        Chunked.open(f, v).use { r -> assertThrows(IOException::class.java) { r.read(0, ByteArray(10), 0, 10) } }
    }

    @Test fun truncatedFileIsRejected() {
        val (v, f) = encrypted()
        f.writeBytes(f.readBytes().copyOf((f.length() - 40).toInt()))
        assertThrows(IOException::class.java) { Chunked.open(f, v) }
    }

    @Test fun reorderedChunksAreRejected() {
        val (v, f) = encrypted()
        val b = f.readBytes()
        val sz = 1000 + 16
        val c0 = b.copyOfRange(Chunked.HEADER, Chunked.HEADER + sz)
        val c1 = b.copyOfRange(Chunked.HEADER + sz, Chunked.HEADER + 2 * sz)
        System.arraycopy(c1, 0, b, Chunked.HEADER, sz)
        System.arraycopy(c0, 0, b, Chunked.HEADER + sz, sz)
        f.writeBytes(b)
        Chunked.open(f, v).use { r -> assertThrows(IOException::class.java) { r.read(0, ByteArray(10), 0, 10) } }
    }
}
