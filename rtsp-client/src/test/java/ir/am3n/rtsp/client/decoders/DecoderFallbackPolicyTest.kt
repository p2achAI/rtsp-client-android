package ir.am3n.rtsp.client.decoders

import ir.am3n.utils.DecoderType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderFallbackPolicyTest {
    @Test
    fun `hardware decoder falls back after sustained no output`() {
        assertTrue(
            DecoderFallbackPolicy.shouldFallback(
                DecoderType.HARDWARE,
                DecoderFallbackPolicy.NO_OUTPUT_TIMEOUT_MS + 1,
                0,
            )
        )
    }

    @Test
    fun `hardware decoder tolerates normal startup delay`() {
        assertFalse(
            DecoderFallbackPolicy.shouldFallback(
                DecoderType.HARDWARE,
                DecoderFallbackPolicy.NO_OUTPUT_TIMEOUT_MS,
                DecoderFallbackPolicy.TRY_AGAIN_STREAK_LIMIT,
            )
        )
    }

    @Test
    fun `software decoder never loops through hardware fallback policy`() {
        assertFalse(
            DecoderFallbackPolicy.shouldFallback(
                DecoderType.SOFTWARE,
                Long.MAX_VALUE,
                Int.MAX_VALUE,
            )
        )
    }
}
