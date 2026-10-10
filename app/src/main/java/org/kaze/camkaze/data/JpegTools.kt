package org.kaze.camkaze.data

import java.io.ByteArrayOutputStream

/** Lossless metadata stripping for shared copies (no re-encode, no quality loss). */
object JpegTools {
    /**
     * Removes EXIF/XMP/comments (device model, timestamps, GPS...) and re-adds ONLY an
     * orientation tag, so the photo still shows upright in other apps.
     */
    fun sanitize(jpeg: ByteArray, orientationDegrees: Int): ByteArray {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return jpeg
        val out = ByteArrayOutputStream(jpeg.size)
        out.write(0xFF); out.write(0xD8)
        orientationSegment(orientationDegrees)?.let { out.write(it) }
        var pos = 2
        while (pos + 4 <= jpeg.size) {
            if (jpeg[pos] != 0xFF.toByte()) break
            val marker = jpeg[pos + 1].toInt() and 0xFF
            if (marker == 0xDA) break                       // start of scan: rest is verbatim
            if (marker == 0xFF) { pos++; continue }         // fill byte
            val len = ((jpeg[pos + 2].toInt() and 0xFF) shl 8) or (jpeg[pos + 3].toInt() and 0xFF)
            val end = pos + 2 + len
            if (len < 2 || end > jpeg.size) break
            if (keep(marker, jpeg, pos + 4, end)) out.write(jpeg, pos, end - pos)
            pos = end
        }
        out.write(jpeg, pos, jpeg.size - pos)
        return out.toByteArray()
    }

    private fun keep(marker: Int, d: ByteArray, payloadStart: Int, end: Int): Boolean = when {
        marker == 0xE0 -> true                                              // JFIF
        marker == 0xE2 -> startsWith(d, payloadStart, end, "ICC_PROFILE")   // colour profile only
        marker in 0xE1..0xEF || marker == 0xFE -> false                     // EXIF, XMP, APPn, COM
        else -> true
    }

    private fun startsWith(d: ByteArray, from: Int, end: Int, text: String): Boolean {
        if (end - from < text.length) return false
        return text.indices.all { d[from + it] == text[it].code.toByte() }
    }

    private fun orientationSegment(deg: Int): ByteArray? {
        val v = when (deg) { 90 -> 6; 180 -> 3; 270 -> 8; else -> return null }
        return byteArrayOf(
            0xFF.toByte(), 0xE1.toByte(), 0x00, 0x22,                       // APP1, length 34
            0x45, 0x78, 0x69, 0x66, 0x00, 0x00,                             // "Exif\0\0"
            0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,                 // TIFF big-endian, IFD at 8
            0x00, 0x01,                                                     // 1 entry
            0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, v.toByte(), 0x00, 0x00, // Orientation
            0x00, 0x00, 0x00, 0x00,                                         // no next IFD
        )
    }
}
