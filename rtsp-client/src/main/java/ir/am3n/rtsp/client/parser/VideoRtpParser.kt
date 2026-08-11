package ir.am3n.rtsp.client.parser

import android.util.Log
import ir.am3n.rtsp.client.Rtsp
import ir.am3n.utils.VideoCodecUtils
import ir.am3n.utils.VideoCodecType
import ir.am3n.utils.VideoCodecUtils.getH264NalUnitTypeString
import java.io.ByteArrayOutputStream

class VideoRtpParser(
    private val codecType: VideoCodecType = VideoCodecType.H264
) {

    companion object {
        private const val TAG: String = "VideoRtpParser"
        private val ANNEX_B_START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
        private const val H265_AP = 48
        private const val H265_FU = 49
        private const val H265_PACI = 50
    }

    // TODO Use already allocated buffer with RtpPacket.MAX_SIZE = 65507
    // Used only for NAL_FU_A fragmented packets
    private val _fragmentedBuffer = arrayOfNulls<ByteArray>(1024)
    private var _fragmentedBufferLength = 0
    private var _fragmentedPackets = 0

    private var h265FragmentedBuffer: ByteArrayOutputStream? = null

    fun processRtpPacketAndGetNalUnit(data: ByteArray, length: Int): ByteArray? {
        if (Rtsp.DEBUG) Log.v(TAG, "processRtpPacketAndGetNalUnit(length=$length)")

        if (length <= 0 || length > data.size) return null
        return when (codecType) {
            VideoCodecType.H264 -> processH264Packet(data, length)
            VideoCodecType.H265 -> processH265Packet(data, length)
            VideoCodecType.UNKNOWN -> null
        }
    }

    private fun processH264Packet(data: ByteArray, length: Int): ByteArray? {
        if (length < 2) return null

        var tmpLen: Int
        val nalType = (data[0].toInt() and 0x1F).toByte()
        val packFlag = data[1].toInt() and 0xC0
        var nalUnit: ByteArray? = null

        if (Rtsp.DEBUG) {
            val typeStr = getH264NalUnitTypeString(nalType)
            val flagStr = when (packFlag) {
                0x80 -> "START"
                0x00 -> "MIDDLE"
                0x40 -> "END"
                else -> "UNKNOWN"
            }
            Log.d(TAG, "NAL type: $typeStr ($nalType), pack flag: $flagStr (0x${packFlag.toString(16)}), length=$length")
        }

        when (nalType) {
            VideoCodecUtils.NAL_FU_A -> when (packFlag) {
                0x80 -> {
                    _fragmentedPackets = 0
                    _fragmentedBufferLength = length - 1
                    _fragmentedBuffer[0] = ByteArray(_fragmentedBufferLength)
                    _fragmentedBuffer[0]!![0] = ((data[0].toInt() and 0xE0) or (data[1].toInt() and 0x1F)).toByte()
                    System.arraycopy(data, 2, _fragmentedBuffer[0]!!, 1, length - 2)
                    if (Rtsp.DEBUG) Log.d(TAG, "FU-A START received. Buffer length=${_fragmentedBufferLength}")
                }

                0x00 -> {
                    _fragmentedPackets++
                    if (_fragmentedPackets >= _fragmentedBuffer.size) {
                        Log.e(TAG, "Too many middle packets. Skipping.")
                        _fragmentedBuffer[0] = null
                    } else {
                        _fragmentedBufferLength += length - 2
                        _fragmentedBuffer[_fragmentedPackets] = ByteArray(length - 2)
                        System.arraycopy(data, 2, _fragmentedBuffer[_fragmentedPackets]!!, 0, length - 2)
                        if (Rtsp.DEBUG) Log.d(TAG, "FU-A MIDDLE received. Total packets=${_fragmentedPackets + 1}, Buffer length=${_fragmentedBufferLength}")
                    }
                }

                0x40 -> {
                    if (_fragmentedBuffer[0] == null) {
                        Log.e(TAG, "END received but START missing. Skipping.")
                    } else {
                        nalUnit = ByteArray(_fragmentedBufferLength + length + 2)
                        writeNalPrefix0001(nalUnit)
                        tmpLen = 4
                        for (i in 0.._fragmentedPackets) {
                            System.arraycopy(_fragmentedBuffer[i]!!, 0, nalUnit, tmpLen, _fragmentedBuffer[i]!!.size)
                            tmpLen += _fragmentedBuffer[i]!!.size
                        }
                        System.arraycopy(data, 2, nalUnit, tmpLen, length - 2)
                        tmpLen += length - 2
                        clearFragmentedBuffer()
                        if (Rtsp.DEBUG) {
                            Log.d(TAG, "FU-A END received. Assembled NAL length=$tmpLen")
                            Log.d(TAG, "First 8 bytes of NAL: ${nalUnit.take(8).joinToString(" ") { String.format("%02X", it) }}")
                            val nalHeader = nalUnit[4].toInt() and 0x1F
                            Log.d(TAG, "Assembled NAL type=$nalHeader")
                            val isKeyFrame = (nalHeader == 5 || nalHeader == 7 || nalHeader == 8)
                            if (nalUnit.size > 64000) {
                                if (!isKeyFrame) {
                                    Log.w(TAG, "Large non-keyframe (${nalUnit.size} bytes), skipping.")
                                    return null
                                } else {
                                    Log.w(TAG, "Large keyframe (${nalUnit.size} bytes), passing to decoder (may fail).")
                                }
                            }
                        }
                    }
                }
            }

            else -> {
                nalUnit = ByteArray(4 + length)
                writeNalPrefix0001(nalUnit)
                System.arraycopy(data, 0, nalUnit, 4, length)
                clearFragmentedBuffer()
                if (Rtsp.DEBUG) Log.d(TAG, "Single NAL (${nalUnit.size})")
            }
        }

        return nalUnit
    }

    /** Depacketizes the single-stream, non-interleaved HEVC payloads defined by RFC 7798. */
    private fun processH265Packet(data: ByteArray, length: Int): ByteArray? {
        if (length < 2) return null

        val nalType = (data[0].toInt() ushr 1) and 0x3F
        return when (nalType) {
            H265_AP -> {
                h265FragmentedBuffer = null
                processH265AggregationPacket(data, length)
            }
            H265_FU -> processH265FragmentationUnit(data, length)
            H265_PACI -> {
                Log.w(TAG, "HEVC PACI packets are not supported")
                null
            }
            else -> {
                h265FragmentedBuffer = null
                annexB(data, 0, length)
            }
        }
    }

    private fun processH265AggregationPacket(data: ByteArray, length: Int): ByteArray? {
        var offset = 2 // Skip the two-byte AP payload header.
        val output = ByteArrayOutputStream(length + 16)
        while (offset + 2 <= length) {
            val nalSize = ((data[offset].toInt() and 0xFF) shl 8) or
                    (data[offset + 1].toInt() and 0xFF)
            offset += 2
            if (nalSize <= 0 || offset + nalSize > length) {
                Log.w(TAG, "Invalid HEVC aggregation packet (NAL size=$nalSize, remaining=${length - offset})")
                return null
            }
            output.write(ANNEX_B_START_CODE)
            output.write(data, offset, nalSize)
            offset += nalSize
        }
        return output.toByteArray().takeIf { it.isNotEmpty() && offset == length }
    }

    private fun processH265FragmentationUnit(data: ByteArray, length: Int): ByteArray? {
        if (length < 3) return null

        val fuHeader = data[2].toInt() and 0xFF
        val start = fuHeader and 0x80 != 0
        val end = fuHeader and 0x40 != 0
        val originalNalType = fuHeader and 0x3F

        if (start) {
            val output = ByteArrayOutputStream(length + 1024)
            output.write(ANNEX_B_START_CODE)
            // Restore the original two-byte HEVC NAL header from the FU indicator/header.
            output.write((data[0].toInt() and 0x81) or (originalNalType shl 1))
            output.write(data[1].toInt() and 0xFF)
            output.write(data, 3, length - 3)
            h265FragmentedBuffer = output
            return if (end) output.toByteArray().also { h265FragmentedBuffer = null } else null
        }

        val output = h265FragmentedBuffer ?: run {
            Log.w(TAG, "HEVC FU packet received without a start packet")
            return null
        }
        output.write(data, 3, length - 3)
        return if (end) output.toByteArray().also { h265FragmentedBuffer = null } else null
    }

    private fun annexB(data: ByteArray, offset: Int, length: Int): ByteArray {
        return ByteArray(ANNEX_B_START_CODE.size + length).also {
            ANNEX_B_START_CODE.copyInto(it)
            data.copyInto(it, ANNEX_B_START_CODE.size, offset, offset + length)
        }
    }

    private fun clearFragmentedBuffer() {
        for (i in 0 until _fragmentedPackets + 1) {
            _fragmentedBuffer[i] = null
        }
    }

    private fun writeNalPrefix0001(buffer: ByteArray) {
        buffer[0] = 0x00
        buffer[1] = 0x00
        buffer[2] = 0x00
        buffer[3] = 0x01
    }

}
