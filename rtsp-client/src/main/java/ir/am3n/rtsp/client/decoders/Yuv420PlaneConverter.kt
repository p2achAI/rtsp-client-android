package ir.am3n.rtsp.client.decoders

import java.nio.ByteBuffer

/**
 * Copies a cropped YUV_420_888 image into a tightly packed NV21 byte array.
 *
 * Android decoders may add row padding and expose chroma pixels with a pixel stride of two.
 * Reading the codec output buffer as one contiguous YUV array therefore corrupts colors on
 * affected devices. This converter keeps the layout handling independent from Android's Image
 * class so it can be covered by local unit tests.
 */
internal object Yuv420PlaneConverter {

    internal data class Plane(
        val buffer: ByteBuffer,
        val rowStride: Int,
        val pixelStride: Int
    )

    fun toNv21(
        cropLeft: Int,
        cropTop: Int,
        width: Int,
        height: Int,
        planes: List<Plane>
    ): ByteArray {
        require(cropLeft >= 0 && cropTop >= 0) { "Crop origin must not be negative" }
        require(cropLeft % 2 == 0 && cropTop % 2 == 0) {
            "YUV420 crop origin must be even"
        }
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) {
            "YUV420 dimensions must be positive and even"
        }
        require(planes.size >= 3) { "YUV_420_888 requires Y, U and V planes" }

        val ySize = Math.multiplyExact(width, height)
        val output = ByteArray(Math.addExact(ySize, ySize / 2))

        copyPlane(
            plane = planes[0],
            sourceLeft = cropLeft,
            sourceTop = cropTop,
            width = width,
            height = height,
            destination = output,
            destinationOffset = 0,
            destinationPixelStride = 1
        )

        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val chromaLeft = cropLeft / 2
        val chromaTop = cropTop / 2

        // NV21 stores V and U interleaved after the Y plane.
        copyPlane(
            plane = planes[2],
            sourceLeft = chromaLeft,
            sourceTop = chromaTop,
            width = chromaWidth,
            height = chromaHeight,
            destination = output,
            destinationOffset = ySize,
            destinationPixelStride = 2
        )
        copyPlane(
            plane = planes[1],
            sourceLeft = chromaLeft,
            sourceTop = chromaTop,
            width = chromaWidth,
            height = chromaHeight,
            destination = output,
            destinationOffset = ySize + 1,
            destinationPixelStride = 2
        )

        return output
    }

    private fun copyPlane(
        plane: Plane,
        sourceLeft: Int,
        sourceTop: Int,
        width: Int,
        height: Int,
        destination: ByteArray,
        destinationOffset: Int,
        destinationPixelStride: Int
    ) {
        require(plane.rowStride > 0) { "Plane row stride must be positive" }
        require(plane.pixelStride > 0) { "Plane pixel stride must be positive" }
        require(destinationPixelStride > 0) { "Destination pixel stride must be positive" }

        val source = plane.buffer.duplicate()
        val sourceBase = source.position()
        val sourceLastIndex = sourceBase.toLong() +
                (sourceTop + height - 1L) * plane.rowStride +
                (sourceLeft + width - 1L) * plane.pixelStride
        require(sourceLastIndex < source.limit().toLong()) {
            "Plane buffer is smaller than the requested crop"
        }

        val destinationRowStride = Math.multiplyExact(width, destinationPixelStride)
        val destinationLastIndex = destinationOffset.toLong() +
                (height - 1L) * destinationRowStride +
                (width - 1L) * destinationPixelStride
        require(destinationLastIndex < destination.size.toLong()) {
            "Destination buffer is too small"
        }

        val sourceRowLength = Math.addExact(
            Math.multiplyExact(width - 1, plane.pixelStride),
            1
        )
        val rowData = if (plane.pixelStride == 1 && destinationPixelStride == 1) {
            null
        } else {
            ByteArray(sourceRowLength)
        }

        for (row in 0 until height) {
            val sourceRowStart = sourceBase +
                    (sourceTop + row) * plane.rowStride +
                    sourceLeft * plane.pixelStride
            val destinationRowStart = destinationOffset + row * destinationRowStride

            if (plane.pixelStride == 1 && destinationPixelStride == 1) {
                val rowBuffer = source.duplicate()
                rowBuffer.position(sourceRowStart)
                rowBuffer.get(destination, destinationRowStart, width)
            } else {
                val rowBuffer = source.duplicate()
                rowBuffer.position(sourceRowStart)
                rowBuffer.get(rowData!!, 0, sourceRowLength)
                for (column in 0 until width) {
                    destination[destinationRowStart + column * destinationPixelStride] =
                        rowData[column * plane.pixelStride]
                }
            }
        }
    }
}
