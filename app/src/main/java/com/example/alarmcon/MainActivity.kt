package com.example.alarmcon

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import android.content.pm.PackageManager
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import androidx.appcompat.app.AlertDialog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private data class AppEntry(val name: String, val packageName: String)

    private val appList = mutableListOf<AppEntry>()
    private data class ConfigEntry(
        val appName: String,
        val packageName: String,
        val pattern: String,
        val patternName: String,
        val sender: String?,
        val muteWhenNoSender: Boolean,
        val storageKey: String,
        val kind: PatternStore.PatternKind
    )

    private val nameToPackage = mutableMapOf<String, String>()
    private val searchResults = mutableListOf<AppEntry>()
    private val searchDisplayList = mutableListOf<String>()
    private lateinit var searchAdapter: SearchAdapter
    private val configuredEntries = mutableListOf<ConfigEntry>()
    private lateinit var configuredAdapter: ConfiguredAdapter
    private lateinit var vibrator: Vibrator
    private var muteWhenNoSender = false
    private var currentSelectedStorageKey: String? = null
    private var currentSelectedKind: PatternStore.PatternKind? = null
    private var isProgrammaticFill = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

        val openSettingsButton = findViewById<Button>(R.id.openSettingsButton)
        val openHelpButton = findViewById<Button>(R.id.openHelpButton)
        val testVibrationButton = findViewById<Button>(R.id.testVibrationButton)
        val packageNameInput = findViewById<EditText>(R.id.packageNameInput)
        val patternNameInput = findViewById<EditText>(R.id.patternNameInput)
        val senderNameInput = findViewById<EditText>(R.id.senderNameInput)
        val patternInput = findViewById<EditText>(R.id.patternInput)
        val aiPromptInput = findViewById<EditText>(R.id.aiPromptInput)
        val aiGenerateButton = findViewById<Button>(R.id.aiGenerateButton)
        val pulseDurationInput = findViewById<EditText>(R.id.pulseDurationInput)
        val pauseDurationInput = findViewById<EditText>(R.id.pauseDurationInput)
        val addPulseButton = findViewById<Button>(R.id.addPulseButton)
        val clearPatternButton = findViewById<Button>(R.id.clearPatternButton)
        val saveButton = findViewById<Button>(R.id.saveButton)
        val searchResultsListView = findViewById<ListView>(R.id.searchResultsListView)
        val openAppNotificationSettingsButton =
            findViewById<Button>(R.id.openAppNotificationSettingsButton)
        val configuredListView = findViewById<ListView>(R.id.configuredListView)
        val shortVibrationButton = findViewById<Button>(R.id.shortVibrationButton)
        val mediumVibrationButton = findViewById<Button>(R.id.mediumVibrationButton)
        val longVibrationButton = findViewById<Button>(R.id.longVibrationButton)
        val muteWhenNoSenderSwitch =
            findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(
                R.id.muteWhenNoSenderSwitch
            )

        openSettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        openHelpButton.setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }

        loadInstalledApps()

        searchAdapter = SearchAdapter()
        searchResultsListView.adapter = searchAdapter
        searchResultsListView.setOnItemClickListener { _, _, position, _ ->
            val entry = searchResults.getOrNull(position) ?: return@setOnItemClickListener
            packageNameInput.setText(entry.name)
            currentSelectedStorageKey = null
            currentSelectedKind = null
        }

        packageNameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                updateSearchResults(s?.toString().orEmpty())
                if (!isProgrammaticFill) {
                    currentSelectedStorageKey = null
                    currentSelectedKind = null
                }
            }
        })

        testVibrationButton.setOnClickListener {
            val patternText = patternInput.text.toString().trim()
            val pattern = PatternStore.parsePatternText(patternText)
            if (pattern == null) {
                Toast.makeText(this, R.string.invalid_pattern, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            vibrate(pattern)
        }

        aiGenerateButton.setOnClickListener {
            val prompt = aiPromptInput.text.toString().trim()
            aiGenerateButton.isEnabled = false
            aiGenerateButton.text = getString(R.string.ai_generate) + "..."

            Thread {
                val result = requestPatternFromAi(prompt)
                runOnUiThread {
                    aiGenerateButton.isEnabled = true
                    aiGenerateButton.text = getString(R.string.ai_generate)
                    when (result) {
                        is AiResult.Success -> patternInput.setText(result.pattern)
                        is AiResult.Error -> Toast.makeText(this, result.messageRes, Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }

        addPulseButton.setOnClickListener {
            val pulseText = pulseDurationInput.text.toString().trim()
            val pauseText = pauseDurationInput.text.toString().trim()

            if (pulseText.isEmpty() || pauseText.isEmpty()) {
                Toast.makeText(this, R.string.missing_pulse_pause, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val pulse = pulseText.toLongOrNull()
            val pause = pauseText.toLongOrNull()

            if (pulse == null || pause == null || pulse < 0L || pause < 0L) {
                Toast.makeText(this, R.string.invalid_pattern, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val current = patternInput.text.toString().trim()
            val addition = if (current.isEmpty()) {
                "0, $pulse, $pause"
            } else {
                "$current, $pulse, $pause"
            }
            patternInput.setText(addition)
        }

        clearPatternButton.setOnClickListener {
            patternInput.setText("")
            patternNameInput.setText("")
            senderNameInput.setText("")
            muteWhenNoSender = false
            muteWhenNoSenderSwitch.isChecked = false
            aiPromptInput.setText("")
            pulseDurationInput.setText("")
            pauseDurationInput.setText("")
            currentSelectedStorageKey = null
            currentSelectedKind = null
        }

        openAppNotificationSettingsButton.setOnClickListener {
            val selectedInput = packageNameInput.text.toString().trim()
            val targetPackage = resolvePackageFromInput(selectedInput)

            if (targetPackage.isNullOrEmpty()) {
                Toast.makeText(this, R.string.missing_package, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, targetPackage)
                }
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$targetPackage")
                }
            }
            startActivity(intent)
        }

        configuredAdapter = ConfiguredAdapter()
        configuredListView.adapter = configuredAdapter
        configuredListView.setOnItemClickListener { _, _, position, _ ->
            val entry = configuredEntries.getOrNull(position) ?: return@setOnItemClickListener
            fillFormFromEntry(entry)
        }

        refreshConfiguredList()
        updateSearchResults(packageNameInput.text.toString())

        shortVibrationButton.setOnClickListener {
            vibrate(longArrayOf(0, 150))
            addVibrationPattern(patternInput, "150, 80")
        }

        mediumVibrationButton.setOnClickListener {
            vibrate(longArrayOf(0, 300))
            addVibrationPattern(patternInput, "300, 80")
        }

        longVibrationButton.setOnClickListener {
            vibrate(longArrayOf(0, 500))
            addVibrationPattern(patternInput, "500, 80")
        }
        muteWhenNoSenderSwitch.setOnCheckedChangeListener { _, isChecked ->
            muteWhenNoSender = isChecked
        }

        saveButton.setOnClickListener {
            val selectedInput = packageNameInput.text.toString().trim()
            val targetPackage = resolvePackageFromInput(selectedInput)

            if (targetPackage.isNullOrEmpty()) {
                Toast.makeText(this, R.string.missing_package, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val patternText = patternInput.text.toString().trim()
            val patternName = patternNameInput.text.toString().trim()
            val senderName = senderNameInput.text.toString().trim()
            val newStorageKey = if (senderName.isNotEmpty()) {
                PatternStore.senderStorageKey(targetPackage, senderName)
            } else {
                PatternStore.appStorageKey(targetPackage)
            }
            val saved = if (senderName.isNotEmpty()) {
                PatternStore.saveSenderPattern(this, targetPackage, senderName, patternText, patternName)
            } else {
                PatternStore.savePattern(this, targetPackage, patternText, patternName)
            }
            if (saved) {
                if (currentSelectedStorageKey != null &&
                    currentSelectedKind != null &&
                    currentSelectedStorageKey != newStorageKey
                ) {
                    PatternStore.deletePatternByKey(
                        this,
                        currentSelectedStorageKey!!,
                        currentSelectedKind!!
                    )
                }
                PatternStore.setMuteWhenNoSender(this, targetPackage, muteWhenNoSender)
                currentSelectedStorageKey = newStorageKey
                currentSelectedKind = if (senderName.isNotEmpty()) {
                    PatternStore.PatternKind.SENDER
                } else {
                    PatternStore.PatternKind.APP
                }
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
                refreshConfiguredList()
            } else {
                Toast.makeText(this, R.string.invalid_pattern, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addVibrationPattern(editText: EditText, pattern: String) {
        val currentText = editText.text.toString().trim()
        val newText = if (currentText.isEmpty()) "0, $pattern" else "$currentText, $pattern"
        editText.setText(newText)
    }

    private fun vibrate(pattern: LongArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    private fun loadInstalledApps() {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null)
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        
        appList.clear() // Make sure list is empty before populating
        nameToPackage.clear()

        for (info in resolveInfos) {
            val appName = info.loadLabel(pm).toString()
            val packageName = info.activityInfo.packageName
            // The check for duplicates must be by package name, not app name.
            if (appName.isNotEmpty() && packageName.isNotEmpty() && !appList.any { it.packageName == packageName }) {
                appList.add(AppEntry(appName, packageName))
            }
        }
        appList.sortBy { it.name.lowercase() } // Sort case-insensitively
        for (entry in appList) {
            if (!nameToPackage.containsKey(entry.name)) {
                nameToPackage[entry.name] = entry.packageName
            }
        }
    }

    private fun resolvePackageFromInput(input: String): String? {
        if (input.isBlank()) return null
        if (appList.any { it.packageName == input }) return input
        nameToPackage[input]?.let { return it }
        return appList.firstOrNull { it.name.equals(input, ignoreCase = true) }?.packageName
    }

    private fun refreshConfiguredList() {
        val stored = PatternStore.getAllPatternTexts(this)
        configuredEntries.clear()

        for (item in stored) {
            if (item.packageName.isBlank()) {
                PatternStore.deletePatternByKey(this, item.storageKey, item.kind)
                continue
            }

            val appName = appList.firstOrNull { it.packageName == item.packageName }?.name
            if (appName == null) {
                // Skip unknown apps to avoid cluttering the list.
                continue
            }
            configuredEntries.add(
                ConfigEntry(
                    appName,
                    item.packageName,
                    item.pattern,
                    item.name,
                    item.sender,
                    item.muteWhenNoSender,
                    item.storageKey,
                    item.kind
                )
            )
        }

        configuredAdapter.notifyDataSetChanged()
    }

    private inner class ConfiguredAdapter : BaseAdapter() {
        override fun getCount(): Int = configuredEntries.size

        override fun getItem(position: Int): Any = configuredEntries[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
            val view = convertView ?: layoutInflater.inflate(
                R.layout.list_item_configured,
                parent,
                false
            )
            val entry = configuredEntries[position]
            val iconView = view.findViewById<android.widget.ImageView>(R.id.appIcon)
            val textView = view.findViewById<android.widget.TextView>(R.id.configText)
            val deleteButton = view.findViewById<Button>(R.id.deleteButton)

            val senderLabel = entry.sender?.let { " · $it" } ?: ""
            val muteLabel = if (entry.muteWhenNoSender) " · 무음" else ""
            textView.text = "${entry.appName} · ${entry.patternName}${senderLabel}${muteLabel}"

            try {
                val icon = packageManager.getApplicationIcon(entry.packageName)
                iconView.setImageDrawable(icon)
            } catch (_: PackageManager.NameNotFoundException) {
                iconView.setImageResource(R.drawable.ic_launcher)
            }

            view.setOnClickListener {
                fillFormFromEntry(entry)
            }

            deleteButton.setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.delete_title)
                    .setMessage(R.string.delete_message)
                    .setPositiveButton(R.string.delete_confirm) { _, _ ->
                        PatternStore.deletePatternByKey(
                            this@MainActivity,
                            entry.storageKey,
                            entry.kind
                        )
                        refreshConfiguredList()
                        Toast.makeText(this@MainActivity, R.string.deleted, Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.delete_cancel, null)
                    .show()
            }
            return view
        }
    }

    private inner class SearchAdapter : BaseAdapter() {
        override fun getCount(): Int = searchResults.size

        override fun getItem(position: Int): Any = searchResults[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
            val view = convertView ?: layoutInflater.inflate(
                R.layout.list_item_text,
                parent,
                false
            )
            val entry = searchResults[position]
            val iconView = view.findViewById<android.widget.ImageView>(R.id.appIcon)
            val textView = view.findViewById<android.widget.TextView>(android.R.id.text1)
            textView.text = entry.name

            try {
                val icon = packageManager.getApplicationIcon(entry.packageName)
                iconView.setImageDrawable(icon)
            } catch (_: PackageManager.NameNotFoundException) {
                iconView.setImageResource(R.drawable.ic_launcher)
            }
            return view
        }
    }

    private fun fillFormFromEntry(entry: ConfigEntry) {
        val packageNameInput = findViewById<EditText>(R.id.packageNameInput)
        val patternNameInput = findViewById<EditText>(R.id.patternNameInput)
        val senderNameInput = findViewById<EditText>(R.id.senderNameInput)
        val patternInput = findViewById<EditText>(R.id.patternInput)
        val muteWhenNoSenderSwitch =
            findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(
                R.id.muteWhenNoSenderSwitch
            )

        isProgrammaticFill = true
        packageNameInput.setText(entry.appName)
        patternNameInput.setText(entry.patternName)
        senderNameInput.setText(entry.sender.orEmpty())
        muteWhenNoSender = entry.muteWhenNoSender
        muteWhenNoSenderSwitch.isChecked = muteWhenNoSender
        patternInput.setText(entry.pattern)
        isProgrammaticFill = false
        currentSelectedStorageKey = entry.storageKey
        currentSelectedKind = entry.kind
    }

    private fun updateSearchResults(query: String) {
        val normalized = query.trim().lowercase()
        searchResults.clear()
        searchDisplayList.clear()

        if (normalized.isNotEmpty()) {
            val matches = appList.filter { it.name.lowercase().contains(normalized) }
            searchResults.addAll(matches)
            searchDisplayList.addAll(matches.map { it.name })
        }

        searchAdapter.notifyDataSetChanged()
    }

    // muteWhenNoSender state is reflected by the switch

    private sealed class AiResult {
        data class Success(val pattern: String) : AiResult()
        data class Error(val messageRes: Int) : AiResult()
    }

    private fun requestPatternFromAi(userPrompt: String): AiResult {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .build()

            val body = JSONObject()
                .put("model", "gpt-5-mini")
                .put("user_prompt", userPrompt)

            val requestBody = body.toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("http://alarmcon-lb-2090533585.ap-southeast-2.elb.amazonaws.com/v1/vibration/pattern")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            for (attempt in 1..2) {
                try {
                    val response = client.newCall(request).execute()
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Log.e("AlarmconAI", "Request failed: ${response.code} $responseBody")
                        if (response.code == 429) {
                            return AiResult.Error(R.string.ai_rate_limited)
                        }
                        return AiResult.Error(R.string.ai_request_failed)
                    }

                    val patternText = JSONObject(responseBody).optString("pattern", "").trim()
                        .ifEmpty { return AiResult.Error(R.string.ai_invalid_response) }
                    val parsed = PatternStore.parsePatternText(patternText)
                        ?: return AiResult.Error(R.string.ai_invalid_response)
                    return AiResult.Success(parsed.joinToString(", "))
                } catch (e: SocketTimeoutException) {
                    Log.e("AlarmconAI", "Request timeout (attempt $attempt)", e)
                    if (attempt == 2) {
                        return AiResult.Error(R.string.ai_request_failed)
                    }
                } catch (e: Exception) {
                    Log.e("AlarmconAI", "Request error", e)
                    return AiResult.Error(R.string.ai_request_failed)
                }
            }
            AiResult.Error(R.string.ai_request_failed)
        } catch (e: Exception) {
            Log.e("AlarmconAI", "Request error", e)
            AiResult.Error(R.string.ai_request_failed)
        }
    }

}
