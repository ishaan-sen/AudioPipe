package com.asdfg.soundmaster.audio

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import com.asdfg.soundmaster.adb.ShellExecutor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Audio playback thread - captures audio from an app and plays it on specified outputs
 * Extracted and adapted from ShizuTools, using ShellExecutor instead of ShizukuRunner
 */
@RequiresApi(Build.VERSION_CODES.Q)
class PlayBackThread(
    private val context: Context,
    private val pkg: String,
    private val mediaProjection: MediaProjection,
    private val shellExecutor: ShellExecutor
) : Thread() {

    companion object {
        const val LOG_TAG = "SoundMaster"
        const val SAMPLE_RATE = 48000
        val CHANNEL = AudioFormat.CHANNEL_IN_STEREO
        val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val UPDATE_INTERVAL = 1000L
    }

    private var playback = true
    private val bufferSize = 8192 // Default buffer size
    private val dataBuffer = ByteArray(bufferSize)
    var loadedCycles = 0

    private lateinit var mCapture: AudioRecord
    var mPlayers = hashMapOf<Int, AudioPlayer>()

    override fun start() {
        setAppOps(false)
        super.start()
    }

    fun setAppOps(allow: Boolean) {
        val op = if (allow) "allow" else "deny"
        shellExecutor.command(
            "appops set $pkg PLAY_AUDIO $op",
            object : ShellExecutor.CommandResultListener {
                override fun onCommandError(error: String) {
                    Handler(context.mainLooper).post {
                        Toast.makeText(context, "AppOps Error: $error", Toast.LENGTH_SHORT).show()
                    }
                    Log.e(LOG_TAG, error)
                }
            }
        )
    }

    fun isDisconnectedFromSystem(callback: (Boolean) -> Unit) {
        shellExecutor.command("appops get $pkg PLAY_AUDIO", object : ShellExecutor.CommandResultListener {
            override fun onCommandResult(output: String, done: Boolean) {
                if (done) {
                    callback(output.contains("deny"))
                }
            }
        })
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun run() {
        if (ActivityCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            interrupt()
            return
        }
        
        try {
            val allUsages = listOf(
                AudioAttributes.USAGE_MEDIA,
                AudioAttributes.USAGE_GAME,
                AudioAttributes.USAGE_ALARM,
                AudioAttributes.USAGE_NOTIFICATION,
                AudioAttributes.USAGE_ASSISTANT,
                AudioAttributes.USAGE_UNKNOWN,
                AudioAttributes.USAGE_VOICE_COMMUNICATION
            )
            
            val configBuilder = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            // Add common usage types
            for (i in 0..3.coerceIn(0, allUsages.size - 1)) {
                configBuilder.addMatchingUsage(allUsages[i])
            }
            
            val config = configBuilder.addMatchingUid(getAppUidFromPackage(pkg))
                .build()
            val audioFormat = AudioFormat.Builder()
                .setEncoding(ENCODING)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL)
                .build()

            mCapture = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()

        } catch (e: Exception) {
            Log.e(LOG_TAG, "Initializing Audio Record failed: ${e.message} for $pkg")
            return
        }
        
        try {
            mCapture.startRecording()
            Log.i(LOG_TAG, "Audio Recording started for $pkg")
            
            while (playback) {
                mCapture.read(dataBuffer, 0, bufferSize)
                val players = mPlayers.values.toList()
                players.forEach {
                    it.write(dataBuffer, 0, dataBuffer.size)
                }
                loadedCycles++
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error in PlayBackThread", e)
        }
    }

    private fun getAppUidFromPackage(packageName: String): Int {
        return try {
            context.packageManager.getApplicationInfo(packageName, 0).uid
        } catch (e: Exception) {
            -1
        }
    }

    fun hasOutput(deviceId: Int): Boolean {
        return mPlayers.contains(deviceId)
    }

    fun createOutput(
        device: AudioDeviceInfo? = null,
        outputKey: Int = device?.id ?: -1,
        startVolume: Float,
        bal: Float? = null,
        bands: Array<Float> = arrayOf()
    ) {
        val plyr = AudioPlayer(
            AudioManager.STREAM_MUSIC,
            SAMPLE_RATE, CHANNEL,
            ENCODING, bufferSize,
            AudioTrack.MODE_STREAM
        )
        plyr.setCurrentVolume(startVolume)
        plyr.playbackRate = SAMPLE_RATE
        plyr.preferredDevice = device
        plyr.play()
        bal?.let { plyr.setBalance(bal) }
        bands.forEachIndexed { index, fl -> plyr.setBand(index, fl) }
        mPlayers[outputKey] = plyr
    }

    fun deleteOutput(outputKey: Int, interruption: Boolean = true): AudioPlayer? {
        val plyr = mPlayers.remove(outputKey)
        plyr?.stop()
        if (mPlayers.size == 0 && interruption) {
            interrupt()
        }
        return plyr
    }

    fun switchOutputDevice(currentKey: Int, newDevice: AudioDeviceInfo?): Boolean {
        if (mPlayers.contains(newDevice?.id ?: -1)) return false
        deleteOutput(currentKey, false)?.let {
            createOutput(
                newDevice,
                startVolume = it.volume * 100f,
                bal = it.getBalance(),
                bands = it.savedBands
            )
        }
        return true
    }

    fun getLatency(): Float {
        return UPDATE_INTERVAL.toFloat() / loadedCycles.coerceAtLeast(1).also { loadedCycles = 0 }
    }

    override fun interrupt() {
        playback = false
        // Restore app's PLAY_AUDIO permission
        shellExecutor.command(
            "appops set $pkg PLAY_AUDIO allow",
            object : ShellExecutor.CommandResultListener {
                override fun onCommandError(error: String) {
                    Handler(context.mainLooper).post {
                        Toast.makeText(context, "Error: $error", Toast.LENGTH_SHORT).show()
                    }
                    Log.e(LOG_TAG, error)
                }
            }
        )
        try {
            mCapture.stop()
            mCapture.release()
        } catch (_: Exception) {}
        mPlayers.values.forEach { it.stop() }
        super.interrupt()
    }

    fun getBalance(device: Int): Float? {
        return mPlayers[device]?.getBalance()
    }

    fun setBalance(device: Int, value: Float) {
        mPlayers[device]?.setBalance(value)
    }

    fun getBand(deviceId: Int, band: Int): Float? {
        return mPlayers[deviceId]?.savedBands?.get(band)
    }

    fun setBand(device: Int, band: Int, value: Float) {
        mPlayers[device]?.setBand(band, value)
    }

    fun setVolume(outputDevice: Int, vol: Float) {
        mPlayers[outputDevice]?.setCurrentVolume(vol)
    }

    fun getVolume(outputKey: Int): Float? {
        return mPlayers[outputKey]?.volume?.times(100f)
    }

    fun calculateRMS(): Float {
        val shortBuffer = ShortArray(dataBuffer.size / 2)
        ByteBuffer.wrap(dataBuffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortBuffer)
        var sum = 0.0
        for (sample in shortBuffer) {
            sum += (sample * sample).toFloat()
        }
        return sqrt(sum / shortBuffer.size).toFloat()
    }
}
