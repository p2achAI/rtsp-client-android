package ir.am3n.rtsp.client

internal object EndpointFallbackExecutor {
    enum class AttemptResult {
        CONNECTED,
        FAILED,
        UNAUTHORIZED,
        STOPPED
    }

    data class ExecutionResult(
        val result: AttemptResult,
        val lastFailureMessage: String? = null
    )

    fun <T> execute(
        endpoints: List<T>,
        isStopped: () -> Boolean,
        attempt: (T) -> ExecutionResult,
        onFallback: (current: T, next: T) -> Unit = { _, _ -> }
    ): ExecutionResult {
        require(endpoints.isNotEmpty()) { "At least one endpoint is required" }
        var lastFailureMessage: String? = null

        endpoints.forEachIndexed { index, endpoint ->
            if (isStopped()) return ExecutionResult(AttemptResult.STOPPED)
            val result = attempt(endpoint)
            when (result.result) {
                AttemptResult.CONNECTED,
                AttemptResult.UNAUTHORIZED,
                AttemptResult.STOPPED -> return result
                AttemptResult.FAILED -> lastFailureMessage = result.lastFailureMessage
            }
            if (index < endpoints.lastIndex) onFallback(endpoint, endpoints[index + 1])
        }

        return ExecutionResult(AttemptResult.FAILED, lastFailureMessage)
    }
}
