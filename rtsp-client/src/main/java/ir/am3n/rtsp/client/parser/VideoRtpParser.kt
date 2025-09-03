package ir.am3n.rtsp.client.parser

import android.util.Log
import ir.am3n.rtsp.client.Rtsp
import ir.am3n.utils.VideoCodecUtils
import ir.am3n.utils.VideoCodecUtils.getH264NalUnitTypeString

class VideoRtpParser {

    companion object {
        private const val TAG: String = "VideoRtpParser"
    }

    // TODO Use already allocated buffer with RtpPacket.MAX_SIZE = 65507
    // Used only for NAL_FU_A fragmented packets
    private val _fragmentedBuffer = arrayOfNulls<ByteArray>(1024)
    private var _fragmentedBufferLength = 0
    private var _fragmentedPackets = 0

    fun processRtpPacketAndGetNalUnit(data: ByteArray, length: Int): ByteArray? {
        if (Rtsp.DEBUG) Log.v(TAG, "processRtpPacketAndGetNalUnit(length=$length)")

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
