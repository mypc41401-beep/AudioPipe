package com.asdfg.soundmaster.adb

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

/** A loopback ADB peer checks the real client's framing, AUTH, stream IDs and timeouts. */
class AdbClientTest {
    private val identity = object : AdbIdentity {
        override val adbPublicKey = "test-public-key\u0000".toByteArray()
        override val sslContext: SSLContext get() = error("TLS is covered by the Android integration test")
        override fun sign(data: ByteArray?) = byteArrayOf(9, 8, 7)
    }

    private fun read(input: DataInputStream): AdbMessage {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        input.readFully(header.array())
        val command = header.int; val arg0 = header.int; val arg1 = header.int
        val size = header.int; val crc = header.int; val magic = header.int
        return AdbMessage(command, arg0, arg1, size, crc, magic, ByteArray(size).also(input::readFully))
    }

    @Test fun `AUTH signature and two serialized commands use distinct stream IDs`() {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val peer = executor.submit {
                server.accept().use { socket ->
                    socket.soTimeout = 3_000
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    fun send(command: Int, a: Int, b: Int, bytes: ByteArray? = null) {
                        output.write(AdbMessage(command, a, b, bytes).toByteArray()); output.flush()
                    }
                    assertEquals(AdbProtocol.A_CNXN, read(input).command)
                    send(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_TOKEN, 0, ByteArray(20))
                    val signed = read(input)
                    assertEquals(AdbProtocol.ADB_AUTH_SIGNATURE, signed.arg0)
                    assertArrayEquals(byteArrayOf(9, 8, 7), signed.data)
                    send(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.A_MAXDATA)
                    var lastId = 0
                    repeat(2) {
                        val open = read(input)
                        assertEquals(AdbProtocol.A_OPEN, open.command)
                        assertTrue(open.arg0 > lastId)
                        lastId = open.arg0
                        send(AdbProtocol.A_OKAY, 7, open.arg0)
                        send(AdbProtocol.A_WRTE, 7, open.arg0, "2000\n".toByteArray())
                        assertEquals(AdbProtocol.A_OKAY, read(input).command)
                        send(AdbProtocol.A_CLSE, 7, open.arg0)
                        assertEquals(AdbProtocol.A_CLSE, read(input).command)
                    }
                }
            }
            try {
                AdbClient("127.0.0.1", server.localPort, identity).use { client ->
                    client.connect()
                    assertTrue(client.isConnected)
                    assertEquals("2000", client.shellCommand("id -u"))
                    assertEquals("2000", client.shellCommand("id -u"))
                }
                peer.get(5, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun `silent peer reaches a finite deadline and closes connection`() {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val peer = executor.submit { server.accept().use { socket ->
                val input = DataInputStream(socket.getInputStream()); read(input)
                assertEquals(-1, input.read())
            } }
            val client = AdbClient("127.0.0.1", server.localPort, identity, 200)
            val start = System.nanoTime()
            try {
                assertThrows(SocketTimeoutException::class.java) { client.connect() }
                assertFalse(client.isConnected)
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3_000)
                peer.get(3, TimeUnit.SECONDS)
            } finally { client.close(); executor.shutdownNow() }
        }
    }

    @Test fun `remote hosts cannot use the local trust policy`() {
        assertThrows(IllegalArgumentException::class.java) { AdbClient("192.0.2.1", 5555, identity).connect() }
    }

    @Test fun `pairing port input validation happens before key or network access`() {
        assertThrows(IllegalArgumentException::class.java) { AdbPairingClient(0, "123456", identity).pair() }
        assertThrows(IllegalArgumentException::class.java) { AdbPairingClient(12345, "12345", identity).pair() }
    }
}
