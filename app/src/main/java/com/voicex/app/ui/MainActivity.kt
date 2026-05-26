package com.voicex.app.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.voicex.app.audio.AppMode
import com.voicex.app.audio.VoicePreset
import com.voicex.app.databinding.ActivityMainBinding
import com.voicex.app.service.VoiceChangerService
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var service: VoiceChangerService? = null
    private var bound = false
    private var sessionSeconds = 0
    private val handler = Handler(Looper.getMainLooper())
    private var timerRunnable: Runnable? = null
    private var latencyMs = 4

    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as VoiceChangerService.LocalBinder).getService()
            bound = true
            service?.onLevelUpdate = { level -> runOnUiThread { updateLevel(level) } }
            service?.onStateChange = { active -> runOnUiThread { updateMicUI(active) } }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            bound = false
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        startAndBindService()
        setupModeToggle()
        setupPresets()
        setupSliders()
        setupEffectToggles()
        setupMicButton()
        startFakeStats()
    }

    private fun startAndBindService() {
        val intent = Intent(this, VoiceChangerService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, serviceConn, Context.BIND_AUTO_CREATE)
    }

    private fun setupMicButton() {
        binding.btnMic.setOnClickListener {
            if (service?.isActive == true) {
                service?.stopProcessing()
            } else {
                if (checkMicPermission()) startVoiceProcessing()
            }
        }
    }

    private fun checkMicPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) return true
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 100 && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
            startVoiceProcessing()
        } else {
            showSnack("Mic permission chahiye!")
        }
    }

    private fun startVoiceProcessing() {
        val success = service?.startProcessing() ?: false
        if (!success) showSnack("Audio device error. App restart karo.")
    }

    private fun updateMicUI(active: Boolean) {
        binding.btnMic.isSelected = active
        binding.tvMicLabel.text = if (active) "ACTIVE — VOICE CHANGING" else "TAP TO START"
        binding.tvStatus.text = if (active) "● PROCESSING" else "● READY"
        binding.tvStatus.setTextColor(
            if (active) getColor(android.R.color.holo_green_light)
            else getColor(android.R.color.darker_gray)
        )
        if (active) startSessionTimer() else stopSessionTimer()
    }

    private fun setupModeToggle() {
        binding.btnCallMode.setOnClickListener { setMode(AppMode.CALL) }
        binding.btnGameMode.setOnClickListener { setMode(AppMode.GAME) }
    }

    private fun setMode(mode: AppMode) {
        service?.setMode(mode)
        binding.btnCallMode.isSelected = mode == AppMode.CALL
        binding.btnGameMode.isSelected = mode == AppMode.GAME
        latencyMs = if (mode == AppMode.GAME) 2 else 4
        binding.tvLatency.text = "${latencyMs}ms"
        showSnack(if (mode == AppMode.GAME) "Game Mode: Ultra-low latency" else "Call Mode: HD clarity")
    }

    private fun setupPresets() {
        val views = listOf(
            binding.presetNormal, binding.presetRobot, binding.presetDeep,
            binding.presetChipmunk, binding.presetAlien, binding.presetGhost,
            binding.presetFemale, binding.presetRadio
        )
        views.forEachIndexed { idx, v ->
            val preset = VoicePreset.ALL[idx]
            v.tvPresetName.text = preset.name
            v.tvPresetEmoji.text = preset.emoji
            v.root.setOnClickListener {
                views.forEach { it.root.isSelected = false }
                v.root.isSelected = true
                service?.applyPreset(preset)
                applyPresetToSliders(preset)
                showSnack("${preset.name} voice active!")
            }
        }
        views[0].root.isSelected = true
    }

    private fun applyPresetToSliders(p: VoicePreset) {
        binding.seekPitch.progress = (p.pitchSemitones + 12).toInt()
        binding.seekVolume.progress = (p.volume * 50).toInt()
        binding.seekReverb.progress = (p.reverbMix * 100).toInt()
        binding.seekEcho.progress = (p.echoDuration * 100).toInt()
        updateSliderLabels()
    }

    private fun setupSliders() {
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                when (sb.id) {
                    binding.seekPitch.id  -> service?.setPitch(p - 12f)
                    binding.seekVolume.id -> service?.setVolume(p / 50f)
                    binding.seekReverb.id -> service?.setReverb(p / 100f)
                    binding.seekEcho.id   -> service?.setEcho(p / 100f)
                }
                updateSliderLabels()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        }
        binding.seekPitch.max = 24; binding.seekPitch.progress = 12
        binding.seekVolume.max = 100; binding.seekVolume.progress = 50
        binding.seekReverb.max = 100; binding.seekReverb.progress = 0
        binding.seekEcho.max = 100; binding.seekEcho.progress = 0
        binding.seekPitch.setOnSeekBarChangeListener(listener)
        binding.seekVolume.setOnSeekBarChangeListener(listener)
        binding.seekReverb.setOnSeekBarChangeListener(listener)
        binding.seekEcho.setOnSeekBarChangeListener(listener)
        updateSliderLabels()
    }

    private fun updateSliderLabels() {
        val pitch = binding.seekPitch.progress - 12
        binding.tvPitchVal.text = if (pitch >= 0) "+$pitch" else "$pitch"
        binding.tvVolumeVal.text = "${(binding.seekVolume.progress * 2)}%"
        binding.tvReverbVal.text = "${binding.seekReverb.progress}%"
        binding.tvEchoVal.text   = "${binding.seekEcho.progress}%"
    }

    private fun setupEffectToggles() {
        binding.toggleNoise.setOnClickListener {
            val on = it.isSelected.not(); it.isSelected = on; service?.setNoiseCancel(on)
        }
        binding.toggleBass.setOnClickListener {
            val on = it.isSelected.not(); it.isSelected = on; service?.setBassBoost(if (on) 0.7f else 0f)
        }
        binding.toggleWhisper.setOnClickListener {
            val on = it.isSelected.not(); it.isSelected = on; service?.setWhisper(on)
        }
        binding.toggleRobot.setOnClickListener {
            val on = it.isSelected.not(); it.isSelected = on; service?.setRobotize(on)
        }
    }

    private fun updateLevel(level: Float) {
        binding.waveformView.updateLevel(level)
        binding.levelBar.progress = (level * 100).toInt()
        val db = if (level > 0.001f) (20 * Math.log10(level.toDouble())).toInt() else -60
        binding.tvLevelVal.text = "${db} dB"
    }

    private fun startSessionTimer() {
        sessionSeconds = 0
        timerRunnable = object : Runnable {
            override fun run() {
                sessionSeconds++
                val m = sessionSeconds / 60; val s = sessionSeconds % 60
                binding.tvSessionTime.text = String.format(Locale.US, "%02d:%02d", m, s)
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(timerRunnable!!)
    }

    private fun stopSessionTimer() {
        timerRunnable?.let { handler.removeCallbacks(it) }
        sessionSeconds = 0
        binding.tvSessionTime.text = "00:00"
    }

    private fun startFakeStats() {
        val statsRunnable = object : Runnable {
            override fun run() {
                if (service?.isActive == true) {
                    val lat = latencyMs + (Math.random() * 2).toInt()
                    val cpu = 4 + (Math.random() * 6).toInt()
                    binding.tvLatStat.text = "${lat}ms"
                    binding.tvCpuStat.text = "${cpu}%"
                    binding.tvLatency.text = "${lat}ms"
                } else {
                    binding.tvLatStat.text = "--"
                    binding.tvCpuStat.text = "--"
                }
                handler.postDelayed(this, 800)
            }
        }
        handler.post(statsRunnable)
    }

    private fun showSnack(msg: String) {
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        if (bound) unbindService(serviceConn)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
