package com.coolcupman.dnasq

import android.os.SystemClock

/**
 * In-memory unlock state. Locked again when the screen turns off, after [MAX_SESSION_MS], or when
 * the process dies. Never persisted.
 */
object Session {
    private const val MAX_SESSION_MS = 15 * 60 * 1000L

    @Volatile private var unlockedUntil = 0L

    val isUnlocked: Boolean get() = SystemClock.elapsedRealtime() < unlockedUntil

    fun unlock() { unlockedUntil = SystemClock.elapsedRealtime() + MAX_SESSION_MS }

    fun lock() { unlockedUntil = 0L }
}
