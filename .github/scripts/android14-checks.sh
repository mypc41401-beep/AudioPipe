#!/usr/bin/env bash
set -euo pipefail

logs=app/build/runtime-logs
mkdir -p "$logs"
collect_logs() {
  timeout 15s adb shell dumpsys audio > "$logs/audio.txt" 2>&1 || true
  timeout 15s adb shell dumpsys meminfo com.asdfg.soundmaster > "$logs/memory.txt" 2>&1 || true
  timeout 15s adb shell dumpsys activity activities > "$logs/activities.txt" 2>&1 || true
  timeout 15s adb logcat -b crash -d > "$logs/crashes.txt" 2>&1 || true
}
trap collect_logs EXIT

# The live AudioService setter has an API-34 calling-identity bug for shell callers.
# Test the same persistent, rootless setting + reboot path offered by the app instead.
adb shell settings put system multi_audio_focus_enabled 1
adb reboot
timeout 120s adb wait-for-device
for i in $(seq 1 90); do
  if [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; then break; fi
  sleep 1
done
[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]
adb shell dumpsys audio > "$logs/audio-before-tests.txt"
grep -q 'Multi Audio Focus enabled.*true' "$logs/audio-before-tests.txt"

./gradlew --no-daemon --stacktrace :app:connectedDebugAndroidTest

# The emulator job compiles its own instrumentation APK. Also install the exact signed
# artifact from the build job, so delivery verification is not limited to another APK.
expected=$(cut -d ' ' -f 1 delivered-apk/app-debug.apk.sha256)
actual=$(sha256sum delivered-apk/app-debug.apk | cut -d ' ' -f 1)
[ "$expected" = "$actual" ]
adb uninstall com.asdfg.soundmaster.test
adb uninstall com.asdfg.soundmaster
adb install --no-streaming delivered-apk/app-debug.apk
adb shell pm grant com.asdfg.soundmaster android.permission.POST_NOTIFICATIONS
adb shell am start -W -n com.asdfg.soundmaster/.MainActivity | tee "$logs/delivered-apk-launch.txt"
grep -q 'Status: ok' "$logs/delivered-apk-launch.txt"
sleep 2
adb logcat -b crash -d > "$logs/crashes.txt"
if grep -q 'Process: com.asdfg.soundmaster,' "$logs/crashes.txt"; then
  cat "$logs/crashes.txt"
  exit 1
fi
