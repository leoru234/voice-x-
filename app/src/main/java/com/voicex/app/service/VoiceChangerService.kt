package com.voicex.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.voicex.app.R
import com.voicex.app.audio.AppMode
import com.voicex.app.audio.AudioProcessor
import com.voicex.app.audio.VoicePreset
import com.voicex.app.ui.MainActivity

class VoiceChangerService : Service() {

    companion object {
        const val CHANNEL_ID = "voicex_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.voicex.app.STOP"
    }

    private val binder = LocalBinder()
    private val processor = AudioProcessor()
    private var wakeLock: PowerManager.WakeLock? = null

    var isActive = false
        private set
    var currentPreset = VoicePreset.ALL[0]
        private set
    var currentMode = AppMode.CALL
        private set

    var onLevelUpdate: ((Float) -> Unit)? = null
    var onStateChange: ((Boolean) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): VoiceChangerService = this@VoiceChangerService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        processor.onLevelUpdate = { level -> onLevelUpdate?.invoke(level) }
        processor.onError = { msg -> android.util.Log.e("VoiceX", "Audio error: $msg") }
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceX::AudioLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopProcessing()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        stopProcessing()
        super.onDestroy()
    }

    fun startProcessing(): Boolean {
        if (isActive) return true
        wakeLock?.acquire(30 * 60 * 1000L)
        val success = processor.start()
        if (success) {
            isActive = true
            onStateChange?.invoke(true)
            updateNotification()
        } else {
            wakeLock?.release()
        }
        return success
    }

    fun stopProcessing() {
        if (!isActive) return
        processor.stop()
        isActive = false
        if (wakeLock?.isHeld == true) wakeLock?.release()
        onStateChange?.invoke(false)
        updateNotification()
    }

    fun applyPreset(preset: VoicePreset) {
        currentPreset = preset
        processor.pitchSemitones = preset.pitchSemitones
        processor.reverbMix = preset.reverbMix
        processor.echoDuration = preset.echoDuration
        processor.bassBoost = preset.bassBoost
        processor.volumeGain = preset.volume
        processor.robotize = preset.robotize
        processor.whisperMode = preset.whisper
    }

    fun setPitch(semitones: Float) { processor.pitchSemitones = semitones }
    fun setVolume(gain: Float) { processor.volumeGain = gain }
    fun setReverb(mix: Float) { processor.reverbMix = mix }
    fun setEcho(duration: Float) { processor.echoDuration = duration }
    fun setBassBoost(amount: Float) { processor.bassBoost = amount }
    fun setNoiseCancel(enabled: Boolean) { processor.noiseCancel = enabled }
    fun setWhisper(enabled: Boolean) { processor.whisperMode = enabled }
    fun setRobotize(enabled: Boolean) { processor.robotize = enabled }
    fun setMode(mode: AppMode) { currentMode = mode }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "VoiceX Voice Changer", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Running voice changer in background"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, VoiceChangerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val statusText = if (isActive) "Voice changing active" else "Paused"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VoiceX")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification())
    }
}
