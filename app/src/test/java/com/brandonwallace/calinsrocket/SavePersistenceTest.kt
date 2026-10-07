package com.brandonwallace.calinsrocket

import com.brandonwallace.calinsrocket.game.Achievement
import com.brandonwallace.calinsrocket.game.GameEngine
import com.brandonwallace.calinsrocket.game.Phase
import com.brandonwallace.calinsrocket.game.SaveConfig
import com.brandonwallace.calinsrocket.game.SaveData
import com.brandonwallace.calinsrocket.game.SaveStore
import com.brandonwallace.calinsrocket.game.UpgradeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Proves Calins Rocket progress is written on every change and survives an app restart. */
class SavePersistenceTest {
    /** Simulates SharedPreferences: survives across GameEngine instances (= app restarts). */
    private class DiskStore : SaveStore {
        var data: String? = null; var writes = 0; var flushes = 0
        override fun load() = data
        override fun save(data: String) { this.data = data; writes++ }
        override fun flush(data: String) { this.data = data; flushes++ }
    }

    private fun flyOnce(e: GameEngine) {
        e.openHangar(); e.launch()
        var n = 0
        while (e.phase != Phase.RESULTS && n < 200_000) { e.inputThrottle = e.phase == Phase.FLIGHT; e.update(1 / 60f); n++ }
        assertEquals(Phase.RESULTS, e.phase)
    }

    @Test fun saveFileNameIsUniqueToCalinsRocket() {
        assertEquals("calins_rocket", SaveConfig.PREFS_FILE)
        assertNotEquals("rocket_ascent", SaveConfig.PREFS_FILE)      // v1
        assertNotEquals("rocket_ascent_v2", SaveConfig.PREFS_FILE)   // v2
    }

    @Test fun everyFieldRoundTrips() {
        val s = SaveData(
            coins = 98765, levels = UpgradeType.entries.associateWith { (it.maxLevel + 1) / 2 },
            bestAltitude = 384_400_123.5, bestSpeed = 11_020.25, bestDistance = 4321.75, flights = 42,
            achievements = Achievement.entries.toSet(), totalCoinsEarned = 1_234_567, moonLandings = 3,
            soundOn = false, tiltControl = true,
        )
        assertEquals(s, SaveData.parse(s.serialize()))
        assertEquals(SaveData(), SaveData.parse(null))
    }

    @Test fun flightEndAndPurchasesAreSavedAndReloadedAfterRestart() {
        val disk = DiskStore()
        val e = GameEngine(disk)
        assertEquals(SaveData(), e.save.value)          // fresh install starts clean

        flyOnce(e)                                       // flight end must write
        assertTrue("flight end did not write the save", disk.writes >= 1)
        assertEquals(e.save.value, SaveData.parse(disk.data))
        assertEquals(1, e.save.value.flights)
        assertTrue(Achievement.FIRST_FLIGHT in e.save.value.achievements)

        e.grantCoins(100_000)
        val before = disk.writes
        val t = UpgradeType.entries.first { e.save.value.canBuy(it) }
        assertTrue(e.buy(t))                             // purchase must write
        assertTrue("purchase did not write the save", disk.writes > before)
        assertEquals(1, SaveData.parse(disk.data).level(t))

        e.setTilt(true)                                  // settings write too
        e.persistNow()                                   // onPause path: synchronous flush
        assertEquals(1, disk.flushes)

        val restarted = GameEngine(disk)                 // app restart
        assertEquals(e.save.value, restarted.save.value)
        assertEquals(1, restarted.save.value.level(t))
        assertTrue(restarted.save.value.tiltControl)
        assertNotNull(restarted.spec)
        assertEquals(1, restarted.spec.level(t))         // upgrades actually applied to the rocket

        flyOnce(restarted)                               // progress keeps accumulating after restart
        assertEquals(2, GameEngine(disk).save.value.flights)
    }
}
