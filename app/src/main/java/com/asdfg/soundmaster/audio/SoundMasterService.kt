package com.asdfg.soundmaster.audio

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes.ALLOW_CAPTURE_BY_NONE
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.asdfg.soundmaster.R
import com.asdfg.soundmaster.adb.ShellExecutor
import java.util.Timer
import kotlin.concurrent.timerTask

/**
 * SoundMaster foreground service
 * Handles audio capture and routing for selected apps
 */
@RequiresApi(Build.VERSION_CODES.Q)
class SoundMasterService : Service() {

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    
    private lateinit var mediaSession: android.support.v4.media.session.MediaSessionCompat
    private var shellExecutor: ShellExecutor? = null
    
    var packageThreads = hashMapOf<String, PlayBackThread>()
    private var latencyUpdateTimer = Timer()

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            stopSelf()
            return
        }

        mediaSession = android.support.v4.media.session.MediaSessionCompat(this, TAG).apply {
            setCallback(object : android.support.v4.media.session.MediaSessionCompat.Callback() {
                override fun onPlay() {
                    handleMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
                }

                override fun onPause() {
                    handleMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
                }

                override fun onSkipToNext() {
                    handleMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
                }

                override fun onSkipToPrevious() {
                    handleMediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                }
                
                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    // Let the default implementation handle mapping to onPlay/onPause/etc.
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
            })
            isActive = true
        }

        // Volume Bridge: Trick AVRCP (Car) into controlling "Remote" volume,
        // which we map back to the real System Music stream (AirPods).
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        
        val volumeProvider = object : androidx.media.VolumeProviderCompat(
            VOLUME_CONTROL_RELATIVE,
            maxVol,
            currentVol
        ) {
            override fun onAdjustVolume(direction: Int) {
                // Forward the Car's command to the Phone's Master Volume
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    direction,
                    AudioManager.FLAG_SHOW_UI
                )
                // Update our internal state to match
                currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
        }
        mediaSession.setPlaybackToRemote(volumeProvider)

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setContentText(getString(R.string.service_notification_text, 0))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            // Add media style to notification for lockscreen controls
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.sessionToken))

        startForeground(
            NOTIFICATION_ID,
            builder.build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        // Update notification with latency info
        latencyUpdateTimer.schedule(timerTask {
            val avg = packageThreads.values.map { it.getLatency() }.average().toInt()
            packageThreads.values.forEach { it.loadedCycles = 0 }
            
            builder.setContentTitle(getString(R.string.service_notification_title))
            builder.setContentText("Controlling ${packageThreads.size} apps. Latency: ${avg}ms")
            
            if (ActivityCompat.checkSelfPermission(
                    applicationContext,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                NotificationManagerCompat.from(applicationContext)
                    .notify(NOTIFICATION_ID, builder.build())
            }
        }, UPDATE_INTERVAL, UPDATE_INTERVAL)

        mediaProjectionManager = applicationContext.getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        audioManager.setAllowedCapturePolicy(ALLOW_CAPTURE_BY_NONE)
        
        Log.d(TAG, "SoundMasterService created")
        instance = this
    }

    private fun handleMediaKey(keyCode: Int) {
        // Special Resume Sequence: Allow -> Play -> Deny
        val dummyListener = object : ShellExecutor.CommandResultListener {
            override fun onCommandResult(output: String, done: Boolean) {}
            override fun onCommandError(error: String) {
                Log.e(TAG, "Media Key Error: $error")
            }
        }

        if (keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY) {
            packageThreads.forEach { (_, thread) -> thread.setAppOps(true) }
            
            shellExecutor?.command("input keyevent $keyCode", dummyListener)
            
            // Re-deny after delay
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                packageThreads.forEach { (_, thread) -> thread.setAppOps(false) }
            }, 500)
        } else {
            // Standard Pass-through
            shellExecutor?.command("input keyevent $keyCode", dummyListener)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && projectionData != null) {
            val pkgs = intent.getStringArrayExtra("packages")?.toMutableList() ?: mutableListOf()
            val devices = intent.getIntArrayExtra("devices")?.toMutableList() ?: mutableListOf()
            val volumes = intent.getFloatArrayExtra("volumes")?.toMutableList() ?: mutableListOf()

            if (pkgs.isNotEmpty()) {
                running = true
                startingIntent = intent
                
                mediaProjection = mediaProjectionManager?.getMediaProjection(
                    Activity.RESULT_OK,
                    projectionData!!
                )

                // Initialize shell executor
                shellExecutor = ShellExecutor.getInstance(applicationContext)
                val audioDevices = getAudioOutputDevices()

                pkgs.forEachIndexed { index, pkg ->
                    val deviceId = devices.getOrElse(index) { -1 }
                    val volume = volumes.getOrElse(index) { 100f }
                    val device = audioDevices.find { it.id == deviceId }
                    
                    startAudioCapture(pkg, device, volume, shellExecutor!!)
                }
            }
        }
        return START_STICKY
    }

    private fun startAudioCapture(
        packageName: String,
        device: AudioDeviceInfo?,
        volume: Float,
        shellExecutor: ShellExecutor
    ) {
        if (mediaProjection == null) return
        
        val thread = PlayBackThread(
            applicationContext,
            packageName,
            mediaProjection!!,
            shellExecutor
        )
        thread.createOutput(device, device?.id ?: -1, volume)
        thread.start()
        packageThreads[packageName] = thread
        
        Log.d(TAG, "Started audio capture for $packageName -> ${device?.productName ?: "default"}")
    }

    fun getAudioOutputDevices(): List<AudioDeviceInfo> {
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { 
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
    }

    override fun onDestroy() {
        running = false
        latencyUpdateTimer.cancel()
        packageThreads.forEach { it.value.interrupt() }
        mediaProjection?.stop()
        mediaSession.release()
        super.onDestroy()
        instance = null
        Log.d(TAG, "SoundMasterService destroyed")
    }

    companion object {
        const val TAG = "SoundMasterService"
        const val NOTIFICATION_ID = 1
        const val NOTIFICATION_CHANNEL = "soundmaster_service"
        const val UPDATE_INTERVAL = 1000L

        var running = false
        var instance: SoundMasterService? = null
        var startingIntent: Intent? = null
        var projectionData: Intent? = null
    }
}
