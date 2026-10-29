package com.gazali.livepace

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs

/**
 * Foreground service that listens to GPS, shows the floating pace bubble,
 * and handles vibration and voice alerts.
 */
class PaceService : Service(), LocationListener {

    companion object {
        const val ACTION_STOP = "com.gazali.livepace.STOP"
        private const val CHANNEL_ID = "live_pace"
        private const val NOTIFICATION_ID = 1

        private const val OUT_OF_RANGE_BEFORE_BUZZ_S = 5   // must be off-target this long
        private const val BUZZ_COOLDOWN_MS = 20_000L       // then at most one buzz per 20 s
        private const val GPS_LOST_MS = 5_000L
        private const val HOLD_TO_CLOSE_MS = 2_000L

        private const val COLOR_IN_RANGE = 0xFF2E7D32.toInt()
        private const val COLOR_TOO_SLOW = 0xFFC62828.toInt()
        private const val COLOR_TOO_FAST = 0xFF1565C0.toInt()
        private const val COLOR_NEUTRAL = 0xE6212121.toInt()
    }

    private enum class Zone { NONE, IN_RANGE, TOO_SLOW, TOO_FAST }

    private val handler = Handler(Looper.getMainLooper())
    private val calculator = PaceCalculator()

    private lateinit var windowManager: WindowManager
    private lateinit var locationManager: LocationManager
    private lateinit var audioManager: AudioManager

    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleBackground: GradientDrawable? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var focusRequest: AudioFocusRequest? = null

    // Settings
    private var targetFast = -1
    private var targetSlow = -1
    private var vibrateEnabled = true
    private var voiceIntervalS = 0

