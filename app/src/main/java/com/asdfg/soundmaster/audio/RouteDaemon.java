package com.asdfg.soundmaster.audio;

import android.media.AudioDeviceInfo;
import android.os.Looper;
import android.os.Process;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** APK entry point launched with app_process through authenticated local ADB, not an exported service. */
public final class RouteDaemon {
    private static String read(BufferedReader in) throws Exception {
        StringBuilder line = new StringBuilder();
        for (int c; (c = in.read()) != -1;) {
            if (c == '\n') return line.toString();
            if (line.length() >= 8192) throw new IllegalArgumentException("Control message too long");
            line.append((char)c);
        }
        throw new IllegalStateException("AudioPipe closed the control connection; removing policy");
    }

    public static void main(String[] args) throws Exception {
        if (Process.myUid() != 2000 && Process.myUid() != 0) throw new SecurityException("ADB shell required");
        Looper.prepareMainLooper();
        if (args.length != 2 || !args[1].matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid control endpoint");
        }
        int port = Integer.parseInt(args[0]);
        try (Socket control = new Socket()) {
            control.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8000);
            control.setSoTimeout(15000); // app death / missing heartbeat removes the policy
            BufferedReader in = new BufferedReader(new InputStreamReader(control.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new OutputStreamWriter(control.getOutputStream(), StandardCharsets.UTF_8), true);
            out.println(new JSONObject().put("type", "hello").put("token", args[1]).put("uid", Process.myUid()));
            JSONObject request = new JSONObject(read(in));
            if (!MessageDigest.isEqual(args[1].getBytes(StandardCharsets.UTF_8),
                    request.optString("token").getBytes(StandardCharsets.UTF_8))) return;
            AudioRoutePolicy route = null;
            try {
                int uid = request.getInt("appUid");
                if (uid < 10000) throw new IllegalArgumentException("Select an ordinary media application UID");
                int deviceId = request.getInt("deviceId");
                AudioDeviceInfo target = null, speaker = null;
                int expectedType = request.optInt("deviceType", -1);
                String address = request.optString("deviceAddress");
                String name = request.optString("deviceName");
                // A2DP codec negotiation can replace a freshly selected port between UI click
                // and helper launch. Resolve the same device identity, never a recycled ID.
                long deadline = System.nanoTime() + (expectedType > 0 ? 3_000_000_000L : 0);
                int stablePort = -1;
                long stableSince = 0;
                String selectionError = "Selected Bluetooth media output disconnected. Reconnect and select it again.";
                do {
                    AudioDeviceInfo[] current = AudioRoutePolicy.outputs();
                    speaker = null;
                    List<OutputSelection.Endpoint> candidates = new ArrayList<>();
                    for (AudioDeviceInfo d : current) {
                        if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) speaker = d;
                        if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                                || d.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET
                                || d.getType() == AudioDeviceInfo.TYPE_BLE_SPEAKER)
                            candidates.add(new OutputSelection.Endpoint(d.getId(), d.getType(),
                                    d.getAddress(), d.getProductName().toString()));
                    }
                    OutputSelection.Endpoint match = null;
                    try { match = OutputSelection.resolve(candidates, deviceId, expectedType, address, name); }
                    catch (IllegalStateException ambiguous) { selectionError = ambiguous.getMessage(); }
                    if (match != null) {
                        if (stablePort != match.id) { stablePort = match.id; stableSince = System.nanoTime(); }
                        if (expectedType < 0 || System.nanoTime() - stableSince >= 200_000_000L) {
                            for (AudioDeviceInfo d : current) if (d.getId() == match.id) target = d;
                            break;
                        }
                    } else { stablePort = -1; }
                    if (System.nanoTime() >= deadline) break;
                    Thread.sleep(100);
                } while (true);
                if (target == null) throw new IllegalStateException(selectionError);
                if (speaker == null) throw new IllegalStateException("Built-in speaker output is unavailable");
                route = new AudioRoutePolicy(uid, target, speaker);
                route.install(request.getBoolean("simultaneous"));
                // A connected idle control socket is not a lost client. Let Android suspend the
                // CPU during offloaded music without tearing down a valid policy. Process death
                // closes the socket (EOF); explicit Stop awaits cleanup. No polling wake lock.
                control.setSoTimeout(0);
                out.println(new JSONObject().put("type", "installed").put("device", target.getProductName()).put("deviceId", target.getId()));
                while (true) {
                    String command = read(in);
                    if ("stop".equals(command)) break;
                    if (!"status".equals(command)) throw new IllegalArgumentException("Unknown control command");
                    JSONObject observation = route.observe();
                    out.println(observation);
                    if (!observation.getBoolean("devicesAvailable")) break;
                }
            } catch (Throwable error) {
                Throwable cause = error;
                while (cause.getCause() != null) cause = cause.getCause();
                out.println(new JSONObject().put("type", "error").put("message",
                        cause.getClass().getSimpleName() + ": " + cause.getMessage()));
            } finally {
                try {
                    if (route != null) route.close();
                    out.println(new JSONObject().put("type", "stopped"));
                } catch (Throwable cleanup) {
                    out.println(new JSONObject().put("type", "cleanupError").put("message", cleanup.toString()));
                }
            }
        }
        // app_process has binder threads; returning main alone need not terminate the process.
        System.exit(0);
    }
}
