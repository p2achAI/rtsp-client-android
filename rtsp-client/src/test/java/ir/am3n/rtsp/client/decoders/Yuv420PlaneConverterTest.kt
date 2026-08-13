package ir.am3n.rtsp.client.decoders

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Yuv420PlaneConverterTest {

    @Test
    fun `converts padded planes with interleaved chroma strides to nv21`() {
        val y = plane(
            rowStride = 6,
            pixelStride = 1,
            bytes = byteArrayOf(
                0, 1, 2, 3, 99, 99,
                4, 5, 6, 7, 99, 99,
                8, 9, 10, 11, 99, 99,
                12, 13, 14, 15, 99, 99
            )
        )
        val u = plane(
            rowStride = 6,
            pixelStride = 2,
            bytes = byteArrayOf(
                20, 0, 21, 0, 99, 99,
                22, 0, 23, 0, 99, 99
            )
        )
        val v = plane(
            rowStride = 6,
            pixelStride = 2,
            bytes = byteArrayOf(
                30, 0, 31, 0, 99, 99,
                32, 0, 33, 0, 99, 99
            )
        )

        val actual = Yuv420PlaneConverter.toNv21(
            cropLeft = 0,
            cropTop = 0,
            width = 4,
            height = 4,
            planes = listOf(y, u, v)
        )

        assertArrayEquals(
            byteArrayOf(
                0, 1, 2, 3,
                4, 5, 6, 7,
                8, 9, 10, 11,
                12, 13, 14, 15,
                30, 20, 31, 21,
                32, 22, 33, 23
            ),
            actual
        )
    }

    @Test
    fun `applies crop offsets without changing plane buffer positions`() {
        val yBuffer = ByteBuffer.wrap(
            byteArrayOf(
                88, 88,
                0, 1, 2, 3, 4, 5, 99, 99,
                10, 11, 12, 13, 14, 15, 99, 99,
                20, 21, 22, 23, 24, 25, 99, 99,
                30, 31, 32, 33, 34, 35, 99, 99
            )
        ).apply { position(2) }
        val uBuffer = ByteBuffer.wrap(
            byteArrayOf(
                88,
                40, 41, 42, 99,
                43, 44, 45, 99
            )
        ).apply { position(1) }
        val vBuffer = ByteBuffer.wrap(
            byteArrayOf(
                88,
                50, 51, 52, 99,
                53, 54, 55, 99
            )
        ).apply { position(1) }

        val actual = Yuv420PlaneConverter.toNv21(
            cropLeft = 2,
            cropTop = 0,
            width = 4,
            height = 4,
            planes = listOf(
                Yuv420PlaneConverter.Plane(yBuffer, rowStride = 8, pixelStride = 1),
                Yuv420PlaneConverter.Plane(uBuffer, rowStride = 4, pixelStride = 1),
                Yuv420PlaneConverter.Plane(vBuffer, rowStride = 4, pixelStride = 1)
            )
        )

        assertArrayEquals(
            byteArrayOf(
                2, 3, 4, 5,
                12, 13, 14, 15,
                22, 23, 24, 25,
                32, 33, 34, 35,
                51, 41, 52, 42,
                54, 44, 55, 45
            ),
            actual
        )
        assertEquals(2, yBuffer.position())
        assertEquals(1, uBuffer.position())
        assertEquals(1, vBuffer.position())
    }

    @Test
    fun `rejects odd yuv420 dimensions`() {
        val tinyPlane = plane(rowStride = 4, pixelStride = 1, bytes = ByteArray(16))

        assertThrows(IllegalArgumentException::class.java) {
            Yuv420PlaneConverter.toNv21(
                cropLeft = 0,
                cropTop = 0,
                width = 3,
                height = 4,
                planes = listOf(tinyPlane, tinyPlane, tinyPlane)
            )
        }
    }

    @Test
    fun `rejects a plane smaller than the requested crop`() {
        val y = plane(rowStride = 4, pixelStride = 1, bytes = ByteArray(8))
        val chroma = plane(rowStride = 2, pixelStride = 1, bytes = ByteArray(4))

        assertThrows(IllegalArgumentException::class.java) {
            Yuv420PlaneConverter.toNv21(
                cropLeft = 0,
                cropTop = 0,
                width = 4,
                height = 4,
                planes = listOf(y, chroma, chroma)
            )
        }
    }

    private fun plane(
        rowStride: Int,
        pixelStride: Int,
        bytes: ByteArray
    ): Yuv420PlaneConverter.Plane {
        return Yuv420PlaneConverter.Plane(
            buffer = ByteBuffer.wrap(bytes),
            rowStride = rowStride,
            pixelStride = pixelStride
        )
    }
}
