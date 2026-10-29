package com.gazali.livepace

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQ_LOCATION = 10
    }

    private lateinit var targetFast: EditText
    private lateinit var targetSlow: EditText
    private lateinit var vibrateSwitch: Switch
    private lateinit var voiceGroup: RadioGroup
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        targetFast = findViewById(R.id.targetFast)
        targetSlow = findViewById(R.id.targetSlow)
        vibrateSwitch = findViewById(R.id.vibrateSwitch)
        voiceGroup = findViewById(R.id.voiceGroup)
        statusText = findViewById(R.id.statusText)

        loadSettings()

        findViewById<Button>(R.id.startBtn).setOnClickListener {
            if (saveSettings()) startBubble()
        }
        findViewById<Button>(R.id.stopBtn).setOnClickListener {
            startService(Intent(this, PaceService::class.java).setAction(PaceService.ACTION_STOP))
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun loadSettings() {
        val prefs = Prefs.get(this)
        val fast = prefs.getInt(Prefs.TARGET_FAST, -1)
        val slow = prefs.getInt(Prefs.TARGET_SLOW, -1)
        if (fast > 0) targetFast.setText(Prefs.formatPace(fast))
        if (slow > 0) targetSlow.setText(Prefs.formatPace(slow))
        vibrateSwitch.isChecked = prefs.getBoolean(Prefs.VIBRATE, true)
        voiceGroup.check(
            when (prefs.getInt(Prefs.VOICE_INTERVAL, 0)) {
                30 -> R.id.voice30
                60 -> R.id.voice60
                else -> R.id.voiceOff
            }
        )
    }

    /** Returns false (and shows why) if the target paces can't be read. */
    private fun saveSettings(): Boolean {
        val fastText = targetFast.text.toString().trim()
        val slowText = targetSlow.text.toString().trim()

        var fast = -1
        var slow = -1
        if (fastText.isNotEmpty() || slowText.isNotEmpty()) {
            val a = Prefs.parsePace(fastText)
            val b = Prefs.parsePace(slowText)
            if (a == null || b == null) {
                Toast.makeText(this, "Enter both paces like 5:20 and 5:40, or leave both empty", Toast.LENGTH_LONG).show()
                return false
            }
            fast = minOf(a, b)
            slow = maxOf(a, b)
            targetFast.setText(Prefs.formatPace(fast))
            targetSlow.setText(Prefs.formatPace(slow))
        }

        val voice = when (voiceGroup.checkedRadioButtonId) {
            R.id.voice30 -> 30
            R.id.voice60 -> 60
            else -> 0
        }

        Prefs.get(this).edit()
            .putInt(Prefs.TARGET_FAST, fast)
            .putInt(Prefs.TARGET_SLOW, slow)
            .putBoolean(Prefs.VIBRATE, vibrateSwitch.isChecked)
            .putInt(Prefs.VOICE_INTERVAL, voice)
            .apply()
        return true
    }

    private fun hasLocation(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startBubble() {
        // 1. Location (and notifications on Android 13+, so the service notification shows)
        if (!hasLocation()) {
            val perms = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            requestPermissions(perms.toTypedArray(), REQ_LOCATION)
            return
        }

        // 2. "Display over other apps"
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Turn on \"Allow display over other apps\", then come back and tap Start", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }

        // 3. Go
        startForegroundService(Intent(this, PaceService::class.java))
        Toast.makeText(this, "Bubble on. Now open Runna.", Toast.LENGTH_SHORT).show()
        updateStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            if (hasLocation()) {
                startBubble()
            } else {
                Toast.makeText(this, "Live Pace needs precise location to measure pace", Toast.LENGTH_LONG).show()
            }
            updateStatus()
        }
    }

    private fun updateStatus() {
        val missing = mutableListOf<String>()
        if (!hasLocation()) missing.add("• Precise location: not allowed yet")
        if (!Settings.canDrawOverlays(this)) missing.add("• Display over other apps: not allowed yet")
        statusText.text = if (missing.isEmpty()) "" else "Needed before the first run:\n" + missing.joinToString("\n")
    }
}
