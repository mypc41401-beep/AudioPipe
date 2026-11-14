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
                for (AudioDeviceInfo d : AudioRoutePolicy.outputs()) {
                    if (d.getId() == deviceId && (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                            || d.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET
                            || d.getType() == AudioDeviceInfo.TYPE_BLE_SPEAKER)) target = d;
                    if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) speaker = d;
                }
                if (target == null) throw new IllegalStateException("Selected Bluetooth media output disconnected. Reconnect and select it again.");
                if (speaker == null) throw new IllegalStateException("Built-in speaker output is unavailable");
                route = new AudioRoutePolicy(uid, target, speaker);
                route.install(request.getBoolean("simultaneous"));
                // A connected idle control socket is not a lost client. Let Android suspend the
                // CPU during offloaded music without tearing down a valid policy. Process death
                // closes the socket (EOF); explicit Stop awaits cleanup. No polling wake lock.
                control.setSoTimeout(0);
                out.println(new JSONObject().put("type", "installed").put("device", target.getProductName()));
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
