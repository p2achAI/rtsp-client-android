package ir.am3n.rtsp.client.decoders

import ir.am3n.utils.DecoderType

internal object DecoderFallbackPolicy {
    const val NO_OUTPUT_TIMEOUT_MS = 10_000L
    const val TRY_AGAIN_STREAK_LIMIT = 500

    fun shouldFallback(
        decoderType: DecoderType,
        noOutputMs: Long,
        tryAgainStreak: Int,
    ): Boolean = decoderType == DecoderType.HARDWARE &&
        (noOutputMs > NO_OUTPUT_TIMEOUT_MS || tryAgainStreak > TRY_AGAIN_STREAK_LIMIT)
}
