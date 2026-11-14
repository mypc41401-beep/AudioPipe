package com.asdfg.soundmaster.adb

import javax.net.ssl.SSLContext

/** A single persisted identity must be used for both pairing and connecting. */
interface AdbIdentity {
    val adbPublicKey: ByteArray
    val sslContext: SSLContext
    fun sign(data: ByteArray?): ByteArray
}
