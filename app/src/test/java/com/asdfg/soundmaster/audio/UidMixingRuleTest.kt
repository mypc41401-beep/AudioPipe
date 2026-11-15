package com.asdfg.soundmaster.audio

import android.media.AudioAttributes
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise the actual API-34 framework builder contract, even on a one-output CI HAL. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UidMixingRuleTest {
    @Test fun selectedUidRuleMatchesMediaGameAndUnknown() = verify(false)
    @Test fun otherUidRuleExcludesSelectedWithoutPassingAnInvalidMaskedApiRule() = verify(true)

    private fun verify(excluding: Boolean) {
        val rule = AudioRoutePolicy.mixingRule(10123, excluding)
        val criteria = rule.javaClass.getMethod("getCriteria").invoke(rule) as List<*>
        assertEquals(4,criteria.size)
        fun value(c: Any, method: String) = c.javaClass.getMethod(method).invoke(c)
        val uid = criteria.filterNotNull().single { ((value(it,"getRule") as Int) and 0x7fff) == 4 }
        assertEquals(if(excluding) 0x8004 else 4,value(uid,"getRule"))
        assertEquals(10123,value(uid,"getIntProp"))
        val usages=criteria.filterNotNull().filter { value(it,"getRule") == 1 }
            .map { (value(it,"getAudioAttributes") as AudioAttributes).usage }.toSet()
        assertEquals(setOf(AudioAttributes.USAGE_MEDIA,AudioAttributes.USAGE_GAME,AudioAttributes.USAGE_UNKNOWN),usages)
    }
}
