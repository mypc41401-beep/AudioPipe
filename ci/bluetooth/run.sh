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
./gradlew --no-daemon --stacktrace :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.asdfg.soundmaster.audio.NativePolicyIntegrationTest
python - <<'CHECK'
import pathlib
import xml.etree.ElementTree as ET
files=list(pathlib.Path('app/build/outputs/androidTest-results').rglob('TEST-*.xml'))
tests=[]
for p in files:
    tests.extend(ET.parse(p).getroot().iter('testcase'))
assert len(tests)==2, f'Expected both native policy tests, found {len(tests)}'
assert not any(t.find('skipped') is not None or t.find('failure') is not None or t.find('error') is not None for t in tests), 'A Bluetooth two-output test failed or skipped'
p=pathlib.Path('app/build/bluetooth-logs/received.sbc')
assert p.exists() and p.stat().st_size>0, 'Virtual sink received no encoded audio'
print('Both native policy tests passed with a second output; virtual A2DP sink received',p.stat().st_size,'bytes. No physical Bluetooth hardware was tested.')
CHECK
