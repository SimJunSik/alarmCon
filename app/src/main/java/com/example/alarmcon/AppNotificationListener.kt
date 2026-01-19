package com.example.alarmcon

import android.app.Notification
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class AppNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return

        PatternStore.recordLastEvent(this, sbn.packageName, System.currentTimeMillis())
        val sender = extractSenderText(sbn)
        val senderPattern = PatternStore.getSenderPattern(this, sbn.packageName, sender.orEmpty())
        val pattern = if (senderPattern != null) {
            senderPattern
        } else {
            if (PatternStore.getMuteWhenNoSender(this, sbn.packageName)) return
            PatternStore.getPattern(this, sbn.packageName) ?: return
        }
        PatternStore.recordLastMatch(this, sbn.packageName, System.currentTimeMillis())
        triggerVibration(pattern)
    }

    private fun triggerVibration(pattern: LongArray) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        if (!vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    private fun extractSenderText(sbn: StatusBarNotification): String? {
        val extras = sbn.notification.extras ?: return null
        val candidates = listOf(
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
        )
        return candidates.firstOrNull { !it.isNullOrBlank() }?.toString()
    }
}
