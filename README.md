# AudioPipe

Experimental Android 14 **original-player UID audio routing** using an authorized local ADB shell.
This fork preserves its working in-app Wireless Debugging pairing. It does not integrate Shizuku.

**Hardware status:** Redmi 11 Prime / X-906 Bluetooth acoustic isolation has not been verified by
the developer. An accepted policy, APK build, or preferred-device request is not proof of split audio.

[Verified APK, CI results and complete Bengali phone guide](AUDIOPIPE_VERIFICATION_BN.md)

[বাংলা audit and platform restrictions](AUDIO_ROUTING_AUDIT_BN.md)

[In-app ADB pairing guide](ADB_PAIRING_BN.md)

## Current implementation

- Selected music app's MEDIA/GAME/UNKNOWN UID tracks request a native RENDER policy to Bluetooth.
- Other UIDs' media/game tracks request a speaker policy. No capture/replay is used by the service.
- Requires ADB shell's privileged `MODIFY_AUDIO_ROUTING` capability. An ordinary APK cannot do this
  with normal runtime permissions. Firmware/HAL and explicit player output choices can prevent it.
- Native current-output/client data is matched to original-player UIDs through the shell. Until both apps are observed,
  status says **Policy accepted; split not yet observed**. Persistent wrong/unknown routes stop the policy.
- Foreground service owns the state and control connection. Activity recreation does not reconstruct
  status from empty spinner selections. Stop/process death returns control to Android's default routing.
- Wireless ADB retains the encrypted RSA identity and discovers changed connection ports through
  Wi-Fi NSD. Recovery is bounded and owned by the visible UI/existing routing service. Android can
  turn Wireless debugging off after Wi-Fi loss; the user must enable that setting again.
- Steady-state native observations run about every 15 seconds, with bounded startup/mismatch checks
  and coalesced device-change events. The UI shows the last observation time. This reduces observer
  overhead; it does not establish that a physical phone's audio stutter has been resolved.
- **Repair AudioPipe policy** checks ADB before removing the owned policy, awaits cleanup, then
  recreates it once. A failed preflight leaves the old policy intact. Repeated repairs have a 30-second
  cooldown. Normal Stop keeps foreground ownership through cleanup. It never restarts other apps,
  changes their buffers, or restarts audioserver. See [Bengali stability and phone test guide](AUDIO_STABILITY_BN.md).
- Local diagnostic export includes output IDs, observations, lifecycle/errors, and CPU/heap snapshots;
  it omits ADB keys, pairing codes, control tokens, MACs and the installed-app list. No automatic upload.
- Simultaneous media requires Android multi-audio-focus. If Start asks for it, use **Enable simultaneous
  media**, reboot once, re-enable Wireless debugging and reconnect. This is a persistent system option;
  **Restore normal audio focus** plus reboot undoes it. Stop only removes the routing policy.
  The live API is deliberately avoided: API-34 shell calls reproduce a system settings identity error.
  Calls, transient focus and applications that pause themselves remain limitations.

## Install and test

Download **AudioPipe-debug** from a successful GitHub Actions run, extract its ZIP and install
`app-debug.apk`. Pair AudioPipe itself, connect Bluetooth Media audio, select BitChord and X-906,
enable simultaneous media and reboot if requested, then Start **before** playing the two apps. Follow the Bengali guide's listening test and export logs.
A new CI debug signing key may require uninstalling the previous test APK and pairing again.

Legacy file paths are retained; the unsafe capture worker is disabled. `AudioPlayer` is unused. Its volume/balance/EQ
controls are not presented as controls for original-player routing. The current path needs neither
RECORD_AUDIO permission nor a MediaProjection consent. Use the original player/system volume controls.

## Build and verification

Java 17, Gradle wrapper 8.9, AGP 8.2, SDK 34:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

GitHub Actions builds/signs/verifies the debug APK, runs JVM tests and lint, installs and tests on a
secure API-34 emulator, and records launch/memory/crash logs. Native policy registration and original-player observation use the emulator speaker. A
separate route-change test skips explicitly if the HAL has no second endpoint. They do **not** simulate or verify Xiaomi Bluetooth hardware isolation.
The separate virtual-Bluetooth workflow pairs a pinned Google Bumble A2DP sink with Netsim,
then verifies two ordinary original-player UIDs through the production service, real Start/Stop UI,
background/recreation, repeated Start/Stop, two minutes of playback, owned-policy Repair and Bluetooth
reconnect. Its assertions check sparse observations, native UID/output evidence and bounded helper
descriptors. This remains software Bluetooth testing, not an X-906 or Xiaomi acoustic test. The
latest APK, precise pass/skip counts and result links are in the verification report above.
No public release is published by the workflows.

See [third-party notices](THIRD_PARTY_NOTICES.md). Earlier build reviews are historical; the current
routing audit above supersedes their capture/replay behavior descriptions.
