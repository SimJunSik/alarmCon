package com.alarmcon.app

import android.Manifest
import android.content.Context
import android.content.ComponentName
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
import androidx.appcompat.app.AppCompatDelegate
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.LocaleListCompat
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
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
    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                showAppNotificationsDialog()
            }
        }

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
        val aiGenerateProgress = findViewById<android.widget.ProgressBar>(R.id.aiGenerateProgress)
        val pulseDurationInput = findViewById<EditText>(R.id.pulseDurationInput)
        val pauseDurationInput = findViewById<EditText>(R.id.pauseDurationInput)
        val pulseStrengthInput = findViewById<EditText>(R.id.pulseStrengthInput)
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
        val patternPreviewContainer =
            findViewById<android.widget.LinearLayout>(R.id.patternPreviewContainer)
        val patternPreviewEmpty =
            findViewById<android.widget.TextView>(R.id.patternPreviewEmpty)
        val selectedAppRow = findViewById<android.widget.LinearLayout>(R.id.selectedAppRow)
        val selectedAppIcon = findViewById<android.widget.ImageView>(R.id.selectedAppIcon)
        val selectedAppName = findViewById<android.widget.TextView>(R.id.selectedAppName)

        openSettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        // Reset to system locale (in case a manual override was previously set).
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())

        if (!isNotificationListenerEnabled()) {
            showNotificationAccessDialog()
        }
        requestNotificationPermissionIfNeeded()


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
            configuredAdapter.notifyDataSetChanged()
            updateSelectedAppHeader(
                selectedAppRow,
                selectedAppIcon,
                selectedAppName,
                entry.packageName,
                entry.name
            )
        }

        packageNameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                updateSearchResults(s?.toString().orEmpty())
                if (!isProgrammaticFill) {
                    currentSelectedStorageKey = null
                    currentSelectedKind = null
                    configuredAdapter.notifyDataSetChanged()
                }
                updateSelectedAppHeader(
                    selectedAppRow,
                    selectedAppIcon,
                    selectedAppName,
                    resolvePackageFromInput(s?.toString().orEmpty()),
                    s?.toString().orEmpty()
                )
            }
        })

        patternInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                updatePatternPreview(
                    patternPreviewContainer,
                    patternPreviewEmpty,
                    s?.toString().orEmpty()
                )
            }
        })

        testVibrationButton.setOnClickListener {
            val patternText = patternInput.text.toString().trim()
            val patternData = PatternStore.parsePatternText(patternText)
            if (patternData == null) {
                Toast.makeText(this, R.string.invalid_pattern, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            vibrate(patternData)
        }

        aiGenerateButton.setOnClickListener {
            val prompt = aiPromptInput.text.toString().trim()
            aiGenerateButton.isEnabled = false
            aiGenerateButton.text = getString(R.string.ai_generate_loading)
            aiGenerateProgress.visibility = android.view.View.VISIBLE

            Thread {
                val result = requestPatternFromAi(prompt)
                runOnUiThread {
                    aiGenerateButton.isEnabled = true
                    aiGenerateButton.text = getString(R.string.ai_generate)
                    aiGenerateProgress.visibility = android.view.View.GONE
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
            val strengthText = pulseStrengthInput.text.toString().trim()

            if (pulseText.isEmpty() || pauseText.isEmpty()) {
                Toast.makeText(this, R.string.missing_pulse_pause, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val pulse = pulseText.toLongOrNull()
            val pause = pauseText.toLongOrNull()
            val strength = strengthText.toIntOrNull()?.coerceIn(1, 255)

            if (pulse == null || pause == null || pulse < 0L || pause < 0L) {
                Toast.makeText(this, R.string.invalid_pattern, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (strengthText.isNotEmpty() && strength == null) {
                Toast.makeText(this, R.string.invalid_pattern, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val current = patternInput.text.toString().trim()
            val pulseToken = if (strength != null) "$pulse:$strength" else pulse.toString()
            val addition = if (current.isEmpty()) {
                "0, $pulseToken, $pause"
            } else {
                "$current, $pulseToken, $pause"
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
            pulseStrengthInput.setText("")
            currentSelectedStorageKey = null
            currentSelectedKind = null
            configuredAdapter.notifyDataSetChanged()
            updateSelectedAppHeader(
                selectedAppRow,
                selectedAppIcon,
                selectedAppName,
                null,
                ""
            )
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
            if (entry.storageKey == currentSelectedStorageKey && entry.kind == currentSelectedKind) {
                clearFormSelection(
                    selectedAppRow,
                    selectedAppIcon,
                    selectedAppName,
                    packageNameInput,
                    patternNameInput,
                    senderNameInput,
                    patternInput,
                    aiPromptInput,
                    pulseDurationInput,
                    pauseDurationInput,
                    pulseStrengthInput,
                    muteWhenNoSenderSwitch
                )
            } else {
                fillFormFromEntry(entry)
                updateSelectedAppHeader(
                    selectedAppRow,
                    selectedAppIcon,
                    selectedAppName,
                    entry.packageName,
                    entry.appName
                )
            }
        }

        refreshConfiguredList()
        updateSearchResults(packageNameInput.text.toString())
        updateSelectedAppHeader(selectedAppRow, selectedAppIcon, selectedAppName, null, "")
        updatePatternPreview(patternPreviewContainer, patternPreviewEmpty, patternInput.text.toString())

        shortVibrationButton.setOnClickListener {
            vibrate(PatternStore.PatternData(longArrayOf(0, 150), intArrayOf(0, 255)))
            addVibrationPattern(patternInput, "150:255, 80")
        }

        mediumVibrationButton.setOnClickListener {
            vibrate(PatternStore.PatternData(longArrayOf(0, 300), intArrayOf(0, 255)))
            addVibrationPattern(patternInput, "300:255, 80")
        }

        longVibrationButton.setOnClickListener {
            vibrate(PatternStore.PatternData(longArrayOf(0, 500), intArrayOf(0, 255)))
            addVibrationPattern(patternInput, "500:255, 80")
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

    private fun vibrate(pattern: PatternStore.PatternData) {
        vibrator.vibrate(
            VibrationEffect.createWaveform(pattern.timings, pattern.amplitudes, -1)
        )
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

            val isSelected = entry.storageKey == currentSelectedStorageKey &&
                entry.kind == currentSelectedKind
            val highlightColor = if (isSelected) {
                ContextCompat.getColor(this@MainActivity, R.color.dropdown_selected)
            } else {
                ContextCompat.getColor(this@MainActivity, android.R.color.transparent)
            }
            view.setBackgroundColor(highlightColor)
            val textColor = if (isSelected) {
                ContextCompat.getColor(this@MainActivity, R.color.primary)
            } else {
                ContextCompat.getColor(this@MainActivity, R.color.on_surface)
            }
            textView.setTextColor(textColor)

            try {
                val icon = packageManager.getApplicationIcon(entry.packageName)
                iconView.setImageDrawable(icon)
            } catch (_: PackageManager.NameNotFoundException) {
                iconView.setImageResource(R.drawable.ic_launcher)
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
        configuredAdapter.notifyDataSetChanged()
    }

    private fun updateSelectedAppHeader(
        row: android.widget.LinearLayout,
        iconView: android.widget.ImageView,
        nameView: android.widget.TextView,
        packageName: String?,
        displayName: String
    ) {
        if (packageName.isNullOrBlank()) {
            row.visibility = android.view.View.VISIBLE
            iconView.setImageDrawable(null)
            iconView.visibility = android.view.View.GONE
            nameView.text = getString(R.string.selected_app_placeholder)
            return
        }

        row.visibility = android.view.View.VISIBLE
        iconView.visibility = android.view.View.VISIBLE
        nameView.text = if (displayName.isNotBlank()) displayName else packageName
        try {
            val icon = packageManager.getApplicationIcon(packageName)
            iconView.setImageDrawable(icon)
        } catch (_: PackageManager.NameNotFoundException) {
            iconView.setImageResource(R.drawable.ic_launcher)
        }
    }

    private fun updatePatternPreview(
        container: android.widget.LinearLayout,
        emptyView: android.widget.TextView,
        patternText: String
    ) {
        container.removeAllViews()
        val pattern = PatternStore.parsePatternText(patternText)
        if (pattern == null || pattern.timings.isEmpty()) {
            emptyView.visibility = android.view.View.VISIBLE
            return
        }
        emptyView.visibility = android.view.View.GONE

        val total = pattern.timings.sum().coerceAtLeast(1L)
        val totalWidthPx = dpToPx(240)
        val minWidthPx = dpToPx(6)
        val minHeightPx = dpToPx(6)
        val maxHeightPx = dpToPx(16)
        val gapPx = dpToPx(4)

        for (index in pattern.timings.indices) {
            val value = pattern.timings[index].coerceAtLeast(0L)
            val proportional = (totalWidthPx * value / total).toInt().coerceAtLeast(minWidthPx)
            val segment = android.view.View(this)
            val isVibrate = index % 2 == 1
            val amplitude = pattern.amplitudes.getOrNull(index) ?: 0
            val height = if (isVibrate) {
                val scaled = (maxHeightPx * amplitude / 255f).toInt()
                scaled.coerceIn(minHeightPx, maxHeightPx)
            } else {
                minHeightPx
            }
            val params = android.widget.LinearLayout.LayoutParams(proportional, height)
            if (index < pattern.timings.lastIndex) {
                params.marginEnd = gapPx
            }
            segment.layoutParams = params
            segment.background = buildPreviewSegmentDrawable(isVibrate)
            container.addView(segment)
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    private fun buildPreviewSegmentDrawable(isVibrate: Boolean): GradientDrawable {
        val colorRes = if (isVibrate) R.color.primary else R.color.outline
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(6).toFloat()
            setColor(ContextCompat.getColor(this@MainActivity, colorRes))
        }
    }

    private fun clearFormSelection(
        selectedRow: android.widget.LinearLayout,
        selectedIcon: android.widget.ImageView,
        selectedName: android.widget.TextView,
        packageNameInput: EditText,
        patternNameInput: EditText,
        senderNameInput: EditText,
        patternInput: EditText,
        aiPromptInput: EditText,
        pulseDurationInput: EditText,
        pauseDurationInput: EditText,
        pulseStrengthInput: EditText,
        muteSwitch: com.google.android.material.switchmaterial.SwitchMaterial
    ) {
        isProgrammaticFill = true
        packageNameInput.setText("")
        patternNameInput.setText("")
        senderNameInput.setText("")
        patternInput.setText("")
        aiPromptInput.setText("")
        pulseDurationInput.setText("")
        pauseDurationInput.setText("")
        pulseStrengthInput.setText("")
        isProgrammaticFill = false

        muteWhenNoSender = false
        muteSwitch.isChecked = false
        currentSelectedStorageKey = null
        currentSelectedKind = null
        configuredAdapter.notifyDataSetChanged()
        updateSelectedAppHeader(selectedRow, selectedIcon, selectedName, null, "")
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

    private fun isNotificationListenerEnabled(): Boolean {
        val enabled =
            Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val components = enabled.split(":")
        for (component in components) {
            val componentName = ComponentName.unflattenFromString(component)
            if (componentName?.packageName == packageName) {
                return true
            }
        }
        return false
    }

    private fun showNotificationAccessDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.notification_access_title)
            .setMessage(R.string.notification_access_message)
            .setPositiveButton(R.string.notification_access_open) { _, _ ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            .setNegativeButton(R.string.notification_access_later, null)
            .show()
    }

    private fun areAppNotificationsEnabled(): Boolean {
        return NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    private fun showAppNotificationsDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.app_notifications_title)
            .setMessage(R.string.app_notifications_message)
            .setPositiveButton(R.string.app_notifications_open) { _, _ ->
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    }
                } else {
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                }
                startActivity(intent)
            }
            .setNegativeButton(R.string.app_notifications_later, null)
            .show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!areAppNotificationsEnabled()) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            if (!areAppNotificationsEnabled()) {
                showAppNotificationsDialog()
            }
        }
    }

    private sealed class AiResult {
        data class Success(val pattern: String) : AiResult()
        data class Error(val messageRes: Int) : AiResult()
    }

    private fun requestPatternFromAi(userPrompt: String): AiResult {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.MINUTES)
                .writeTimeout(3, TimeUnit.MINUTES)
                .callTimeout(3, TimeUnit.MINUTES)
                .build()

            val body = JSONObject()
                .put("model", "gpt-5-mini")
                .put("user_prompt", userPrompt)

            val requestBody = body.toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("https://alarmcon.xyz/v1/vibration/pattern")
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
                    PatternStore.parsePatternText(patternText)
                        ?: return AiResult.Error(R.string.ai_invalid_response)
                    return AiResult.Success(patternText)
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
