package com.asdfg.soundmaster.adb

import io.github.muntashirakon.crypto.spake2.Spake2Context
import io.github.muntashirakon.crypto.spake2.Spake2Role
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.conscrypt.Conscrypt
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket

/** Android's TLS-exporter-bound SPAKE2 pairing protocol, not a plain ADB AUTH request. */
class AdbPairingClient(
    private val port: Int,
    private val code: String,
    private val identity: AdbIdentity,
    private val timeoutMs: Int = 15_000
) : Closeable {
    @Volatile private var socket: Socket? = null
    @Volatile private var closed = false

    fun pair() {
        require(port in 1..65535) { "Enter the pairing port (1–65535)" }
        require(code.matches(Regex("[0-9]{6}"))) { "Enter the six-digit pairing code" }
        check(!closed) { "Pairing cancelled" }
        try {
            NetworkDeadline.run(timeoutMs, ::close) {
                val plain = Socket()
                socket = plain
                if (closed) throw IOException("Pairing cancelled")
                plain.connect(InetSocketAddress("127.0.0.1", port), 4_000)
                plain.soTimeout = timeoutMs
                plain.tcpNoDelay = true
                val tls = identity.sslContext.socketFactory
                    .createSocket(plain, "127.0.0.1", port, true) as SSLSocket
                socket = tls
                if (closed) throw IOException("Pairing cancelled")
                tls.enabledProtocols = arrayOf("TLSv1.3")
                tls.soTimeout = timeoutMs
                tls.startHandshake()
                val exported = Conscrypt.exportKeyingMaterial(tls, "adb-label\u0000", null, 64)
                    ?: throw IOException("TLS exporter unavailable")
                val password = code.toByteArray(Charsets.UTF_8) + exported
                try {
                    AdbPairingProtocol.exchange(
                        DataInputStream(tls.inputStream), DataOutputStream(tls.outputStream),
                        password, identity.adbPublicKey
                    )
                } finally {
                    password.fill(0)
                    exported.fill(0)
                }
            }
        } finally {
            close()
        }
    }

    override fun close() {
        closed = true
        runCatching { socket?.close() }
    }
}

/** Wire constants follow AOSP adb/pairing_connection and adb/pairing_auth. */
internal object AdbPairingProtocol {
    const val PEER_SIZE = 8192
    private val clientName = "adb pair client\u0000".toByteArray()
    private val serverName = "adb pair server\u0000".toByteArray()
    private val keyInfo = "adb pairing_auth aes-128-gcm key".toByteArray()

    fun exchange(input: DataInputStream, output: DataOutputStream, password: ByteArray, publicKey: ByteArray) {
        require(publicKey.size in 1 until PEER_SIZE) { "Invalid ADB public key size" }
        val spake = Spake2Context(Spake2Role.Alice, clientName.clone(), serverName.clone())
        var secret: ByteArray? = null
        var shared: ByteArray? = null
        var nativeContextValid = true
        try {
            val message = try {
                spake.generateMessage(password)
            } catch (e: IllegalStateException) {
                // Upstream JNI frees its context on a native failure.
                nativeContextValid = false
                throw IOException("Could not initialize pairing authentication", e)
            }
            writePacket(output, 0, message)
            val peerMessage = readPacket(input, 0)
            if (peerMessage.size != 32) throw IOException("Invalid SPAKE2 message size")
            shared = try {
                spake.processMessage(peerMessage)
            } catch (e: IllegalStateException) {
                nativeContextValid = false
                throw IOException("Invalid pairing authentication response", e)
            }
            secret = deriveKey(requireNotNull(shared) { "SPAKE2 did not return a shared key" })
            val ours = ByteArray(PEER_SIZE)
            // PeerInfo type 0 = ADB_RSA_PUB_KEY; include the NUL-terminated public key.
            publicKey.copyInto(ours, 1)
            writePacket(output, 1, crypt(Cipher.ENCRYPT_MODE, secret, ours))
            val theirs = try {
                crypt(Cipher.DECRYPT_MODE, secret, readPacket(input, 1))
            } catch (e: Exception) {
                throw IOException("Pairing rejected: wrong or expired code. Open a new pairing-code dialog.", e)
            }
            if (theirs.size != PEER_SIZE || theirs[0].toInt() != 1) {
                throw IOException("Invalid pairing response from adbd")
            }
        } finally {
            shared?.fill(0)
            secret?.fill(0)
            if (nativeContextValid) spake.destroy()
        }
    }

    internal fun deriveKey(shared: ByteArray): ByteArray = ByteArray(16).also {
        HKDFBytesGenerator(SHA256Digest()).apply {
            init(HKDFParameters(shared, null, keyInfo))
            generateBytes(it, 0, it.size)
        }
    }

    internal fun crypt(mode: Int, secret: ByteArray, bytes: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            // Each direction sends exactly one PeerInfo packet, with counter 0 (AOSP).
            init(mode, SecretKeySpec(secret, "AES"), GCMParameterSpec(128, ByteArray(12)))
            doFinal(bytes)
        }

    internal fun writePacket(output: DataOutputStream, type: Int, payload: ByteArray) {
        output.writeByte(1)
        output.writeByte(type)
        output.writeInt(payload.size) // pairing headers use network byte order
        output.write(payload)
        output.flush()
    }

    internal fun readPacket(input: DataInputStream, expectedType: Int): ByteArray {
        val version = input.readUnsignedByte()
        val type = input.readUnsignedByte()
        val size = input.readInt()
        if (version != 1 || type != expectedType || size !in 1..(PEER_SIZE * 2)) {
            throw IOException("Invalid pairing packet; use the pairing port, not the connection port")
        }
        return ByteArray(size).also(input::readFully)
    }
}
