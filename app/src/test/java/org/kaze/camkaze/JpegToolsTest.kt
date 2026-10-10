package org.kaze.camkaze

import org.junit.Assert.*
import org.junit.Test
import org.kaze.camkaze.data.JpegTools
import org.kaze.camkaze.data.SecureDelete
import java.io.File

class JpegToolsTest {
    private fun seg(marker: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (len shr 8).toByte(), len.toByte()) + payload
    }

    private fun fakeJpeg(): ByteArray {
        val exif = seg(0xE1, "Exif\u0000\u0000GPS-LAT-12.34 Pixel-9".toByteArray())
        val jfif = seg(0xE0, "JFIF\u0000".toByteArray())
        val icc = seg(0xE2, "ICC_PROFILE\u0000abc".toByteArray())
        val xmp = seg(0xE1, "http://ns.adobe.com/xap/1.0/ secret".toByteArray())
        val com = seg(0xFE, "made by someone".toByteArray())
        val dqt = seg(0xDB, ByteArray(10) { 1 })
        val sos = byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 4, 1, 2) + ByteArray(20) { 5 } + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + exif + jfif + icc + xmp + com + dqt + sos
    }

    @Test fun stripsExifXmpAndCommentsButKeepsImageData() {
        val out = JpegTools.sanitize(fakeJpeg(), 0)
        val text = String(out, Charsets.ISO_8859_1)
        assertFalse(text.contains("GPS-LAT"))
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("someone"))
        assertTrue(text.contains("JFIF"))
        assertTrue(text.contains("ICC_PROFILE"))
        assertTrue(out.size < fakeJpeg().size)
        assertEquals(0xFF.toByte(), out[0]); assertEquals(0xD8.toByte(), out[1])
        // entropy-coded data and EOI untouched
        assertArrayEquals(fakeJpeg().takeLast(26).toByteArray(), out.takeLast(26).toByteArray())
    }

    @Test fun addsOnlyAnOrientationTag() {
        val out = JpegTools.sanitize(fakeJpeg(), 90)
        assertEquals(0xE1.toByte(), out[3])                    // our APP1 right after SOI
        val text = String(out, Charsets.ISO_8859_1)
        assertTrue(text.contains("Exif"))
        assertFalse(text.contains("GPS-LAT"))
        // orientation value 6 (= rotate 90) at the documented offset: SOI(2)+hdr(4)+Exif(6)+TIFF(8)+count(2)+tag..(10)
        assertEquals(6.toByte(), out[2 + 4 + 6 + 8 + 2 + 9])
    }

    @Test fun nonJpegIsReturnedUntouched() {
        val junk = byteArrayOf(1, 2, 3, 4, 5)
        assertArrayEquals(junk, JpegTools.sanitize(junk, 90))
    }

    @Test fun shredRemovesFileAndLeavesNoHiddenLeftovers() {
        val dir = File.createTempFile("shred", "d").also { it.delete(); it.mkdir() }
        val f = File(dir, "a.kaze").also { it.writeBytes(ByteArray(200_000) { 7 }) }
        SecureDelete.shred(f)
        assertFalse(f.exists())
        assertEquals(0, dir.listFiles()!!.size)
        SecureDelete.shred(File(dir, "missing"))                // no crash
    }
}