    // State
    private var started = false
    private var alertsPaused = false
    private var outOfRangeSeconds = 0
    private var lastBuzzMs = 0L
    private var lastSpokenMs = 0L

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutDown()
            return START_NOT_STICKY
        }

        loadSettings()
        if (started) {
            // Already running: "Start" was pressed again just to update settings.
            outOfRangeSeconds = 0
            return START_NOT_STICKY
        }

        val hasLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasLocation || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        started = true

        addBubble()
        startLocationUpdates()
        setUpVoice()
        lastSpokenMs = SystemClock.elapsedRealtime()
        handler.post(ticker)
        return START_NOT_STICKY
    }

    private fun loadSettings() {
        val prefs = Prefs.get(this)
        targetFast = prefs.getInt(Prefs.TARGET_FAST, -1)
        targetSlow = prefs.getInt(Prefs.TARGET_SLOW, -1)
        vibrateEnabled = prefs.getBoolean(Prefs.VIBRATE, true)
        voiceIntervalS = prefs.getInt(Prefs.VOICE_INTERVAL, 0)
    }

    // ---------------------------------------------------------------- notification

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Live pace", NotificationManager.IMPORTANCE_LOW)
        )

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PaceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Live Pace is running")
            .setContentText("Tap to change targets")
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---------------------------------------------------------------- bubble

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

    private fun addBubble() {
        val size = dp(84f)
        val background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(COLOR_NEUTRAL)
            setStroke(dp(2f), Color.WHITE)
        }
        val view = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setBackground(background)
            elevation = dp(6f).toFloat()
        }

        val prefs = Prefs.get(this)
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(Prefs.BUBBLE_X, dp(16f))
            y = prefs.getInt(Prefs.BUBBLE_Y, dp(200f))
        }

        view.setOnTouchListener(BubbleTouchListener(params))
        windowManager.addView(view, params)

        bubble = view
        bubbleParams = params
        bubbleBackground = background
        showText("GPS", "wait")
    }

    private inner class BubbleTouchListener(
        private val params: WindowManager.LayoutParams
    ) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var downAt = 0L
        private var moved = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    downAt = SystemClock.uptimeMillis()
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > dp(8f) || abs(dy) > dp(8f)) moved = true
                    if (moved) {
                        params.x = startX + dx
                        params.y = startY + dy
                        windowManager.updateViewLayout(v, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        Prefs.get(this@PaceService).edit()
                            .putInt(Prefs.BUBBLE_X, params.x)
                            .putInt(Prefs.BUBBLE_Y, params.y)
                            .apply()
                    } else if (SystemClock.uptimeMillis() - downAt >= HOLD_TO_CLOSE_MS) {
                        shutDown()
                    } else {
                        toggleAlerts()
                    }
                }
            }
            return true
        }
    }

    private fun toggleAlerts() {
        alertsPaused = !alertsPaused
        outOfRangeSeconds = 0
        Toast.makeText(
            this,
            if (alertsPaused) "Alerts paused" else "Alerts on",
            Toast.LENGTH_SHORT
        ).show()
        tick()
    }

    private fun showText(main: String, small: String) {
        val text = SpannableString("$main\n$small")
        text.setSpan(
            RelativeSizeSpan(0.5f), main.length + 1, text.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        bubble?.text = text
    }

    // ---------------------------------------------------------------- GPS

    private fun startLocationUpdates() {
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Toast.makeText(this, "Location permission missing", Toast.LENGTH_LONG).show()
            shutDown()
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, "This phone has no GPS provider", Toast.LENGTH_LONG).show()
            shutDown()
        }
    }

    override fun onLocationChanged(location: Location) {
        calculator.addFix(location, SystemClock.elapsedRealtime())
    }

    @Deprecated("Required on Android 10 and below")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {
        showText("GPS", "off")
    }

    // ---------------------------------------------------------------- every second

    private fun tick() {
        if (!started) return
        val now = SystemClock.elapsedRealtime()

        if (calculator.lastFixTimeMs == 0L || now - calculator.lastFixTimeMs > GPS_LOST_MS) {
            setColor(COLOR_NEUTRAL)
            showText("GPS", "wait")
            outOfRangeSeconds = 0
            return
        }

        val pace = calculator.currentPaceSecPerKm(now)
        if (pace == null) {
            setColor(COLOR_NEUTRAL)
            showText("--:--", "/km")
            outOfRangeSeconds = 0
            return
        }

        val zone = zoneFor(pace)
        setColor(
            if (alertsPaused) COLOR_NEUTRAL else when (zone) {
                Zone.IN_RANGE -> COLOR_IN_RANGE
                Zone.TOO_SLOW -> COLOR_TOO_SLOW
                Zone.TOO_FAST -> COLOR_TOO_FAST
                Zone.NONE -> COLOR_NEUTRAL
            }
        )
        showText(if (pace >= 60 * 60) "60+" else Prefs.formatPace(pace), if (alertsPaused) "paused" else "/km")

        // Vibration: only after being off-target for a few seconds, with a cooldown.
        val offTarget = zone == Zone.TOO_SLOW || zone == Zone.TOO_FAST
        if (!alertsPaused && vibrateEnabled && offTarget) {
            outOfRangeSeconds++
            if (outOfRangeSeconds >= OUT_OF_RANGE_BEFORE_BUZZ_S && now - lastBuzzMs >= BUZZ_COOLDOWN_MS) {
                buzz(zone)
                lastBuzzMs = now
            }
        } else {
            outOfRangeSeconds = 0
        }

        // Voice: speak pace at the chosen interval.
        if (voiceIntervalS > 0 && now - lastSpokenMs >= voiceIntervalS * 1000L) {
            speak(pace)
            lastSpokenMs = now
        }
    }

    private fun zoneFor(pace: Int): Zone {
        if (targetFast <= 0 || targetSlow <= 0) return Zone.NONE
        return when {
            pace > targetSlow -> Zone.TOO_SLOW
            pace < targetFast -> Zone.TOO_FAST
            else -> Zone.IN_RANGE
        }
    }

    private fun setColor(color: Int) {
        bubbleBackground?.setColor(color)
    }

    // ---------------------------------------------------------------- vibration

    private fun buzz(zone: Zone) {
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        // Too slow: two long buzzes. Too fast: three short buzzes.
        val pattern = if (zone == Zone.TOO_SLOW) {
            longArrayOf(0, 500, 250, 500)
        } else {
            longArrayOf(0, 150, 120, 150, 120, 150)
        }
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    // ---------------------------------------------------------------- voice

    private val speechAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun setUpVoice() {
        // Set up even when voice is off, so it can be switched on mid-run.
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale.ENGLISH)
                tts?.setAudioAttributes(speechAttributes)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) = releaseFocus()
                    @Deprecated("Required override")
                    override fun onError(utteranceId: String?) = releaseFocus()
                })
                ttsReady = true
            }
        }
        // Lowers the podcast volume briefly while the pace is spoken.
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(speechAttributes)
            .build()
    }

    private fun speak(pace: Int) {
        val engine = tts ?: return
        if (!ttsReady) return
        focusRequest?.let { audioManager.requestAudioFocus(it) }
        val minutes = pace / 60
        val seconds = pace % 60
        val phrase = when {
            seconds == 0 -> "$minutes flat"
            seconds < 10 -> "$minutes oh $seconds"
            else -> "$minutes $seconds"
        }
        engine.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "pace")
    }

    private fun releaseFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    // ---------------------------------------------------------------- cleanup

    private fun shutDown() {
        if (started) {
            handler.removeCallbacks(ticker)
            try { locationManager.removeUpdates(this) } catch (_: Exception) {}
            bubble?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
            bubble = null
            tts?.stop()
            tts?.shutdown()
            tts = null
            releaseFocus()
            calculator.reset()
            started = false
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        shutDown()
        super.onDestroy()
    }
}
