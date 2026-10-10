package org.kaze.camkaze.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** All image work happens on byte arrays / bitmaps in RAM. */
object Images {
    const val THUMB = 256

    /** Decode JPEG at [off, off+len) downsampled to ~maxSide, then rotated. */
    fun decode(data: ByteArray, off: Int, len: Int, rotation: Int, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, off, len, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSide && bounds.outHeight / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(data, off, len, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalArgumentException("Cannot decode image")
        if (rotation == 0) return bmp
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height,
            Matrix().apply { postRotate(rotation.toFloat()) }, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }

    fun decodePayload(payload: ByteArray, maxSide: Int): Bitmap =
        decode(payload, Payload.HEADER, payload.size - Payload.HEADER, Payload.meta(payload).rotation, maxSide)

    /** 256px JPEG thumbnail (rotation baked in). */
    fun thumbJpeg(jpeg: ByteArray, rotation: Int): ByteArray =
        compressThumb(decode(jpeg, 0, jpeg.size, rotation, THUMB))

    /** Scales + compresses [src] to a thumbnail JPEG and RECYCLES [src]. */
    fun compressThumb(src: Bitmap): ByteArray {
        val soft = if (src.config == Bitmap.Config.HARDWARE) {
            src.copy(Bitmap.Config.ARGB_8888, false).also { src.recycle() }
        } else src
        val longest = maxOf(soft.width, soft.height)
        val bmp = if (longest > THUMB) {
            val s = THUMB.toFloat() / longest
            Bitmap.createScaledBitmap(soft, (soft.width * s).toInt().coerceAtLeast(1),
                (soft.height * s).toInt().coerceAtLeast(1), true)
        } else soft
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        if (bmp !== soft) bmp.recycle()
        soft.recycle()
        return out.toByteArray()
    }

    /** EXIF orientation -> degrees (0/90/180/270). */
    fun exifRotation(jpeg: ByteArray): Int = try {
        when (ExifInterface(ByteArrayInputStream(jpeg))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    } catch (e: Exception) {
        0
    }
}
