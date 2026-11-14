package com.asdfg.soundmaster.audio

import android.os.Looper
import android.os.Process
import java.net.Socket
import java.io.PrintWriter

/** Test APK only. Uses the production policy backend as the real shell UID, on emulator outputs. */
object NativePolicyFixture {
    @JvmStatic fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        if (args[0] == "wired") {
            val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "audio")
            val service = Class.forName("android.media.IAudioService\$Stub")
                .getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
            val attributes = Class.forName("android.media.AudioDeviceAttributes")
            val endpoint = attributes.getConstructor(Int::class.javaPrimitiveType, String::class.java, String::class.java)
                .newInstance(android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "", "CI virtual wired endpoint")
            Class.forName("android.media.IAudioService").getMethod("setWiredDeviceConnectionState", attributes, Int::class.javaPrimitiveType, String::class.java)
                .invoke(service, endpoint, args[1].toInt(), "com.android.shell")
            println("WIRED_REQUESTED")
            System.exit(0)
        }
        Socket("127.0.0.1", args[0].toInt()).use { socket ->
            socket.soTimeout = 20000
            val input = socket.getInputStream().bufferedReader()
            val output = PrintWriter(socket.getOutputStream(), true)
            var policy: AudioRoutePolicy? = null
            try {
                val devices = AudioRoutePolicy.outputs()
                val selected = devices.first { it.id == args[3].toInt() }
                val speaker = devices.first { it.id == args[4].toInt() }
                policy = AudioRoutePolicy(args[2].toInt(), selected, speaker)
                policy.install(true, args[5] == "1")
                output.println("READY ${args[1]} ${Process.myUid()}")
                while (input.readLine() == "status") output.println(policy.observe().toString())
            } catch (e: Throwable) {
                var cause = e
                while (cause.cause != null) cause = cause.cause!!
                output.println("ERROR ${cause.javaClass.simpleName}: ${cause.message}")
            } finally {
                try { policy?.close(); output.println("STOPPED") }
                catch (e: Throwable) { output.println("CLEANUP_ERROR $e") }
            }
        }
        System.exit(0)
    }
}
