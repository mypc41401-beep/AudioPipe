package com.asdfg.soundmaster.adb

import io.github.muntashirakon.crypto.spake2.Spake2Context
import io.github.muntashirakon.crypto.spake2.Spake2Role
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher

/** Real SPAKE2 + HKDF + AES-GCM exchanges; the TLS exporter is supplied separately by Conscrypt. */
@RunWith(AndroidJUnit4::class)
class AdbPairingProtocolTest {
    private val password = "123456".toByteArray() + ByteArray(64) { it.toByte() }

    private fun exchange(serverPassword: ByteArray, tamper: Boolean = false, peerType: Int = 1) {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val peer = executor.submit {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    val bob = Spake2Context(Spake2Role.Bob,
                        "adb pair server\u0000".toByteArray(), "adb pair client\u0000".toByteArray())
                    try {
                        val theirs = AdbPairingProtocol.readPacket(input, 0)
                        AdbPairingProtocol.writePacket(output, 0, bob.generateMessage(serverPassword))
                        val shared = bob.processMessage(theirs)
                        val secret = AdbPairingProtocol.deriveKey(shared)
                        try {
                            val encryptedClient = AdbPairingProtocol.readPacket(input, 1)
                            if (serverPassword.contentEquals(password)) {
                                val client = AdbPairingProtocol.crypt(Cipher.DECRYPT_MODE, secret, encryptedClient)
                                assertEquals(0, client[0].toInt())
                                assertEquals(AdbPairingProtocol.PEER_SIZE, client.size)
                            }
                            val response = ByteArray(AdbPairingProtocol.PEER_SIZE)
                            response[0] = peerType.toByte()
                            val encrypted = AdbPairingProtocol.crypt(Cipher.ENCRYPT_MODE, secret, response)
                            if (tamper) encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
                            AdbPairingProtocol.writePacket(output, 1, encrypted)
                        } finally { shared.fill(0); secret.fill(0) }
                    } finally { bob.destroy() }
                }
            }
            var clientError: Throwable? = null
            try {
                Socket("127.0.0.1", server.localPort).use { socket ->
                    socket.soTimeout = 5_000
                    AdbPairingProtocol.exchange(DataInputStream(socket.getInputStream()),
                        DataOutputStream(socket.getOutputStream()), password, "public-key\u0000".toByteArray())
                }
            } catch (e: Throwable) {
                clientError = e
                throw e
            } finally {
                try {
                    peer.get(8, TimeUnit.SECONDS)
                } catch (e: Throwable) {
                    if (clientError != null) clientError.addSuppressed(e) else throw e
                } finally { executor.shutdownNow() }
            }
        }
    }

    @Test fun `correct code and TLS binding complete the encrypted peer exchange`() = exchange(password)

    @Test fun `wrong pairing code cannot authenticate`() {
        assertThrows(IOException::class.java) { exchange("654321".toByteArray() + ByteArray(64)) }
    }

    @Test fun `tampered encrypted response cannot authenticate`() {
        assertThrows(IOException::class.java) { exchange(password, tamper = true) }
    }

    @Test fun `wrong peer type cannot mark pairing successful`() {
        assertThrows(IOException::class.java) { exchange(password, peerType = 0) }
    }

    @Test fun `oversized packet is rejected before allocation`() {
        val bytes = java.io.ByteArrayOutputStream()
        DataOutputStream(bytes).apply { writeByte(1); writeByte(0); writeInt(Int.MAX_VALUE) }
        assertThrows(IOException::class.java) {
            AdbPairingProtocol.readPacket(DataInputStream(bytes.toByteArray().inputStream()), 0)
        }
    }
}
