#!/usr/bin/env python3
"""Pair a CI-only Bumble A2DP sink through Android Settings, with bounded waits."""
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

OUT = pathlib.Path("app/build/bluetooth-logs")
OUT.mkdir(parents=True, exist_ok=True)
NAME = "AudioPipe CI Speaker"

def adb(*args):
    return subprocess.check_output(["adb", *args], text=True, timeout=20)

def tap(node):
    values = [int(x) for x in re.findall(r"\d+", node.get("bounds", ""))]
    if len(values) == 4:
        adb("shell", "input", "tap", str((values[0]+values[2])//2), str((values[1]+values[3])//2))
        return True
    return False

adb("shell", "svc", "bluetooth", "enable")
adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
adb("shell", "wm", "dismiss-keyguard")
adb("shell", "am", "start", "-a", "android.settings.BLUETOOTH_SETTINGS")
last = None
last_tap = 0
end = time.monotonic() + 110
while time.monotonic() < end:
    try:
        radio = adb("shell", "dumpsys", "bluetooth_manager")
        a2dp = radio.partition("Profile: A2dpService")[2].partition("\nProfile:")[0]
        if NAME in radio and "mConnectionState: CONNECTED" in a2dp:
            (OUT/"paired-bluetooth.txt").write_text(radio)
            print("Android A2dpService reports the virtual speaker CONNECTED", flush=True)
            break
        adb("shell", "uiautomator", "dump", "/data/local/tmp/bluetooth-window.xml")
        xml = adb("exec-out", "cat", "/data/local/tmp/bluetooth-window.xml")
        (OUT/"pairing-ui.xml").write_text(xml)
        nodes = list(ET.fromstring(xml).iter("node"))
        labels = [n.get("text", "") for n in nodes if n.get("text")]
        if labels != last:
            print("Bluetooth Settings:", labels, flush=True)
            last = labels
        if NAME in labels and any(x in labels for x in ["Connected", "Active", "Connected for media audio"]):
            print("Settings reports the virtual speaker connected", flush=True)
            break
        action = next((n for n in nodes if n.get("resource-id") == "android:id/button1" and n.get("text", "").lower() in ["pair", "ok"]), None)
        if action is None:
            action = next((n for n in nodes if n.get("text") == NAME), None)
        if action is None:
            action = next((n for n in nodes if n.get("text", "").lower() == "pair new device"), None)
        if action is not None and time.monotonic() - last_tap > 3:
            tap(action)
            last_tap = time.monotonic()
        time.sleep(1)
    except (subprocess.SubprocessError, ET.ParseError) as exc:
        print(type(exc).__name__, str(exc)[:200], flush=True)
        time.sleep(1)
else:
    raise SystemExit("Virtual Bluetooth pairing did not reach Connected within 110s; see UI/radio logs")
time.sleep(4)  # allow Android to expose the A2DP audio port
