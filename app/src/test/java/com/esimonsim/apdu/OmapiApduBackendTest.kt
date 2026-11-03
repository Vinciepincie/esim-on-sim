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
        // Its old slot still has an entry, but no card there lets this app in: a refusal starts card access over.
        cards[0] = FakeCard.LOCKED
        assertThrows(ApduException::class.java) { backend.openChannel(0, 0, Lpa.ISD_R_OPEN) }
        assertFalse(backend.isReady())
    }

    @Test fun aRefusalByOneCardLeavesTheOtherCardsAccess() {
        val cards = mutableMapOf(0 to FakeCard.OPEN, 1 to FakeCard.OPEN)
        val backend = OmapiApduBackend { FakeReaders(cards) }
        backend.connect()
        for (s in 0..1) backend.closeChannel(s, 0, backend.openChannel(s, 0, Lpa.ISD_R_OPEN).channel)
        cards[1] = FakeCard.LOCKED
        assertEquals(0, backend.openChannel(1, 0, Lpa.ISD_R_OPEN).channel)
        assertTrue(backend.isReady())
        assertTrue(backend.openChannel(0, 0, Lpa.ISD_R_OPEN).channel > 0)
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

    // ---------------- an eSIM card that refuses this app, told by its ATR ----------------

    private val fiveBer = "3B9F96803FC7828031E073FE211B57AA8660F001001EE1"
    /** A USIM's: T=15, but no TB after it. */
    private val ordinarySim = "3B9E95801FC78031E073FE211B66D001A11B0E00"

    @Test fun theAtrSaysEsimCardAsAndroidReadsIt() {
        for (atr in listOf("3F979580BFFE8210428031A073BE211797", "3B9F97C00A3FC6828031E073FE211F65D002341512810F51", fiveBer,
            "3F979580BFFE8310428031A073BE211797", "3F979580BFFE9210428031A073BE211797", // other bits don't matter
            fiveBer.dropLast(2))) // no check byte, as a phone may leave it off
            assertTrue(atr, OmapiApduBackend.saysEuicc(Hex.decode(atr)))
        for (atr in listOf("3F979580BEFE8210428031A073BE211797", // T=14, not 15
            "3F979580BFFE8110428031A073BE211797", // b8 without b2
            "3F979580B0FE0010428031A073BE2117", ordinarySim, "3B9F96803F", "3B9F", "3B", ""))
            assertFalse(atr, OmapiApduBackend.saysEuicc(Hex.decode(atr)))
        assertFalse(OmapiApduBackend.saysEuicc(null))
    }

    @Test fun anEsimCardThatRefusesThisAppIsToldApartFromAnOrdinarySim() {
        val readers = FakeReaders(mapOf(0 to FakeCard.LOCKED, 1 to FakeCard.OPEN, 2 to FakeCard.REFUSING_SIM, 3 to FakeCard.LOCKED),
            atrs = mapOf(0 to fiveBer, 2 to ordinarySim, 3 to null))
        val backend = OmapiApduBackend { readers }
        backend.connect()
        val locked = backend.openChannel(0, 0, Lpa.ISD_R_OPEN)
        assertEquals(OpenResult.LOCKED, locked.status)
        assertEquals(0, locked.channel)
        assertEquals(3, backend.openChannel(2, 0, Lpa.ISD_R_OPEN).status)
        assertEquals(3, backend.openChannel(3, 0, Lpa.ISD_R_OPEN).status) // no ATR to go by
        assertTrue(backend.isReady()) // nothing to do with the card that lets this app in
        assertEquals(3, readers.freshAtrReads)
    }

    @Test fun phonesWithAnEsimOfTheirOwnFlagNoCard() {
        // Their built-in chip could look the same, and they don't need telling.
        val readers = FakeReaders(mapOf(0 to FakeCard.LOCKED, 1 to FakeCard.OPEN), atrs = mapOf(0 to fiveBer))
        var asked = 0
        val backend = OmapiApduBackend(flagLocked = { asked++; false }) { readers }
        backend.connect()
        assertEquals(0, asked) // only once a card refuses
        assertEquals(3, backend.openChannel(0, 0, Lpa.ISD_R_OPEN).status)
        assertEquals(3, backend.openChannel(0, 0, Lpa.ISD_R_OPEN).status)
        assertEquals(1, asked)
        assertEquals(0, readers.freshAtrReads)
    }

    @Test fun aCardSwappedSinceIsToldByItsAtrNow() {
        // The slot's session still has the ATR of the ordinary SIM that was there; a 5ber is now.
        val readers = FakeReaders(mapOf(0 to FakeCard.REFUSING_SIM, 1 to FakeCard.OPEN), atrs = mapOf(0 to ordinarySim))
        val backend = OmapiApduBackend { readers }
        backend.connect()
        readers.atrs = mapOf(0 to fiveBer)
        assertEquals(OpenResult.LOCKED, backend.openChannel(0, 0, Lpa.ISD_R_OPEN).status)
        assertFalse(OmapiApduBackend.saysEuicc(readers.atr(0)))
        // And the other way round.
        readers.atrs = mapOf(0 to ordinarySim)
        assertEquals(3, backend.openChannel(0, 0, Lpa.ISD_R_OPEN).status)
    }

    @Test fun aCardThatLetThisAppInSwappedForARefusingOneIsLockedWhileAnotherStillOpens() {
        val cards = mutableMapOf(0 to FakeCard.OPEN, 1 to FakeCard.OPEN)
        val readers = FakeReaders(cards)
        val backend = OmapiApduBackend { readers }
        backend.connect()
        for (s in 0..1) backend.closeChannel(s, 0, backend.openChannel(s, 0, Lpa.ISD_R_OPEN).channel)
        cards[1] = FakeCard.LOCKED
        readers.atrs = mapOf(1 to fiveBer)
        // Said as for any other slot, so a refresh of that card finds whichever card is in the phone now.
        assertEquals(OpenResult.LOCKED, backend.openChannel(1, 0, Lpa.ISD_R_OPEN).status)
        assertTrue(backend.isReady())
    }

    @Test fun aCardTakenOutIsToldFromOneThatWontOpen() {
        val cards = mutableMapOf(0 to FakeCard.OPEN, 1 to FakeCard.ORDINARY)
        val backend = OmapiApduBackend { FakeReaders(cards) }
        assertNull(backend.present(0)) // not connected: nothing to say
        backend.connect()
        assertEquals(true, backend.present(0))
        assertEquals(true, backend.present(1))
        assertEquals(false, backend.present(3)) // no reader there
        cards[0] = FakeCard.EMPTY
        assertEquals(3, backend.openChannel(0, 0, Lpa.ISD_R_OPEN).status) // the same as for an ordinary SIM...
        assertEquals(false, backend.present(0)) // ...but Android says there's no card
    }

    @Test fun onlyARefusalCostsAnAtrRead() {
        val readers = FakeReaders(mapOf(0 to FakeCard.EMPTY, 1 to FakeCard.OPEN, 2 to FakeCard.ORDINARY))
        val backend = OmapiApduBackend { readers }
        backend.connect()
        assertEquals(3, backend.openChannel(0, 0, Lpa.ISD_R_OPEN).status)
        backend.closeChannel(1, 0, backend.openChannel(1, 0, Lpa.ISD_R_OPEN).channel)
        assertEquals(3, backend.openChannel(2, 0, Lpa.ISD_R_OPEN).status)
        assertEquals(3, backend.openChannel(3, 0, Lpa.ISD_R_OPEN).status) // no reader at all
        assertEquals(0, readers.freshAtrReads)
    }

    @Test fun connectSaysWhichCardsRefusedBeforeOneLetThisAppIn() {
        val readers = FakeReaders(mapOf(0 to FakeCard.LOCKED, 1 to FakeCard.OPEN, 2 to FakeCard.LOCKED),
            atrs = mapOf(0 to fiveBer, 2 to ordinarySim))
        assertEquals(setOf(fiveBer), OmapiApduBackend { readers }.connect()) // the card after it was never asked
        // One of the same make that lets this app in: that make isn't one that refuses.
        val same = FakeReaders(mapOf(0 to FakeCard.LOCKED, 1 to FakeCard.OPEN), atrs = mapOf(0 to fiveBer, 1 to fiveBer))
        assertEquals(emptySet<String>(), OmapiApduBackend { same }.connect())
        // Connected already: nothing new.
        val backend = OmapiApduBackend { readers }
        backend.connect()
        assertEquals(emptySet<String>(), backend.connect())
    }

    @Test fun readersButNoneForSimCardsAreSaid() {
        val none = OmapiApduBackend { FakeReaders(emptyMap()) }
        assertThrows(OmapiApduBackend.UnavailableException::class.java) { none.connect() }
        assertTrue(none.noReaders)
        val some = OmapiApduBackend { FakeReaders(mapOf(0 to FakeCard.ORDINARY)) }
        assertThrows(OmapiApduBackend.UnavailableException::class.java) { some.connect() }
        assertFalse(some.noReaders)
        // No service at all says nothing about its readers.
        val noService = OmapiApduBackend { throw java.io.IOException("Secure element service unavailable") }
        assertThrows(OmapiApduBackend.UnavailableException::class.java) { noService.connect() }
        assertFalse(noService.noReaders)
    }
}
