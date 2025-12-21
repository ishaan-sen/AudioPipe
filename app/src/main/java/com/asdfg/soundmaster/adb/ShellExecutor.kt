package com.asdfg.soundmaster.adb

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "ShellExecutor"

/**
 * Shell command executor using embedded ADB client
 * Replaces ShizukuRunner functionality
 */
@RequiresApi(Build.VERSION_CODES.R)
class ShellExecutor(private val context: Context) {

    interface CommandResultListener {
        fun onCommandResult(output: String, done: Boolean) {}
        fun onCommandError(error: String) {}
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("adb_settings", Context.MODE_PRIVATE)
    }

    private val keyStore: AdbKeyStore by lazy {
        PreferenceAdbKeyStore(prefs)
    }

    private var adbKey: AdbKey = AdbKey(keyStore, "soundmaster@${Build.MODEL}")

    private var cachedPort: Int = -1
    private var cachedClient: AtomicReference<AdbClient?> = AtomicReference(null)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Import an existing private ADB key
     */
    fun importKey(keyBytes: ByteArray) {
        // Import the key into the store
        adbKey.importPrivateKey(keyBytes)
        
        // Re-initialize AdbKey instance to load the new private key from store
        adbKey = AdbKey(keyStore, "soundmaster@${Build.MODEL}")
        
        // Clear existing connection to force reconnection with the new key
        disconnect()
        
        // Mark as set up since we have a key now
        prefs.edit().putBoolean("adb_imported", true).apply()
    }

    val isSetUp: Boolean
        get() = prefs.getBoolean("adb_paired", false) || prefs.getBoolean("adb_imported", false)

    val isConnected: Boolean
        get() = cachedClient.get()?.isConnected == true

    /**
     * Discover wireless debugging port using mDNS
     */
    fun discoverPort(callback: (Int) -> Unit) {
        val mdns = AdbMdns(context, AdbMdns.TLS_CONNECT) { port ->
            if (port > 0) {
                cachedPort = port
                prefs.edit().putInt("last_port", port).apply()
                callback(port)
            }
        }
        mdns.start()
        
        // Also try last known port
        val lastPort = prefs.getInt("last_port", -1)
        if (lastPort > 0) {
            cachedPort = lastPort
            callback(lastPort)
        }
    }

    fun getCachedPort(): Int {
        if (cachedPort > 0) return cachedPort
        return prefs.getInt("last_port", -1)
    }

    /**
     * Connect to ADB daemon
     */
    suspend fun connect(port: Int = cachedPort): Boolean {
        if (port <= 0) {
            Log.e(TAG, "Invalid port: $port")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val client = AdbClient("127.0.0.1", port, adbKey)
                client.connect()
                cachedClient.set(client)
                cachedPort = port
                prefs.edit().putInt("last_port", port).apply()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                false
            }
        }
    }

    /**
     * Disconnect from ADB
     */
    fun disconnect() {
        cachedClient.getAndSet(null)?.close()
    }

    /**
     * Execute a shell command
     */
    fun command(command: String, listener: CommandResultListener) {
        scope.launch {
            try {
                var client = cachedClient.get()
                
                // Try to connect if not connected
                if (client == null || !client.isConnected) {
                    if (cachedPort <= 0) {
                        cachedPort = prefs.getInt("last_port", -1)
                    }
                    if (cachedPort > 0) {
                        val connected = connect(cachedPort)
                        if (connected) {
                            client = cachedClient.get()
                        }
                    }
                }

                if (client == null || !client.isConnected) {
                    withContext(Dispatchers.Main) {
                        listener.onCommandError("ADB not connected")
                    }
                    return@launch
                }

                val output = client.shellCommand(command, null)
                
                withContext(Dispatchers.Main) {
                    listener.onCommandResult(output, true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Command failed: $command", e)
                withContext(Dispatchers.Main) {
                    listener.onCommandError(e.message ?: "Unknown error")
                }
                // Connection may be broken, clear it
                disconnect()
            }
        }
    }

    /**
     * Execute a shell command synchronously (for use in coroutines)
     */
    suspend fun commandSync(command: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                var client = cachedClient.get()
                
                if (client == null || !client.isConnected) {
                    if (cachedPort <= 0) {
                        cachedPort = prefs.getInt("last_port", -1)
                    }
                    if (cachedPort > 0) {
                        connect(cachedPort)
                        client = cachedClient.get()
                    }
                }

                if (client == null || !client.isConnected) {
                    return@withContext Result.failure(Exception("ADB not connected"))
                }

                val output = client.shellCommand(command, null)
                Result.success(output)
            } catch (e: Exception) {
                Log.e(TAG, "Command failed: $command", e)
                disconnect()
                Result.failure(e)
            }
        }
    }

    /**
     * Mark as paired (called after successful pairing)
     */
    fun markAsPaired() {
        prefs.edit().putBoolean("adb_paired", true).apply()
    }

    /**
     * Clear pairing status
     */
    fun clearPairing() {
        prefs.edit()
            .remove("adb_paired")
            .remove("last_port")
            .apply()
        disconnect()
    }

    companion object {
        @Volatile
        private var instance: ShellExecutor? = null

        fun getInstance(context: Context): ShellExecutor {
            return instance ?: synchronized(this) {
                instance ?: ShellExecutor(context.applicationContext).also { instance = it }
            }
        }
    }
}
