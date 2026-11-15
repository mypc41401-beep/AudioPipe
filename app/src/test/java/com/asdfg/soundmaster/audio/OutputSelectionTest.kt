package com.asdfg.soundmaster.audio

import org.junit.Assert.*
import org.junit.Test

class OutputSelectionTest {
    private fun endpoint(id: Int,address: String="12:34:56:78:9A:BC",name: String="X-906") =
        OutputSelection.Endpoint(id,8,address,name)
    @Test fun codecChangeResolvesSameAddressAtNewPort() {
        val result=OutputSelection.resolve(listOf(endpoint(43)),38,8,"12:34:56:78:9a:bc","Old display name")
        assertEquals(43,result!!.id)
    }
    @Test fun recycledPortCannotSelectAnotherBluetoothDevice() {
        val connected=listOf(endpoint(38,"AA:BB:CC:DD:EE:FF"),endpoint(43))
        assertEquals(43,OutputSelection.resolve(connected,38,8,"12:34:56:78:9A:BC","X-906")!!.id)
    }
    @Test fun maskedAddressRequiresUniqueTypeAndName() {
        assertEquals(43,OutputSelection.resolve(listOf(endpoint(43)),38,8,"02:00:00:00:00:00","X-906")!!.id)
        assertThrows(IllegalStateException::class.java) {
            OutputSelection.resolve(listOf(endpoint(43),endpoint(44,"AA:BB:CC:DD:EE:FF")),38,8,"02:00:00:00:00:00","X-906")
        }
    }
    @Test fun missingIdentityUsesExactCurrentIdOnly() {
        assertNull(OutputSelection.resolve(listOf(endpoint(43)),38,-1,"",""))
        assertEquals(43,OutputSelection.resolve(listOf(endpoint(43)),43,-1,"","")!!.id)
    }
    @Test fun duplicateStrongIdentityIsAnErrorRatherThanAnArbitraryPort() {
        assertThrows(IllegalStateException::class.java) {
            OutputSelection.resolve(listOf(endpoint(43),endpoint(44)),38,8,"12:34:56:78:9A:BC","X-906")
        }
        assertNull(OutputSelection.resolve(listOf(endpoint(43)),43,26,"12:34:56:78:9A:BC","X-906"))
    }
}
