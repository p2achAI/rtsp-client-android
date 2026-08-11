package ir.am3n.rtsp.client

import ir.am3n.rtsp.client.parser.VideoRtpParser
import ir.am3n.utils.VideoCodecType
import ir.am3n.utils.VideoCodecUtils
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HevcSupportTest {

    private val startCode = byteArrayOf(0, 0, 0, 1)

    @Test
    fun `all known HEVC SDP names map to H265`() {
        listOf("H265", "h265", "HEVC", "HEV", "hvc1", "hev1").forEach { name ->
            assertEquals(
                RtspClientUtils.VIDEO_CODEC_H265,
                RtspClientUtils.getVideoCodecFromSdpName(name)
            )
        }
        assertEquals(
            RtspClientUtils.VIDEO_CODEC_H264,
            RtspClientUtils.getVideoCodecFromSdpName("H264")
        )
        assertNull(RtspClientUtils.getVideoCodecFromSdpName("VP9"))
    }

    @Test
    fun `single HEVC NAL is converted to Annex B`() {
        val payload = byteArrayOf(0x26, 0x01, 0x11, 0x22)

        val actual = VideoRtpParser(VideoCodecType.H265)
            .processRtpPacketAndGetNalUnit(payload, payload.size)

        assertArrayEquals(startCode + payload, actual)
        assertTrue(VideoCodecUtils.isAnyH265KeyFrame(actual, 0, actual!!.size))
    }

    @Test
    fun `HEVC fragmentation unit restores its original NAL header`() {
        val parser = VideoRtpParser(VideoCodecType.H265)
        val start = byteArrayOf(0x62, 0x01, 0x93.toByte(), 0x11, 0x22)
        val middle = byteArrayOf(0x62, 0x01, 0x13, 0x33)
        val end = byteArrayOf(0x62, 0x01, 0x53, 0x44)

        assertNull(parser.processRtpPacketAndGetNalUnit(start, start.size))
        assertNull(parser.processRtpPacketAndGetNalUnit(middle, middle.size))
        val actual = parser.processRtpPacketAndGetNalUnit(end, end.size)

        assertArrayEquals(
            startCode + byteArrayOf(0x26, 0x01, 0x11, 0x22, 0x33, 0x44),
            actual
        )
    }

    @Test
    fun `HEVC aggregation packet emits each NAL in Annex B form`() {
        val vps = byteArrayOf(0x40, 0x01, 0x11)
        val idr = byteArrayOf(0x26, 0x01, 0x22)
        val payload = byteArrayOf(
            0x60, 0x01,
            0x00, vps.size.toByte(), *vps,
            0x00, idr.size.toByte(), *idr
        )

        val actual = VideoRtpParser(VideoCodecType.H265)
            .processRtpPacketAndGetNalUnit(payload, payload.size)

        assertArrayEquals(startCode + vps + startCode + idr, actual)
        val units = ArrayList<VideoCodecUtils.NalUnit>()
        assertEquals(2, VideoCodecUtils.getH265NalUnits(actual!!, 0, actual.size, units))
        assertEquals(listOf(32, 19), units.map { it.type.toInt() })
    }
}
