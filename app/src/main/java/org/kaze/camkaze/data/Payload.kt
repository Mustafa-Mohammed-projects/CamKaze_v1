package org.kaze.camkaze.data

import java.nio.ByteBuffer

/** Metadata that used to leak through file times now lives INSIDE the encrypted payload. */
data class Meta(val kind: Int, val rotation: Int, val takenMs: Long, val extra: Long) {
    val isVideo: Boolean get() = kind == Payload.KIND_VIDEO
}

/** Plaintext of a photo / thumbnail .kaze: kind(1) rotation(4) takenMs(8) extra(8) + JPEG. */
object Payload {
    const val HEADER = 21
    const val KIND_PHOTO = 0
    const val KIND_VIDEO = 1

    fun pack(meta: Meta, data: ByteArray): ByteArray =
        ByteBuffer.allocate(HEADER + data.size)
            .put(meta.kind.toByte()).putInt(meta.rotation).putLong(meta.takenMs).putLong(meta.extra)
            .put(data).array()

    fun meta(p: ByteArray): Meta {
        val b = ByteBuffer.wrap(p, 0, HEADER)
        return Meta(b.get().toInt(), b.getInt(), b.getLong(), b.getLong())
    }
}
