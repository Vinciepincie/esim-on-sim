package com.esimonsim.apdu

import com.esimonsim.lpa.Lpa
import com.esimonsim.lpa.SlotLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Debug builds' simulated SIM readers, which reach the simulated card the helper uses (adb/FakeCardShell). */
class FakeOmapiTest {
    @get:Rule val folder = TemporaryFolder()
    private val fiveBer = "3B9F96803FC7828031E073FE211B57AA8660F001001EE1"

    @Test fun directAccessReachesTheHelpersCardsAndTellsTheOneThatRefuses() {
        val tmp = folder.newFolder("tmp")
        val home = folder.newFolder("files")
        // A card that lets this app in in SIM 1, a 5ber that doesn't in SIM 2, and an empty reader for SIM 3.
        File(tmp, "fakecard.json").writeText("""{"cards": [{"slot": 0}, {"slot": 1, "aid": "A0000005591010FFFFFFFF8900050500"}],
            "omapi": [{"slot": 0, "atr": "$fiveBer"}, {"slot": 1, "refuse": true, "atr": "$fiveBer"}, {"slot": 2, "present": false}]}""")
        val readers = FakeOmapi(tmp, home)
        assertEquals(listOf(0, 1, 2), readers.slots)
        val backend = OmapiApduBackend { readers }
        assertEquals(emptySet<String>(), backend.connect())
        val lpa = Lpa(backend, accessRulesApply = { true })
        lpa.discover()
        assertEquals(0, lpa.slot)
        val profiles = lpa.getProfiles()
        assertEquals(listOf("Firsty", "Roamless", "1GLOBAL"), profiles.map { it.displayName })
        val looks = lpa.scan(inView = 0)
        assertEquals(SlotLook.What.LOCKED, looks.first { it.slot == 1 }.what)
        assertEquals(SlotLook.What.EMPTY, looks.first { it.slot == 2 }.what)
        // Changes stay in the app's own copy of the card: apps can't write the helper's folder.
        lpa.setNickname(profiles[1].iccid, "Travel")
        assertFalse(File(tmp, "fakecard-state.json").exists())
        assertTrue(File(home, "fakecard-state.json").exists())
        assertEquals("Travel", lpa.getProfiles()[1].displayName)
        backend.disconnect()
        assertFalse(readers.isConnected)
    }

    @Test fun theHelpersChangesShowThroughDirectAccess() {
        val tmp = folder.newFolder("tmp")
        val home = folder.newFolder("files")
        File(tmp, "fakecard.json").writeText("""{"slot": 0, "omapi": [{"slot": 0}]}""")
        val backend = OmapiApduBackend { FakeOmapi(tmp, home) }
        backend.connect()
        val lpa = Lpa(backend)
        lpa.discover()
        lpa.setNickname(lpa.getProfiles()[0].iccid, "Mine")
        // The helper wrote since: the newer card wins.
        val ours = File(home, "fakecard-state.json")
        File(tmp, "fakecard-state.json").writeText(ours.readText().replace("\"Mine\"", "\"Theirs\""))
        File(tmp, "fakecard-state.json").setLastModified(ours.lastModified() + 2000)
        assertEquals("Theirs", lpa.getProfiles()[0].displayName)
        // No ATR configured: none, as some phones give.
        assertNull(FakeOmapi(tmp, home).atr(0, fresh = true))
    }

    @Test fun aCardSwappedMidTestOnlyShowsInAFreshAtr() {
        val tmp = folder.newFolder("tmp")
        val config = File(tmp, "fakecard.json")
        config.writeText("""{"omapi": [{"slot": 1, "refuse": true, "atr": "3B00"}]}""")
        val readers = FakeOmapi(tmp, folder.newFolder("files"))
        assertEquals("3B00", com.esimonsim.lpa.Hex.encode(readers.atr(1)!!))
        config.writeText("""{"omapi": [{"slot": 1, "refuse": true, "atr": "$fiveBer"}]}""")
        assertEquals("3B00", com.esimonsim.lpa.Hex.encode(readers.atr(1)!!)) // as the slot's session keeps it
        assertTrue(OmapiApduBackend.saysEuicc(readers.atr(1, fresh = true)))
        assertNull(readers.atr(0))
    }
}
