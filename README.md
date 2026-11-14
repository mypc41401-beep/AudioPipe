# AudioPipe

**Fork update — in-app Wireless Debugging pairing:** [Phone-only setup](ADB_PAIRING_BN.md). Pair AudioPipe separately; Shizuku Running alone does not authorize it. Physical Xiaomi/Bluetooth routing remains unverified.

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
| **Android Version** | 11 (API 30) or higher |
| **Wireless Debugging** | Must be enabled in Developer Options |
| **ADB authorization** | Pair AudioPipe in-app using the Wireless debugging code and pairing port |

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
4. Use Setup / Pair with the pairing code and pairing port from Android Settings. If discovery fails, enter the separate connection port from the main Wireless debugging page.

---

## 🚀 Usage

1. Launch **AudioPipe**.
2. If not connected, tap **Setup / Pair** and authorize AudioPipe with the pairing code.
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
- **Same-phone Pairing** – Keep the Settings pairing-code dialog open using split screen/floating window. Pairing and connection ports differ. Shizuku is not integrated.
- **Media Buttons** – Hardware media button forwarding is not currently functional.

---

## 📄 License

This project is provided as-is for educational purposes.
