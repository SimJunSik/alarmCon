package com.alarmcon.app

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
        val matchText = extractMatchText(sbn).ifBlank { sender.orEmpty() }
        val senderPattern = PatternStore.getSenderPattern(this, sbn.packageName, matchText)
        val pattern = if (senderPattern != null) {
            senderPattern
        } else {
            if (matchText.isNotBlank() &&
                PatternStore.getMuteWhenNoSender(this, sbn.packageName)
            ) {
                return
            }
            PatternStore.getPattern(this, sbn.packageName) ?: return
        }
        PatternStore.recordLastMatch(this, sbn.packageName, System.currentTimeMillis())
        triggerVibration(pattern)
    }

    private fun triggerVibration(pattern: PatternStore.PatternData) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        if (!vibrator.hasVibrator()) return

        vibrator.vibrate(
            VibrationEffect.createWaveform(pattern.timings, pattern.amplitudes, -1)
        )
    }

    private fun extractSenderText(sbn: StatusBarNotification): String? {
        val extras = sbn.notification.extras ?: return null
        val fromMessages = extractSenderFromMessages(extras)
        if (!fromMessages.isNullOrBlank()) return fromMessages

        val candidates = listOf(
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE),
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
            extras.getCharSequence(Notification.EXTRA_TITLE_BIG),
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
        )
        return candidates.firstOrNull { !it.isNullOrBlank() }?.toString()
    }

    private fun extractMatchText(sbn: StatusBarNotification): String {
        val extras = sbn.notification.extras ?: return ""
        val candidates = listOf(
            extractSenderFromMessages(extras),
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE),
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
            extras.getCharSequence(Notification.EXTRA_TITLE_BIG),
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
        )
        return candidates
            .filterNot { it.isNullOrBlank() }
            .joinToString(" ") { it.toString() }
            .trim()
    }

    private fun extractSenderFromMessages(extras: android.os.Bundle): String? {
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        for (message in messages) {
            val bundle = message as? android.os.Bundle ?: continue
            val sender = bundle.getCharSequence("sender")
                ?: bundle.getCharSequence("name")
                ?: bundle.getCharSequence("from")
            if (!sender.isNullOrBlank()) return sender.toString()
        }
        return null
    }
}
