#!/usr/bin/env bash
set -euo pipefail
logs=app/build/bluetooth-logs
mkdir -p "$logs"
speaker_pid=""
collect() {
  timeout 15s adb shell dumpsys bluetooth_manager > "$logs/bluetooth.txt" 2>&1 || true
  timeout 15s adb shell dumpsys media.audio_policy > "$logs/audio-policy.txt" 2>&1 || true
  timeout 15s adb shell dumpsys audio > "$logs/audio.txt" 2>&1 || true
  timeout 15s adb logcat -d > "$logs/logcat.txt" 2>&1 || true
  timeout 15s adb exec-out screencap -p > "$logs/screen.png" 2>/dev/null || true
  if [ -n "$speaker_pid" ]; then kill "$speaker_pid" 2>/dev/null || true; fi
}
trap collect EXIT
adb shell settings put system multi_audio_focus_enabled 1
sleep 2
adb shell sync
adb reboot
timeout 120s adb wait-for-device
for i in $(seq 1 90); do
  if [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; then break; fi
  sleep 1
done
adb shell dumpsys audio > "$logs/audio-before-tests.txt"
grep -q 'Multi Audio Focus enabled.*true' "$logs/audio-before-tests.txt"
PYTHONUNBUFFERED=1 bumble-speaker --codec sbc --device-config ci/bluetooth/speaker.json --output "$logs/received.sbc" android-netsim:name=audiopipe_ci > "$logs/speaker.txt" 2>&1 &
speaker_pid=$!
sleep 3
kill -0 "$speaker_pid"
python ci/bluetooth/pair_speaker.py
# Keep optional companion fixtures out of the shipped APK and main test suite.
cp ci/bluetooth/TwoUidBluetoothTest.kt app/src/androidTest/java/com/asdfg/soundmaster/audio/
./gradlew --no-daemon --stacktrace :app:assembleDebug :app:assembleDebugAndroidTest
peer=app/build/ci-peer
mkdir -p "$peer/classes" "$peer/dex"
tools_dir="$ANDROID_HOME/build-tools/34.0.0"
android_jar="$ANDROID_HOME/platforms/android-34/android.jar"
javac -source 8 -target 8 -cp "$android_jar" -d "$peer/classes" ci/bluetooth/peer/PeerPlayerActivity.java
"$tools_dir/d8" --lib "$android_jar" --output "$peer/dex" "$peer/classes/dev/audiopipe/ci/peer/"*.class
"$tools_dir/aapt" package -f -M ci/bluetooth/peer/AndroidManifest.xml -I "$android_jar" -F "$peer/unsigned.apk"
cp "$peer/dex/classes.dex" "$peer/classes.dex"
(cd "$peer" && "$tools_dir/aapt" add unsigned.apk classes.dex)
"$tools_dir/zipalign" -p -f 4 "$peer/unsigned.apk" "$peer/aligned.apk"
# The test companion needs no signature relationship to the delivered APK. Use its own
# disposable CI key instead of assuming Gradle stores its key in the host HOME.
keytool -genkeypair -noprompt -keystore "$peer/peer.keystore" -alias peer -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 2 -dname 'CN=AudioPipe CI fixture'
"$tools_dir/apksigner" sign --ks "$peer/peer.keystore" --ks-pass pass:android --key-pass pass:android --out "$peer/peer.apk" "$peer/aligned.apk"
adb install --no-streaming "$peer/peer.apk"
./gradlew --no-daemon --stacktrace :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.asdfg.soundmaster.audio.NativePolicyIntegrationTest,com.asdfg.soundmaster.audio.TwoUidBluetoothTest
python - <<'CHECK'
import pathlib
import xml.etree.ElementTree as ET
files=list(pathlib.Path('app/build/outputs/androidTest-results').rglob('TEST-*.xml'))
tests=[]
for p in files:
    tests.extend(ET.parse(p).getroot().iter('testcase'))
assert len(tests)==3, f'Expected native policy and two-UID service tests, found {len(tests)}'
assert not any(t.find('skipped') is not None or t.find('failure') is not None or t.find('error') is not None for t in tests), 'A Bluetooth two-output test failed or skipped'
p=pathlib.Path('app/build/bluetooth-logs/received.sbc')
assert p.exists() and p.stat().st_size>0, 'Virtual sink received no encoded audio'
print('Native policy and two-UID production service tests passed with a second output; virtual A2DP sink received',p.stat().st_size,'bytes. No physical Bluetooth hardware was tested.')
CHECK
