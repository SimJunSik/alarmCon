package com.alarmcon.app

import android.content.Context
import com.alarmcon.app.R

object PatternStore {
    private const val MAX_SEGMENT_MS = 15_000L
    private const val MAX_TOTAL_MS = 60_000L
    private const val PREFS_NAME = "alarmcon_prefs"
    private const val KEY_PREFIX = "pattern_"
    private const val NAME_PREFIX = "pattern_name_"
    private const val SENDER_PATTERN_PREFIX = "pattern_sender_"
    private const val SENDER_NAME_PREFIX = "pattern_sender_name_"
    private const val SENDER_VALUE_PREFIX = "pattern_sender_value_"
    private const val MUTE_NO_SENDER_PREFIX = "mute_no_sender_"
    private const val KEY_LAST_PACKAGE = "last_package"
    private const val KEY_LAST_TIME = "last_time"
    private const val KEY_LAST_MATCH_PACKAGE = "last_match_package"
    private const val KEY_LAST_MATCH_TIME = "last_match_time"

    data class LastEvent(val packageName: String, val timestamp: Long)
    data class PatternData(val timings: LongArray, val amplitudes: IntArray)

    fun savePattern(context: Context, targetPackage: String, patternText: String, name: String): Boolean {
        if (patternText.isBlank()) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_PREFIX + targetPackage)
                .remove(NAME_PREFIX + targetPackage)
                .apply()
            return true
        }

        val pattern = parsePattern(patternText) ?: return false
        val normalized = formatPattern(pattern)
        val patternName = name.trim().ifEmpty { context.getString(R.string.pattern_name_default) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREFIX + targetPackage, normalized)
            .putString(NAME_PREFIX + targetPackage, patternName)
            .apply()
        return true
    }

    fun saveSenderPattern(
        context: Context,
        targetPackage: String,
        sender: String,
        patternText: String,
        name: String
    ): Boolean {
        val senderValue = sender.trim()
        if (senderValue.isEmpty()) return false

        val senderKey = buildSenderKey(targetPackage, senderValue)
        if (patternText.isBlank()) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(SENDER_PATTERN_PREFIX + senderKey)
                .remove(SENDER_NAME_PREFIX + senderKey)
                .remove(SENDER_VALUE_PREFIX + senderKey)
                .apply()
            return true
        }

        val pattern = parsePattern(patternText) ?: return false
        val normalized = formatPattern(pattern)
        val patternName = name.trim().ifEmpty { context.getString(R.string.pattern_name_default) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(SENDER_PATTERN_PREFIX + senderKey, normalized)
            .putString(SENDER_NAME_PREFIX + senderKey, patternName)
            .putString(SENDER_VALUE_PREFIX + senderKey, senderValue)
            .apply()
        return true
    }

    fun getPattern(context: Context, targetPackage: String): PatternData? {
        val text = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PREFIX + targetPackage, null)
            ?: return null
        return parsePattern(text)
    }

    fun getSenderPattern(context: Context, targetPackage: String, senderText: String): PatternData? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val normalizedSender = senderText.trim().lowercase()
        if (normalizedSender.isEmpty()) return null

        val entries = prefs.all.entries
            .filter { it.key.startsWith(SENDER_PATTERN_PREFIX + "$targetPackage|") && it.value is String }

        var bestMatch: Pair<String, String>? = null
        for (entry in entries) {
            val senderKey = entry.key.removePrefix(SENDER_PATTERN_PREFIX)
            val senderValue = prefs.getString(SENDER_VALUE_PREFIX + senderKey, null)
                ?: continue
            val tokens = senderValue.split(",")
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
            if (tokens.isEmpty()) continue
            for (token in tokens) {
                if (normalizedSender.contains(token)) {
                    if (bestMatch == null || token.length > bestMatch!!.first.length) {
                        bestMatch = token to (entry.value as String)
                    }
                }
            }
        }

        return bestMatch?.second?.let { parsePattern(it) }
    }

    enum class PatternKind { APP, SENDER }

    data class StoredPattern(
        val packageName: String,
        val pattern: String,
        val name: String,
        val sender: String?,
        val muteWhenNoSender: Boolean,
        val storageKey: String,
        val kind: PatternKind
    )

    fun getAllPatternTexts(context: Context): List<StoredPattern> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val appPatterns = prefs.all.entries
            .filter { it.key.startsWith(KEY_PREFIX) && it.value is String }
            .map { entry ->
                val packageName = entry.key.removePrefix(KEY_PREFIX)
                val pattern = entry.value as String
                val name = prefs.getString(NAME_PREFIX + packageName, null)
                    ?: context.getString(R.string.pattern_name_default)
                val mute = prefs.getBoolean(MUTE_NO_SENDER_PREFIX + packageName, false)
                StoredPattern(
                    packageName,
                    pattern,
                    name,
                    null,
                    mute,
                    entry.key,
                    PatternKind.APP
                )
            }
        val senderPatterns = prefs.all.entries
            .filter { it.key.startsWith(SENDER_PATTERN_PREFIX) && it.value is String }
            .map { entry ->
                val senderKey = entry.key.removePrefix(SENDER_PATTERN_PREFIX)
                val parts = senderKey.split("|", limit = 2)
                val packageName = parts.firstOrNull().orEmpty()
                val pattern = entry.value as String
                val name = prefs.getString(SENDER_NAME_PREFIX + senderKey, null)
                    ?: context.getString(R.string.pattern_name_default)
                val sender = prefs.getString(SENDER_VALUE_PREFIX + senderKey, null)
                val mute = prefs.getBoolean(MUTE_NO_SENDER_PREFIX + packageName, false)
                StoredPattern(
                    packageName,
                    pattern,
                    name,
                    sender,
                    mute,
                    entry.key,
                    PatternKind.SENDER
                )
            }
        return (appPatterns + senderPatterns)
            .sortedWith(compareBy({ it.packageName.lowercase() }, { it.sender ?: "" }))
    }

    fun setMuteWhenNoSender(context: Context, targetPackage: String, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(MUTE_NO_SENDER_PREFIX + targetPackage, enabled)
            .apply()
    }

    fun getMuteWhenNoSender(context: Context, targetPackage: String): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(MUTE_NO_SENDER_PREFIX + targetPackage, false)
    }

    fun deletePattern(context: Context, targetPackage: String, sender: String?): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        return if (sender.isNullOrBlank()) {
            editor
                .remove(KEY_PREFIX + targetPackage)
                .remove(NAME_PREFIX + targetPackage)
                .remove(MUTE_NO_SENDER_PREFIX + targetPackage)
                .apply()
            true
        } else {
            val senderKey = buildSenderKey(targetPackage, sender)
            editor
                .remove(SENDER_PATTERN_PREFIX + senderKey)
                .remove(SENDER_NAME_PREFIX + senderKey)
                .remove(SENDER_VALUE_PREFIX + senderKey)
                .apply()
            true
        }
    }

    fun deletePatternByKey(context: Context, storageKey: String, kind: PatternKind): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        return if (kind == PatternKind.APP) {
            val packageName = storageKey.removePrefix(KEY_PREFIX)
            editor
                .remove(KEY_PREFIX + packageName)
                .remove(NAME_PREFIX + packageName)
                .remove(MUTE_NO_SENDER_PREFIX + packageName)
                .apply()
            true
        } else {
            val senderKey = storageKey.removePrefix(SENDER_PATTERN_PREFIX)
            editor
                .remove(SENDER_PATTERN_PREFIX + senderKey)
                .remove(SENDER_NAME_PREFIX + senderKey)
                .remove(SENDER_VALUE_PREFIX + senderKey)
                .apply()
            true
        }
    }

    fun appStorageKey(packageName: String): String {
        return KEY_PREFIX + packageName
    }

    fun senderStorageKey(packageName: String, sender: String): String {
        return SENDER_PATTERN_PREFIX + buildSenderKey(packageName, sender)
    }

    fun recordLastEvent(context: Context, packageName: String, timestamp: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_PACKAGE, packageName)
            .putLong(KEY_LAST_TIME, timestamp)
            .apply()
    }

    fun recordLastMatch(context: Context, packageName: String, timestamp: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_MATCH_PACKAGE, packageName)
            .putLong(KEY_LAST_MATCH_TIME, timestamp)
            .apply()
    }

    fun getLastEvent(context: Context): LastEvent? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pkg = prefs.getString(KEY_LAST_PACKAGE, null) ?: return null
        val time = prefs.getLong(KEY_LAST_TIME, 0L)
        if (time <= 0L) return null
        return LastEvent(pkg, time)
    }

    fun getLastMatch(context: Context): LastEvent? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pkg = prefs.getString(KEY_LAST_MATCH_PACKAGE, null) ?: return null
        val time = prefs.getLong(KEY_LAST_MATCH_TIME, 0L)
        if (time <= 0L) return null
        return LastEvent(pkg, time)
    }

    fun parsePatternText(patternText: String): PatternData? {
        return parsePattern(patternText)
    }

    private fun parsePattern(patternText: String): PatternData? {
        val parts = patternText.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null

        val timings = ArrayList<Long>(parts.size)
        val amplitudes = ArrayList<Int>(parts.size)
        var total = 0L
        for ((index, part) in parts.withIndex()) {
            val pieces = part.split(":", limit = 2).map { it.trim() }
            val duration = pieces.getOrNull(0)?.toLongOrNull() ?: return null
            if (duration < 0L) return null
            if (duration > MAX_SEGMENT_MS) return null
            total += duration
            if (total > MAX_TOTAL_MS) return null

            val isVibrate = index % 2 == 1
            val amplitude = if (!isVibrate) {
                0
            } else {
                val ampToken = pieces.getOrNull(1)
                if (ampToken.isNullOrEmpty()) {
                    255
                } else {
                    val amp = ampToken.toIntOrNull() ?: return null
                    if (amp !in 1..255) return null
                    amp
                }
            }

            timings.add(duration)
            amplitudes.add(amplitude)
        }

        return if (timings.isEmpty()) {
            null
        } else {
            PatternData(timings.toLongArray(), amplitudes.toIntArray())
        }
    }

    private fun formatPattern(pattern: PatternData): String {
        val parts = ArrayList<String>(pattern.timings.size)
        for (index in pattern.timings.indices) {
            val duration = pattern.timings[index]
            if (index % 2 == 1) {
                val amp = pattern.amplitudes.getOrNull(index) ?: 255
                parts.add("$duration:$amp")
            } else {
                parts.add(duration.toString())
            }
        }
        return parts.joinToString(",")
    }

    private fun buildSenderKey(packageName: String, sender: String): String {
        val normalized = sender.trim().lowercase()
            .replace(Regex("[^a-z0-9._-]"), "_")
        return "$packageName|$normalized"
    }
}
