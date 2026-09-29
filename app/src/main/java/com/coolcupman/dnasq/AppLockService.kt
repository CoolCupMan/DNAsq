package com.coolcupman.dnasq

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

/**
 * Watches only window-state changes (which package is in front). When a locked app comes to the
 * foreground while the DNA session is locked, it is covered with [LockActivity].
 */
class AppLockService : AccessibilityService() {

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = Session.lock()
    }

    override fun onServiceConnected() {
        ContextCompat.registerReceiver(
            this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || Session.isUnlocked) return
        val app = application as DnasqApp
        if (!app.templateStore.isEnrolled || pkg !in app.settings.lockedPackages) return

        startActivity(
            Intent(this, LockActivity::class.java)
                .putExtra(LockActivity.EXTRA_PACKAGE, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOff) }
        super.onDestroy()
    }

    companion object {
        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, AppLockService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}
