package com.asdfg.soundmaster.audio

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RouteEvidenceTest {
    private fun evidence(players: String) = RouteEvidence.from(JSONObject("""
        {"players":[$players],"selectedDevice":20,"speakerDevice":2}
    """), 10123)

    @Test fun `accepted policy without simultaneous original players is not a split observation`() {
        assertFalse(evidence("").bothObserved)
        assertFalse(evidence("""{"uid":10123,"expected":20,"actual":20}""").bothObserved)
        assertFalse(evidence("""{"uid":10124,"expected":2,"actual":2}""").bothObserved)
    }

    @Test fun `one incorrectly routed third party player invalidates the whole observation`() {
        val e = evidence("""{"uid":10123,"expected":20,"actual":20},
            {"uid":10124,"expected":2,"actual":2},
            {"uid":10125,"expected":2,"actual":20}""")
        assertEquals(1, e.wrong)
        assertFalse(e.bothObserved)
    }

    @Test fun `unknown routes never count as successful output`() {
        val e = evidence("""{"uid":10123,"expected":20,"actual":-1},
            {"uid":10124,"expected":2,"actual":2}""")
        assertEquals(1, e.unknown)
        assertFalse(e.bothObserved)
    }

    @Test fun `both streams count only after all observed native devices match`() {
        val e = evidence("""{"uid":10123,"expected":20,"actual":20},
            {"uid":10124,"expected":2,"actual":2},
            {"uid":10125,"expected":2,"actual":2}""")
        assertTrue(e.bothObserved)
        assertEquals(2, e.others)
    }

    @Test fun `daemon launch shell quotes APK paths and rejects injected control parameters`() {
        assertEquals("'a'\\''b'", RouteSession.quote("a'b"))
        val command = RouteSession.launchCommand("/data/app/a'b/base.apk", 23456, "a".repeat(64))
        assertTrue(command.contains("CLASSPATH='/data/app/a'\\''b/base.apk'"))
        assertThrows(IllegalArgumentException::class.java) {
            RouteSession.launchCommand("/a.apk", 23456, "x; id")
        }
    }
}
