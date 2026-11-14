package com.asdfg.soundmaster.adb

import com.asdfg.soundmaster.adb.AdbProtocol.A_CNXN
import com.asdfg.soundmaster.adb.AdbProtocol.A_AUTH
import com.asdfg.soundmaster.adb.AdbProtocol.A_STLS
import com.asdfg.soundmaster.adb.AdbProtocol.A_STLS_VERSION
import com.asdfg.soundmaster.adb.AdbProtocol.A_VERSION
import com.asdfg.soundmaster.adb.AdbProtocol.A_MAXDATA
import com.asdfg.soundmaster.adb.AdbProtocol.A_OPEN
import com.asdfg.soundmaster.adb.AdbProtocol.A_OKAY
import com.asdfg.soundmaster.adb.AdbProtocol.A_CLSE
import com.asdfg.soundmaster.adb.AdbProtocol.A_WRTE
import com.asdfg.soundmaster.adb.AdbProtocol.ADB_AUTH_TOKEN
import com.asdfg.soundmaster.adb.AdbProtocol.ADB_AUTH_SIGNATURE
import com.asdfg.soundmaster.adb.AdbProtocol.ADB_AUTH_RSAPUBLICKEY
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

/** One authenticated connection. Calls are serialized; close() may interrupt blocked IO. */
class AdbClient(
    private val host: String,
    private val port: Int,
    private val key: AdbIdentity,
    private val timeoutMs: Int = 8_000
) : Closeable {
    @Volatile private var socket: Socket? = null
    @Volatile private var closed = false
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream
    private var nextStreamId = 1
    @Volatile var isConnected = false
        private set

    @Synchronized fun connect() {
        require(host == "127.0.0.1") { "Only this phone's local adbd is supported" }
        require(port in 1..65535) { "Enter the connection port (1–65535)" }
        try {
            NetworkDeadline.run(timeoutMs, ::close) {
                val plain = Socket()
                socket = plain
                if (closed) throw IOException("Connection cancelled")
                plain.connect(InetSocketAddress(host, port), minOf(4_000, timeoutMs))
                plain.soTimeout = timeoutMs
                plain.tcpNoDelay = true
                input = DataInputStream(plain.getInputStream())
                output = DataOutputStream(plain.getOutputStream())
                write(A_CNXN, A_VERSION, A_MAXDATA, "host::")
                var message = read()
                when (message.command) {
                    A_STLS -> {
                        write(A_STLS, A_STLS_VERSION, 0)
                        val tls = key.sslContext.socketFactory.createSocket(plain, host, port, true) as SSLSocket
                        socket = tls
                        if (closed) throw IOException("Connection cancelled")
                        tls.enabledProtocols = arrayOf("TLSv1.3")
                        tls.soTimeout = timeoutMs
                        tls.startHandshake()
                        input = DataInputStream(tls.inputStream)
                        output = DataOutputStream(tls.outputStream)
                        message = read()
                    }
                    A_AUTH -> {
                        if (message.arg0 != ADB_AUTH_TOKEN) throw IOException("Invalid ADB authentication token")
                        write(A_AUTH, ADB_AUTH_SIGNATURE, 0, key.sign(message.data))
                        message = read()
                        if (message.command == A_AUTH && message.arg0 == ADB_AUTH_TOKEN) {
                            write(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, key.adbPublicKey)
                            message = read()
                        }
                    }
                }
                if (message.command != A_CNXN) throw IOException("ADB did not authenticate. Pair this app again.")
                if (closed) throw IOException("Connection cancelled")
                isConnected = true
            }
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    @Synchronized fun shellCommand(command: String, listener: ((ByteArray) -> Unit)? = null): String {
        check(isConnected) { "ADB is disconnected" }
        require(command.toByteArray().size < A_MAXDATA - 16) { "ADB command is too long" }
        try {
            return NetworkDeadline.run(timeoutMs, ::close) {
                val localId = nextStreamId++
                write(A_OPEN, localId, 0, "shell:$command")
                val accepted = read()
                if (accepted.command != A_OKAY || accepted.arg1 != localId) {
                    throw IOException("ADB refused to open a shell. Reconnect or pair this app again.")
                }
                val remoteId = accepted.arg0
                val result = StringBuilder()
                while (true) {
                    val message = read()
                    if (message.arg0 != remoteId || message.arg1 != localId) {
                        throw IOException("Unexpected ADB stream ID")
                    }
                    when (message.command) {
                        A_WRTE -> {
                            message.data?.let {
                                listener?.invoke(it)
                                result.append(String(it, Charsets.UTF_8))
                            }
                            if (result.length > 262_144) throw IOException("ADB output exceeds the safe limit")
                            write(A_OKAY, localId, remoteId)
                        }
                        A_CLSE -> {
                            write(A_CLSE, localId, remoteId)
                            break
                        }
                        else -> throw IOException("Unexpected ADB shell packet")
                    }
                }
                result.toString().trim()
            }
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private fun write(command: Int, arg0: Int, arg1: Int, data: ByteArray? = null) {
        output.write(AdbMessage(command, arg0, arg1, data).toByteArray())
        output.flush()
    }

    private fun write(command: Int, arg0: Int, arg1: Int, data: String) {
        output.write(AdbMessage(command, arg0, arg1, data).toByteArray())
        output.flush()
    }

    private fun read(): AdbMessage {
        val header = ByteBuffer.allocate(AdbMessage.HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        input.readFully(header.array())
        val command = header.int
        val arg0 = header.int
        val arg1 = header.int
        val size = header.int
        val checksum = header.int
        val magic = header.int
        if (size !in 0..1_048_576) throw IOException("Invalid ADB packet size")
        val data = ByteArray(size).also(input::readFully)
        return AdbMessage(command, arg0, arg1, size, checksum, magic, data).also { it.validateOrThrow() }
    }

    override fun close() {
        closed = true
        isConnected = false
        runCatching { socket?.close() }
    }
}
