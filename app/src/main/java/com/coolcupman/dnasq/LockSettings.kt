package com.coolcupman.dnasq

import android.content.Context
import com.coolcupman.dnasq.core.AttemptLimiter

/** Which apps are locked, and the brute-force lock-out state. Contains no genetic data. */
class LockSettings(context: Context) {
    private val prefs = context.getSharedPreferences("dnasq_settings", Context.MODE_PRIVATE)

    var lockedPackages: Set<String>
        get() = prefs.getStringSet(KEY_LOCKED, emptySet())!!.toSet()
        set(value) { prefs.edit().putStringSet(KEY_LOCKED, value).apply() }

    var limiterState: AttemptLimiter.State
        get() = AttemptLimiter.State(prefs.getInt(KEY_FAILURES, 0), prefs.getLong(KEY_LOCKED_UNTIL, 0))
        set(value) {
            prefs.edit().putInt(KEY_FAILURES, value.failures).putLong(KEY_LOCKED_UNTIL, value.lockedUntilEpochMs).commit()
        }

    private companion object {
        const val KEY_LOCKED = "locked_packages"
        const val KEY_FAILURES = "failures"
        const val KEY_LOCKED_UNTIL = "locked_until"
    }
}
