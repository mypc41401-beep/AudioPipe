package com.asdfg.soundmaster.audio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses CURRENT native output devices and active clients, never Preferred Device fields.
 * Format: AOSP Android 14 AudioOutputDescriptor/ClientDescriptor/AudioDeviceTypeAddr dump().
 * Raw dumps can contain MACs; they remain in helper memory and are never exported or logged.
 */
public final class NativeRoutes {
    public static boolean multiFocusEnabled(String dump) {
        Matcher flag = Pattern.compile("Multi Audio Focus enabled\\s*:\\s*(true|false)").matcher(dump);
        if (!flag.find()) throw new IllegalStateException("This ROM does not expose Android multi-audio-focus state; simultaneous playback cannot be confirmed");
        return Boolean.parseBoolean(flag.group(1));
    }
    public static final class Device {
        public final int id;
        public final String type, address;
        public Device(int portId, String nativeType, String addr) { id = portId; type = nativeType; address = addr; }
    }
    private static final Pattern CLIENT = Pattern.compile("Port ID: (\\d+); Session ID: (\\d+); uid (\\d+); State: (Active|Inactive)");
    private static final Pattern DEVICE = Pattern.compile("(AUDIO_DEVICE_OUT_[A-Z0-9_]+), @:([^;}]*)(?:[;}]|$)");

    public static JSONArray parse(String dump, List<Device> devices, int selectedUid, int targetId, int speakerId) throws Exception {
        boolean outputs = false, recognized = false, trackClients = false;
        String currentDevices = "";
        int uid = -1, port = -1, session = -1;
        boolean active = false;
        Map<Integer, JSONObject> clients = new LinkedHashMap<>();
        for (String line : dump.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.matches("Outputs \\(\\d+\\):")) { outputs = true; recognized = true; currentDevices = ""; trackClients = false; continue; }
            if (trimmed.matches("Inputs.*:")) { outputs = false; trackClients = false; continue; }
            if (!outputs) continue;
            if (line.contains("Port ID:") && line.contains("I/O handle:")) {
                currentDevices = ""; active = false; trackClients = false; continue;
            }
            if (trimmed.startsWith("Devices:")) { currentDevices = trimmed.substring(8).trim(); continue; }
            if (trimmed.startsWith("- AudioTrack clients (") || trimmed.startsWith("- AudioTrack active (stream) clients (")) {
                trackClients = true; continue;
            }
            if (!trackClients) continue;
            Matcher client = CLIENT.matcher(line);
            if (client.find()) {
                port = Integer.parseInt(client.group(1)); session = Integer.parseInt(client.group(2));
                uid = Integer.parseInt(client.group(3)); active = "Active".equals(client.group(4)); continue;
            }
            if (!active || !trimmed.startsWith("Attributes:")) continue;
            active = false;
            boolean knownUsage = trimmed.contains("AUDIO_USAGE_");
            if (knownUsage && !Pattern.compile("\\bAUDIO_USAGE_(MEDIA|GAME|UNKNOWN)\\b").matcher(trimmed).find()) continue;
            Set<Integer> actual = new LinkedHashSet<>();
            boolean unknown = !knownUsage;
            Matcher nativeDevice = DEVICE.matcher(currentDevices);
            int found = 0;
            while (nativeDevice.find()) {
                found++;
                List<Device> matches = new ArrayList<>();
                for (Device d : devices) if (d.type.equals(nativeDevice.group(1))
                        && d.address.equalsIgnoreCase(nativeDevice.group(2).trim())) matches.add(d);
                if (matches.size() == 1) actual.add(matches.get(0).id); else unknown = true;
            }
            if (found == 0) unknown = true;
            JSONObject previous = clients.get(port);
            if (previous != null) {
                JSONArray old = previous.getJSONArray("actualDevices");
                for (int i = 0; i < old.length(); i++) actual.add(old.getInt(i));
                unknown |= previous.optBoolean("unknown");
            }
            JSONArray ids = new JSONArray();
            for (int id : actual) ids.put(id);
            clients.put(port, new JSONObject().put("uid", uid).put("session", session).put("port", port)
                    .put("expected", uid == selectedUid ? targetId : speakerId)
                    .put("actual", !unknown && actual.size() == 1 ? actual.iterator().next() : -1)
                    .put("actualDevices", ids).put("unknown", unknown || actual.size() != 1));
        }
        if (!recognized) throw new IllegalStateException("Unsupported native audio-policy dump format; cannot verify original routes");
        JSONArray result = new JSONArray();
        for (JSONObject c : clients.values()) result.put(c);
        return result;
    }
}
