package com.asdfg.soundmaster.adb

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

class AdbPairingProtocolTest {
    @Test fun `invalid pairing header is rejected before allocation`() {
        for (header in listOf(byteArrayOf(1, 0, 127, -1, -1, -1), byteArrayOf(2, 0, 0, 0, 0, 32))) {
            assertThrows(IOException::class.java) {
                AdbPairingProtocol.readPacket(DataInputStream(header.inputStream()), 0)
            }
        }
    }
}
