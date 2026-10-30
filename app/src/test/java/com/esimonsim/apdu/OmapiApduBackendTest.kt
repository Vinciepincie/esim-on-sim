package com.esimonsim.apdu

import com.esimonsim.adb.CardShell
import com.esimonsim.lpa.Hex
import com.esimonsim.lpa.Lpa
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OmapiApduBackendTest {
    private val eid = "89049032000000000000000000012345"

    @Test fun readerNames() {
        assertEquals(0, OmapiApduBackend.slotOf("SIM1"))
        assertEquals(1, OmapiApduBackend.slotOf("SIM2"))
        assertEquals(0, OmapiApduBackend.slotOf("SIM"))
        assertNull(OmapiApduBackend.slotOf("eSE1"))
        assertNull(OmapiApduBackend.slotOf("SD1"))
        assertNull(OmapiApduBackend.slotOf("SIMx"))
    }

    @Test fun commandsAsTelephonyBuildsThem() {
        assertEquals("80E2910003BF3E00", Hex.encode(OmapiApduBackend.command(0x80, 0xE2, 0x91, 0x00, 3, "BF3E00")))
        // GET RESPONSE: no data, P3 is how much to fetch.
        assertEquals("80C0000020", Hex.encode(OmapiApduBackend.command(0x80, 0xC0, 0, 0, 0x20, "")))
        assertThrows(IllegalArgumentException::class.java) { OmapiApduBackend.command(0x80, 0xE2, 0x91, 0, 4, "BF3E00") }
    }

    @Test fun lockedCardIsReportedAsLocked() {
        val e = assertThrows(OmapiApduBackend.UnavailableException::class.java) {
            OmapiApduBackend { FakeReaders(mapOf(0 to FakeCard.ORDINARY, 1 to FakeCard.LOCKED)) }.connect()
        }
        assertTrue(e.locked)
    }

    @Test fun noCardIsNotLocked() {
        val readers = FakeReaders(mapOf(0 to FakeCard.ORDINARY))
        val e = assertThrows(OmapiApduBackend.UnavailableException::class.java) { OmapiApduBackend { readers }.connect() }
        assertFalse(e.locked)
        assertTrue(readers.closed) // the service isn't kept bound for nothing
    }

    @Test fun noSecureElementServiceIsNotLocked() {
        val e = assertThrows(OmapiApduBackend.UnavailableException::class.java) {
            OmapiApduBackend { throw java.io.IOException("Secure element service unavailable") }.connect()
        }
        assertFalse(e.locked)
    }

    @Test fun lpaReadsTheCardDirectly() {
        val readers = FakeReaders(mapOf(0 to FakeCard.ORDINARY, 1 to FakeCard.OPEN)) { "BF3E125A10${eid}9000" }
        val backend = OmapiApduBackend { readers }
        backend.connect()
        val lpa = Lpa(backend)
        assertEquals(eid, lpa.discover())
        assertEquals(1, lpa.slot) // SIM2
        // Access rules name the full ID of the eSIM manager, so that's the one opened.
        assertEquals(Lpa.ISD_R, readers.opened.last())
        // The class byte goes out as is: Android adds the channel number.
        assertTrue(readers.sent.all { it.startsWith("80E2") })
        assertEquals(0, readers.openChannels)
    }

    @Test fun makersOwnEsimManagerIdIsKept() {
        val esimMe = "A0000005591010000000008900000300"
        val readers = FakeReaders(mapOf(1 to FakeCard.OPEN), aid = esimMe) { "BF3E125A10${eid}9000" }
        val backend = OmapiApduBackend { readers }
        backend.connect()
        assertEquals(eid, Lpa(backend).discover())
        assertEquals(listOf(Lpa.ISD_R, esimMe, esimMe), readers.opened)
    }

    @Test fun makersIdsAskedForFromOutsideNeverReachTheReaders() {
        // Lpa goes through the helper's list on every route; here it would cost a check of the card's access rules.
        val readers = FakeReaders(mapOf(1 to FakeCard.OPEN))
        val backend = OmapiApduBackend { readers }
        backend.connect()
        readers.opened.clear()
        for (id in CardShell.ISD_R_AIDS.drop(1)) assertEquals(0, backend.openChannel(1, 0, id).channel)
        assertTrue(readers.opened.isEmpty())
    }

    @Test fun anotherMakesCardIsFoundWithoutConnectingAgain() {
        val esimMe = "A0000005591010000000008900000300"
        val readers = FakeReaders(mapOf(1 to FakeCard.OPEN)) { "BF3E125A10${eid}9000" }
        val backend = OmapiApduBackend { readers }
        backend.connect()
        readers.aid = esimMe // the first card swapped for one with its maker's own ID
        val lpa = Lpa(backend)
        assertEquals(eid, lpa.discover())
        assertEquals(esimMe, readers.opened.last())
        // Kept: the ID that failed isn't asked for again.
        readers.opened.clear()
        assertEquals(eid, lpa.discover())
        assertEquals(listOf(esimMe), readers.opened)
        assertEquals(0, readers.openChannels)
    }

    @Test fun aCardMovedToTheOtherSimKeepsWorking() {
        val esimMe = "A0000005591010000000008900000300"
        val cards = mutableMapOf(0 to FakeCard.ORDINARY, 1 to FakeCard.OPEN)
        val readers = FakeReaders(cards, aid = esimMe)
        val backend = OmapiApduBackend { readers }
        backend.connect()
        cards[0] = FakeCard.OPEN
        cards[1] = FakeCard.ORDINARY
        assertTrue(backend.openChannel(0, 0, Lpa.ISD_R_OPEN).channel > 0)
        assertEquals(esimMe, readers.opened.last())
        assertTrue(backend.isReady())
    }

    @Test fun anIdTheCardRefusesThisAppIsOnlyThatId() {
        // Access rules can differ from one ID to the next, so a refusal mustn't end the search or card access.
        val esimMe = "A0000005591010000000008900000300"
        val fiveBer = "A0000005591010FFFFFFFF8900050500"
        val xeSim = "A0000005591010FFFFFFFF8900000177"
        val real = FakeReaders(mapOf(1 to FakeCard.OPEN), aid = xeSim)
        val readers = object : OmapiApduBackend.Readers by real {
            override fun openChannel(slot: Int, aid: ByteArray) =
                if (Hex.encode(aid) == esimMe) throw SecurityException("no") else real.openChannel(slot, aid)
        }
        val backend = OmapiApduBackend { readers }
        backend.connect()
        real.aid = fiveBer // another card, whose ID comes after the refused one
        assertTrue(backend.openChannel(1, 0, Lpa.ISD_R_OPEN).channel > 0)
        assertEquals(fiveBer, real.opened.last())
        assertTrue(backend.isReady())
    }

    @Test fun aSlotWithNoneOfTheIdsIsNoCardAndKeepsItsId() {
        val readers = FakeReaders(mapOf(1 to FakeCard.OPEN))
        val backend = OmapiApduBackend { readers }
        backend.connect()
        readers.aid = "A000000000" // none of them
        assertEquals(0, backend.openChannel(1, 0, Lpa.ISD_R_OPEN).channel)
        readers.aid = Lpa.ISD_R
        readers.opened.clear()
        assertTrue(backend.openChannel(1, 0, Lpa.ISD_R_OPEN).channel > 0)
        assertEquals(listOf(Lpa.ISD_R), readers.opened)
    }

    @Test fun aCardWithNoFreeChannelSaysSoInsteadOfNotFound() {
        // One open slot, the other a reader that fails to open: no channel left on the card there.
        val real = FakeReaders(mapOf(0 to FakeCard.ORDINARY, 1 to FakeCard.OPEN)) { "BF3E125A10${eid}9000" }
        var full = false
        val readers = object : OmapiApduBackend.Readers by real {
            override fun openChannel(slot: Int, aid: ByteArray) =
                if (full && slot == 1) throw java.io.IOException("No logical channel available") else real.openChannel(slot, aid)
        }
        val backend = OmapiApduBackend { readers }
        backend.connect()
        full = true
        val e = assertThrows(ApduException::class.java) { Lpa(backend).discover() }
        assertTrue(e.message!!, e.message!!.startsWith("Couldn't open the card in SIM 2"))
        assertTrue(e.message!!, "another eSIM app" in e.message!!)
    }

    @Test fun portsAndMissingReadersAreRefused() {
        val backend = OmapiApduBackend { FakeReaders(mapOf(1 to FakeCard.OPEN)) }
        backend.connect()
        assertThrows(ApduException::class.java) { backend.openChannel(1, 1, Lpa.ISD_R_OPEN) }
        assertEquals(0, backend.openChannel(3, 0, Lpa.ISD_R_OPEN).channel) // no reader there: no card
        assertThrows(ApduException::class.java) { backend.transmit(1, 0, 9, 0x80, 0xE2, 0x91, 0, 0, "") }
    }

    @Test fun otherSimRefusingTheAppIsNoCard() {
        val backend = OmapiApduBackend { FakeReaders(mapOf(0 to FakeCard.LOCKED, 1 to FakeCard.OPEN)) }
        backend.connect()
        assertEquals(0, backend.openChannel(0, 0, Lpa.ISD_R_OPEN).channel)
        assertTrue(backend.isReady())
    }

    @Test fun cardSwappedForALockedOneEndsDirectAccess() {
        val cards = mutableMapOf(1 to FakeCard.OPEN)
        val backend = OmapiApduBackend { FakeReaders(cards) }
        backend.connect()
        cards[1] = FakeCard.LOCKED
        assertThrows(ApduException::class.java) { backend.openChannel(1, 0, Lpa.ISD_R_OPEN) }
        assertFalse(backend.isReady()) // so the app looks for a way in again instead of failing on every refresh
    }

    @Test fun ordinarySimInASlotIsNoCard() {
        val backend = OmapiApduBackend { FakeReaders(mapOf(0 to FakeCard.ORDINARY, 1 to FakeCard.OPEN)) }
        backend.connect()
        assertEquals(0, backend.openChannel(0, 0, Lpa.ISD_R_OPEN).channel)
        backend.disconnect()
        assertFalse(backend.isReady())
    }
}
