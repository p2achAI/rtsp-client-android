package ir.am3n.rtsp.client

import ir.am3n.rtsp.client.EndpointFallbackExecutor.AttemptResult
import ir.am3n.rtsp.client.EndpointFallbackExecutor.ExecutionResult
import org.junit.Assert.assertEquals
import org.junit.Test

class EndpointFallbackExecutorTest {
    @Test
    fun `503 failure falls back to second endpoint`() {
        val attempted = mutableListOf<String>()

        val result = execute(listOf("h265", "h264"), attempted) { endpoint ->
            if (endpoint == "h265") ExecutionResult(AttemptResult.FAILED, "Invalid status code 503")
            else ExecutionResult(AttemptResult.CONNECTED)
        }

        assertEquals(listOf("h265", "h264"), attempted)
        assertEquals(AttemptResult.CONNECTED, result.result)
    }

    @Test
    fun `connection failure reaches legacy endpoint`() {
        val attempted = mutableListOf<String>()

        val result = execute(listOf("h265", "h264", "legacy"), attempted) { endpoint ->
            if (endpoint == "legacy") ExecutionResult(AttemptResult.CONNECTED)
            else ExecutionResult(AttemptResult.FAILED, "Connection refused")
        }

        assertEquals(listOf("h265", "h264", "legacy"), attempted)
        assertEquals(AttemptResult.CONNECTED, result.result)
    }

    @Test
    fun `unauthorized is terminal and does not try fallback`() {
        val attempted = mutableListOf<String>()

        val result = execute(listOf("h265", "h264", "legacy"), attempted) {
            ExecutionResult(AttemptResult.UNAUTHORIZED)
        }

        assertEquals(listOf("h265"), attempted)
        assertEquals(AttemptResult.UNAUTHORIZED, result.result)
    }

    @Test
    fun `all failures report only the last failure once`() {
        val attempted = mutableListOf<String>()
        var terminalFailureCount = 0

        val result = execute(listOf("h265", "h264", "legacy"), attempted) { endpoint ->
            ExecutionResult(AttemptResult.FAILED, "$endpoint failed")
        }.also {
            if (it.result == AttemptResult.FAILED) terminalFailureCount++
        }

        assertEquals(listOf("h265", "h264", "legacy"), attempted)
        assertEquals(AttemptResult.FAILED, result.result)
        assertEquals("legacy failed", result.lastFailureMessage)
        assertEquals(1, terminalFailureCount)
    }

    @Test
    fun `user stop prevents next endpoint attempt`() {
        val attempted = mutableListOf<String>()
        var stopped = false

        val result = EndpointFallbackExecutor.execute(
            endpoints = listOf("h265", "h264"),
            isStopped = { stopped },
            attempt = { endpoint ->
                attempted += endpoint
                stopped = true
                ExecutionResult(AttemptResult.FAILED, "stopped")
            }
        )

        assertEquals(listOf("h265"), attempted)
        assertEquals(AttemptResult.STOPPED, result.result)
    }

    private fun execute(
        endpoints: List<String>,
        attempted: MutableList<String>,
        attempt: (String) -> ExecutionResult
    ): ExecutionResult {
        return EndpointFallbackExecutor.execute(
            endpoints = endpoints,
            isStopped = { false },
            attempt = { endpoint ->
                attempted += endpoint
                attempt(endpoint)
            }
        )
    }
}
