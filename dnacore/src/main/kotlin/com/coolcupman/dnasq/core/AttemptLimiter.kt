package com.coolcupman.dnasq.core

/**
 * Exponential lock-out after failed DNA verifications, persisted by the caller so that it survives
 * app restarts. Pure logic so it can be unit tested.
 */
class AttemptLimiter(private val clock: () -> Long = System::currentTimeMillis) {

    data class State(val failures: Int = 0, val lockedUntilEpochMs: Long = 0)

    fun remainingLockMs(state: State): Long = (state.lockedUntilEpochMs - clock()).coerceAtLeast(0)

    fun onFailure(state: State): State {
        val failures = state.failures + 1
        return State(failures, clock() + lockoutFor(failures))
    }

    fun onSuccess(): State = State()

    companion object {
        private const val FREE_ATTEMPTS = 3
        private const val BASE_MS = 30_000L
        private const val MAX_MS = 24 * 60 * 60 * 1000L

        fun lockoutFor(failures: Int): Long {
            if (failures <= FREE_ATTEMPTS) return 0
            val exp = (failures - FREE_ATTEMPTS - 1).coerceAtMost(20)
            return (BASE_MS shl exp).coerceAtMost(MAX_MS)
        }
    }
}
