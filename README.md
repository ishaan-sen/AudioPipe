# AudioPipe (SoundMaster)

A powerful Android audio routing utility that **captures audio from individual apps** and routes it to **specific output devices** (like Bluetooth headphones or the speaker), effectively bypassing the system's default audio routing.

> **Use Case:** Listen to Spotify through your AirPods while Android Auto plays navigation through the car speakers.

---

## ✨ Features

- **Per-App Audio Capture** – Select any installed app and capture its audio output.
- **Custom Output Routing** – Route captured audio to Bluetooth, wired headphones, USB headsets, or the built-in speaker.
- **Live Volume & Balance Control** – Adjust volume and stereo balance on the fly.
- **Latency Monitoring** – Real-time latency display in the notification.
- **Wireless ADB Integration** – Uses self-to-self ADB over wireless debugging to control `appops` permissions dynamically.

---

## 🛠 Requirements

| Requirement | Details |
|-------------|---------|
| **Android Version** | 10 (Q) or higher |
| **Wireless Debugging** | Must be enabled in Developer Options |
| **ADB Key** | Import an existing `adbkey` or pair via Shizuku first |

---

## 📦 Installation

1. **Build from source:**
   ```bash
   ./gradlew assembleDebug
   ```
2. Install the APK on your device:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
3. Enable **Wireless Debugging** in Developer Options.
4. Import your ADB private key (usually `~/.android/adbkey`) or pair via Shizuku.

---

## 🚀 Usage

1. Launch **AudioPipe**.
2. If not connected, tap **Setup** and import your ADB key.
3. Select the **app** you want to capture (e.g., Spotify).
4. Select the **output device** (e.g., Bluetooth headphones).
5. Adjust **volume** and **balance** as desired.
6. Tap **Start** to begin audio routing.

The app will:
- Deny the selected app's `PLAY_AUDIO` permission (so it doesn't play to the system).
- Capture its audio via `MediaProjection`.
- Play the captured audio to your chosen output device.

---

## 🏗 Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        MainActivity                         │
│  - App selection, output selection, volume/balance controls │
└─────────────────────────────┬───────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                     SoundMasterService                      │
│  - Foreground service with MediaProjection                  │
│  - Manages PlayBackThread instances per app                 │
└─────────────────────────────┬───────────────────────────────┘
                              │
          ┌───────────────────┼───────────────────┐
          ▼                   ▼                   ▼
┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐
│  PlayBackThread │ │  PlayBackThread │ │  PlayBackThread │
│  (per app)      │ │  (per app)      │ │  (per app)      │
│  - AudioRecord  │ │  - AudioRecord  │ │  - AudioRecord  │
│  - AudioPlayer  │ │  - AudioPlayer  │ │  - AudioPlayer  │
└─────────────────┘ └─────────────────┘ └─────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────┐
│                       ShellExecutor                         │
│  - ADB connection via AdbClient                             │
│  - Runs shell commands (appops set, etc.)                   │
└─────────────────────────────────────────────────────────────┘
```

---

## 📁 Project Structure

```
app/src/main/java/com/asdfg/soundmaster/
├── MainActivity.kt           # Main UI and controls
├── adb/
│   ├── AdbClient.kt          # ADB protocol implementation
│   ├── AdbKey.kt             # RSA key management
│   ├── AdbMdns.kt            # mDNS discovery for wireless debugging
│   ├── AdbMessage.kt         # ADB message format
│   ├── AdbProtocol.kt        # ADB protocol constants
│   └── ShellExecutor.kt      # High-level shell command execution
└── audio/
    ├── AudioPlayer.kt        # AudioTrack wrapper with volume/balance
    ├── PlayBackThread.kt     # Audio capture and playback thread
    └── SoundMasterService.kt # Foreground service orchestrating capture
```

---

## ⚠️ Known Limitations

- **MediaProjection Required** – A screen capture permission dialog appears on each start.
- **No Pairing** – This app cannot pair with wireless debugging directly; use Shizuku or import an existing key.
- **Media Buttons** – Hardware media button forwarding is not currently functional.

---

## 📄 License

This project is provided as-is for educational purposes.
