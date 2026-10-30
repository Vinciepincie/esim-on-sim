package com.esimonsim.lpa

import com.esimonsim.adb.CardShell
import com.esimonsim.apdu.ApduBackend
import com.esimonsim.apdu.ApduException
import com.esimonsim.apdu.OpenResult
import com.esimonsim.apdu.SlotInfo
import com.esimonsim.es9.Es9Client
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ES10 layer against a scripted card: no device, card or network needed. */
class LpaTest {

    private val eid = "89049032000000000000000000012345"
    private val aid = "A0000005591010FFFFFFFF8900001000"

    /** A card in [cardSlot] (slot 1 unless set). [reply] gets each APDU as hex (CLA INS P1 P2 Lc data) and
     *  returns data + SW. While not [reachable], opening it fails the way the helper reports an empty slot. */
    private class FakeCard(val reply: (String) -> String) : ApduBackend {
        val apdus = ArrayList<String>()
        var openChannels = 0
        var opens = 0
        var cardSlot = 1
        var reachable = true
        /** The one eSIM manager ID it answers to; others get "no such applet" (3) from Android. */
        var isdR = Lpa.ISD_R_OPEN
        val tried = ArrayList<Pair<Int, String>>()
        override fun isReady() = true
        override fun openChannel(slot: Int, port: Int, aidHex: String): OpenResult {
            tried += slot to aidHex
            val here = slot == cardSlot && reachable
            if (here && aidHex == isdR) { opens++; openChannels++; return OpenResult(1, 0, null) }
            return OpenResult(-1, if (here) 3 else 2, null)
        }
        override fun transmit(slot: Int, port: Int, channel: Int, cla: Int, ins: Int, p1: Int, p2: Int, p3: Int, dataHex: String): String {
            val apdu = "%02X%02X%02X%02X%02X%s".format(cla, ins, p1, p2, p3, dataHex)
            apdus += apdu
            return reply(apdu)
        }
        override fun closeChannel(slot: Int, port: Int, channel: Int) { openChannels-- }
    }

    /** Discovered card in [cardSlot] whose ES10 replies (other than GetEID) come from [reply]. */
    private fun connected(reach: () -> SlotReach = { SlotReach.ALL }, cardSlot: Int = 1,
                          reply: (String) -> String): Pair<Lpa, FakeCard> {
        val card = FakeCard { if (it.endsWith("BF3E035C015A")) "BF3E125A10${eid}9000" else reply(it) }
        card.cardSlot = cardSlot
        val lpa = Lpa(card, slotReach = reach)
        assertEquals(eid, lpa.discover())
        card.apdus.clear()
        card.opens = 0
        return lpa to card
    }

    private fun data(apdu: String) = apdu.substring(10)

    private val esim = EuiccProfile("8944478600000000037", aid, false, null, "1GLOBAL", null, 2)
    private val profilesAsked = "BF2D0A5C085A4F9F7090919295"
    /** What GetProfilesInfo answers: one eSIM at [atAid] with ICCID bytes [swapped]. */
    private fun listWith(atAid: String = aid, swapped: String = "984474680000000030F7") = Hex.encode(Tlv.build(0xBF2D,
        Tlv.buildSeq(0xA0, Tlv.buildSeq(0xE3, Tlv.build(0x5A, Hex.decode(swapped)), Tlv.build(0x4F, Hex.decode(atAid)))))) + "9000"
    /** A card that holds [esim], and answers everything but the list with [reply]. */
    private fun onCard(list: String = listWith(), reply: (String) -> String): (String) -> String =
        { if (it.endsWith(profilesAsked)) list else reply(it) }
    /** What went to the card apart from the list. */
    private fun FakeCard.sent() = apdus.filterNot { it.endsWith(profilesAsked) }

    @Test fun discoverFindsCardAndClosesChannel() {
        val (lpa, card) = connected { error("unexpected $it") }
        assertEquals(1, lpa.slot)
        assertEquals(0, card.openChannels)
    }

    @Test fun discoverWithoutCardSaysSo() {
        val empty = object : ApduBackend by FakeCard({ "9000" }) {
            override fun openChannel(slot: Int, port: Int, aidHex: String) = OpenResult(-1, 2, null)
        }
        val e = assertThrows(ApduException::class.java) { Lpa(empty).discover() }
        assertTrue(e.message!!, e.message!!.startsWith("Couldn't find the eSIM card"))
    }

    @Test fun discoverExplainsWhereOlderAndroidVersionsReachTheCard() {
        // The helper answers status 2 for every slot it can't reach, just as for an empty one.
        val empty = object : ApduBackend by FakeCard({ "9000" }) {
            override fun openChannel(slot: Int, port: Int, aidHex: String) = OpenResult(0, 2, null)
        }
        fun message(reach: SlotReach) =
            assertThrows(ApduException::class.java) { Lpa(empty, slotReach = { reach }).discover() }.message!!
        assertEquals(Lpa.NOT_FOUND, message(SlotReach.ALL))
        assertEquals(Lpa.NOT_FOUND_FIRST_OR_ACTIVE, message(SlotReach.FIRST_OR_ACTIVE))
        assertEquals(Lpa.NOT_FOUND_FIRST_ONLY, message(SlotReach.FIRST_ONLY))
        for (reach in SlotReach.entries) assertTrue(message(reach).startsWith("Couldn't find the eSIM card"))
        for (reach in listOf(SlotReach.FIRST_OR_ACTIVE, SlotReach.FIRST_ONLY)) assertTrue("SIM 1" in message(reach))
    }

    @Test fun theLastEsimOnStaysOnWhereTurningItOffWouldPutTheCardOutOfReach() {
        // Android 7-9 through the helper, the card in SIM 2: refused before the card is touched.
        val (lpa, card) = connected({ SlotReach.FIRST_OR_ACTIVE }) { error("must not reach the card") }
        assertEquals(Lpa.LAST_ESIM_ON, assertThrows(ApduException::class.java) { lpa.disable(esim) }.message)
        assertTrue(card.apdus.isEmpty())
        assertEquals(0, card.opens)
        // Switching leaves another eSIM on, so it goes ahead.
        val (switching, card2) = connected({ SlotReach.FIRST_OR_ACTIVE }, reply = onCard { "BF31038001009000" })
        switching.enable(esim)
        assertEquals("BF3117A0124F10${aid}8101FF", data(card2.sent().single()))
    }

    @Test fun turningOffGoesAheadInSim1OrWhereEverySlotIsReached() {
        val off = "BF3217A0124F10${aid}8101FF"
        // In SIM 1, Android 7-9 reach the card with no eSIM on.
        val (sim1, card) = connected({ SlotReach.FIRST_OR_ACTIVE }, cardSlot = 0, reply = onCard { "BF32038001009000" })
        sim1.disable(esim)
        assertEquals(off, data(card.sent().single()))
        // Android 10+ or direct access; asked each time, as the way to the card can change.
        var reach = SlotReach.FIRST_OR_ACTIVE
        val (lpa, card2) = connected({ reach }, reply = onCard { "BF32038001009000" })
        reach = SlotReach.ALL
        lpa.disable(esim)
        assertEquals(off, data(card2.sent().single()))
        assertEquals(0, card2.openChannels)
    }

    @Test fun aCardOutsideSim1ThatAndroidNoLongerReachesIsExplained() {
        val (lpa, card) = connected({ SlotReach.FIRST_OR_ACTIVE }) { error("must not reach the card") }
        card.reachable = false
        assertEquals(Lpa.OUT_OF_REACH, assertThrows(ApduException::class.java) { lpa.getProfiles() }.message)
        // Everywhere else it's the plain error.
        val (sim1, card2) = connected({ SlotReach.FIRST_OR_ACTIVE }, cardSlot = 0) { error("must not reach the card") }
        card2.reachable = false
        assertEquals("Couldn't open the card (status 2). Try again.",
            assertThrows(ApduException::class.java) { sim1.getProfiles() }.message)
    }

    @Test fun discoverSurfacesConnectionFailure() {
        val broken = object : ApduBackend by FakeCard({ "9000" }) {
            override fun openChannel(slot: Int, port: Int, aidHex: String): OpenResult = throw ApduException("helper not connected")
        }
        val e = assertThrows(ApduException::class.java) { Lpa(broken).discover() }
        assertEquals("helper not connected", e.message)
    }

    private val esimMe = "A0000005591010000000008900000300"

    @Test fun aCardThatOnlyAnswersToItsMakersIdIsFoundAndStaysOnThatId() {
        val card = FakeCard { if (it.endsWith("BF3E035C015A")) "BF3E125A10${eid}9000" else listWith() }
        card.isdR = esimMe
        val lpa = Lpa(card)
        assertEquals(eid, lpa.discover())
        assertEquals(1 to esimMe, card.tried.last())
        card.tried.clear()
        lpa.getProfiles()
        assertEquals(listOf(1 to esimMe), card.tried)
        // Asked first next time, so the other IDs aren't paid for again.
        card.tried.clear()
        lpa.discover()
        assertEquals(listOf(1 to esimMe), card.tried)
    }

    @Test fun aStandardCardCostsAnOrdinarySimNoMakersIds() {
        val tried = ArrayList<Pair<Int, String>>()
        val card = object : ApduBackend by FakeCard({ "BF3E125A10${eid}9000" }) {
            override fun openChannel(slot: Int, port: Int, aidHex: String): OpenResult {
                tried += slot to aidHex
                return if (slot == 0) OpenResult(1, 0, null) else OpenResult(-1, 3, null)
            }
        }
        Lpa(card).discover()
        assertEquals(listOf(1 to Lpa.ISD_R_OPEN, 0 to Lpa.ISD_R_OPEN), tried)
    }

    @Test fun onlySlotsThatCouldHoldACardGetTheMakersIds() {
        // No card, or out of reach (2), against an ordinary SIM (3) or one that failed for another reason (4).
        val status = mapOf(0 to 2, 1 to 3, 2 to 4, 3 to 2)
        val tried = ArrayList<Int>()
        val slots = object : ApduBackend by FakeCard({ "9000" }) {
            override fun openChannel(slot: Int, port: Int, aidHex: String): OpenResult { tried += slot; return OpenResult(-1, status[slot]!!, null) }
        }
        assertThrows(ApduException::class.java) { Lpa(slots).discover() }
        val ids = CardShell.ISD_R_AIDS.size
        assertEquals(mapOf(0 to 1, 1 to ids, 2 to ids, 3 to 1), tried.groupingBy { it }.eachCount())
    }

    @Test fun aMakersCardInTheTrayBeatsThePhonesOwnEuicc() {
        // Slot 1 is built in and answers the usual ID; the tray's card only answers to its own.
        val both = object : ApduBackend by FakeCard({ "BF3E125A10${eid}9000" }) {
            override fun slots() = listOf(SlotInfo(euicc = true, removable = true), SlotInfo(euicc = true, removable = false))
            override fun openChannel(slot: Int, port: Int, aidHex: String) = when {
                slot == 0 && aidHex == esimMe -> OpenResult(1, 0, null)
                slot == 1 && aidHex == Lpa.ISD_R_OPEN -> OpenResult(1, 0, null)
                else -> OpenResult(-1, 3, null)
            }
        }
        val lpa = Lpa(both, phoneHasEsim = true)
        lpa.discover()
        assertEquals(0, lpa.slot)
    }

    @Test fun aMakersIdThatOpensSomethingElseIsPassedOver() {
        val noManager = "A0000005591010FFFFFFFF8900050500"
        val xeSim = "A0000005591010FFFFFFFF8900000177"
        val opened = ArrayList<String>()
        val card = object : ApduBackend by FakeCard({ if (opened.last() == xeSim) "BF3E125A10${eid}9000" else "6D00" }) {
            override fun openChannel(slot: Int, port: Int, aidHex: String): OpenResult {
                if (slot != 1 || aidHex !in setOf(noManager, xeSim)) return OpenResult(-1, 3, null)
                opened += aidHex
                return OpenResult(1, 0, null)
            }
        }
        val lpa = Lpa(card)
        assertEquals(eid, lpa.discover())
        // The ID that worked is the one kept: asked first next time, and nothing else.
        opened.clear()
        lpa.discover()
        assertEquals(listOf(xeSim), opened)
    }

    /** Slot 1 opens, and GetEID then answers [status]; with none, the link drops there. Other slots answer 2. */
    private fun eidRead(status: String?) = object : ApduBackend by FakeCard({ "9000" }) {
        var ready = true
        var closed = 0
        override fun isReady() = ready
        override fun closeChannel(slot: Int, port: Int, channel: Int) { closed++ }
        override fun openChannel(slot: Int, port: Int, aidHex: String): OpenResult {
            if (!ready) throw ApduException("Card access disconnected. Reconnect first.")
            return if (slot == 1 && aidHex == Lpa.ISD_R_OPEN) OpenResult(1, 0, null) else OpenResult(-1, 2, null)
        }
        override fun transmit(slot: Int, port: Int, channel: Int, cla: Int, ins: Int, p1: Int, p2: Int, p3: Int, dataHex: String): String {
            if (status != null) return status
            ready = false
            throw ApduException("Card connection lost or timed out. Reconnect and refresh before trying again.")
        }
    }

    @Test fun aCardThatOpensButWontGiveItsEidIsNotCalledMissing() {
        val refused = eidRead("6985")
        val e = assertThrows(ApduException::class.java) { Lpa(refused).discover() }
        assertEquals("The card didn't accept the request (status 6985). Try again.", e.message)
        assertEquals(1, refused.closed)
    }

    @Test fun aConnectionLostReadingTheEidIsReportedAsThat() {
        val e = assertThrows(ApduException::class.java) { Lpa(eidRead(null)).discover() }
        assertTrue(e.message!!, e.message!!.startsWith("Card connection lost"))
    }

    @Test fun aLinkLostPartwayThroughTheSlotsIsNotAMissingCard() {
        // The first slot is empty, then the link dies opening the next: the card there was never asked.
        var opens = 0
        var ready = true
        val backend = object : ApduBackend by FakeCard({ "9000" }) {
            override fun isReady() = ready
            override fun openChannel(slot: Int, port: Int, aidHex: String): OpenResult {
                if (++opens == 1) return OpenResult(-1, 2, null)
                ready = false
                throw ApduException("Card connection lost or timed out. Reconnect and refresh before trying again.")
            }
        }
        val e = assertThrows(ApduException::class.java) { Lpa(backend).discover() }
        assertTrue(e.message!!, e.message!!.startsWith("Card connection lost"))
    }

    @Test fun aFailedRefreshLeavesTheCardWhereItWas() {
        var answering = true
        val card = FakeCard { if (answering) "BF3E125A10${eid}9000" else "6985" }
        card.cardSlot = 0
        val lpa = Lpa(card)
        lpa.discover()
        answering = false
        card.cardSlot = 3
        assertThrows(ApduException::class.java) { lpa.discover() }
        assertEquals(0, lpa.slot)
    }

    @Test fun aSecondSlotStillWinsAfterAFailedRead() {
        // Slot 1 opens and refuses; the card in slot 0 answers. Nothing to report.
        var slotNow = -1
        val card = object : ApduBackend by FakeCard({ if (slotNow == 0) "BF3E125A10${eid}9000" else "6985" }) {
            override fun openChannel(slot: Int, port: Int, aidHex: String): OpenResult {
                slotNow = slot
                return if (slot <= 1 && aidHex == Lpa.ISD_R_OPEN) OpenResult(1, 0, null) else OpenResult(-1, 2, null)
            }
        }
        val lpa = Lpa(card)
        assertEquals(eid, lpa.discover())
        assertEquals(0, lpa.slot)
    }

    @Test fun emptySlotsAndSlotsPastThePhonesLastStillMeanNotFound() {
        // Android 10+ through the helper: slots past the last throw, with the link fine.
        val phone = object : ApduBackend by FakeCard({ "9000" }) {
            override fun openChannel(slot: Int, port: Int, aidHex: String): OpenResult {
                if (slot > 1) throw IllegalArgumentException("Given slot index: $slot does not correspond to an active phone")
                return OpenResult(-1, 2, null)
            }
        }
        assertEquals(Lpa.NOT_FOUND, assertThrows(ApduException::class.java) { Lpa(phone).discover() }.message)
    }

    @Test fun aCardWithNoChannelLeftIsNotCalledMissing() {
        val full = ApduException("Couldn't open the card in SIM 1. Try again.", cardThere = true)
        val busy = object : ApduBackend by FakeCard({ "9000" }) {
            override fun openChannel(slot: Int, port: Int, aidHex: String): OpenResult =
                if (slot == 0) throw full else OpenResult(-1, 2, null)
        }
        assertEquals(full.message, assertThrows(ApduException::class.java) { Lpa(busy).discover() }.message)
    }

    @Test fun thePhonesOwnEuiccRefusingDoesNotReplaceNotFound() {
        val own = object : ApduBackend by FakeCard({ "6985" }) {
            override fun slots() = listOf(SlotInfo(euicc = true, removable = false), SlotInfo(euicc = false, removable = true))
            override fun openChannel(slot: Int, port: Int, aidHex: String) =
                if (slot == 0 && aidHex == Lpa.ISD_R_OPEN) OpenResult(1, 0, null) else OpenResult(-1, 2, null)
        }
        assertEquals(Lpa.NOT_FOUND, assertThrows(ApduException::class.java) { Lpa(own, phoneHasEsim = true).discover() }.message)
    }

    /** The card in slot 1, reported the way a phone without eSIM support reports a 5ber card: a fixed eUICC. */
    private fun fixedEuiccCard(): ApduBackend = object : ApduBackend by
        FakeCard({ if (it.endsWith("BF3E035C015A")) "BF3E125A10${eid}9000" else error("unexpected $it") }) {
        override fun slots() = listOf(SlotInfo(euicc = false, removable = true), SlotInfo(euicc = true, removable = false))
    }

    @Test fun phonesWithoutEsimNeverSkipTheCard() {
        val lpa = Lpa(fixedEuiccCard(), phoneHasEsim = false)
        assertEquals(eid, lpa.discover())
        assertFalse(lpa.onBuiltInSlot)
    }

    @Test fun phonesWithEsimTryTheirBuiltInEuiccLast() {
        // Slot 1 is reported built in, and an eUICC answers in both: the removable one in slot 0 wins.
        val opened = ArrayList<Int>()
        val both = object : ApduBackend by FakeCard({ "BF3E125A10${eid}9000" }) {
            override fun slots() = listOf(SlotInfo(euicc = true, removable = true), SlotInfo(euicc = true, removable = false))
            override fun openChannel(slot: Int, port: Int, aidHex: String): OpenResult { opened += slot; return OpenResult(1, 0, null) }
        }
        val lpa = Lpa(both, phoneHasEsim = true)
        lpa.discover()
        assertEquals(0, lpa.slot)
        assertEquals(listOf(0), opened)
        assertFalse(lpa.onBuiltInSlot)
        // Only the "built-in" slot answers: still found, as a phone could report the card's own slot that way, and
        // the app is told, to ask.
        val only = Lpa(fixedEuiccCard(), phoneHasEsim = true)
        assertEquals(eid, only.discover())
        assertTrue(only.onBuiltInSlot)
    }

    @Test fun aBuiltInSlotThatWontGiveItsEidIsNotReportedAsTheOneFound() {
        val own = object : ApduBackend by FakeCard({ "6985" }) {
            override fun slots() = listOf(SlotInfo(euicc = true, removable = false), SlotInfo(euicc = false, removable = true))
            override fun openChannel(slot: Int, port: Int, aidHex: String) =
                if (slot == 0 && aidHex == Lpa.ISD_R_OPEN) OpenResult(1, 0, null) else OpenResult(-1, 2, null)
        }
        val lpa = Lpa(own, phoneHasEsim = true)
        assertThrows(ApduException::class.java) { lpa.discover() }
        assertFalse(lpa.onBuiltInSlot)
    }

    @Test fun aRefreshThatFindsTheRemovableCardAgainClearsTheFlag() {
        var trayFull = false
        val both = object : ApduBackend by FakeCard({ "BF3E125A10${eid}9000" }) {
            override fun slots() = listOf(SlotInfo(euicc = true, removable = true), SlotInfo(euicc = true, removable = false))
            override fun openChannel(slot: Int, port: Int, aidHex: String) =
                if (slot == 1 || trayFull) OpenResult(1, 0, null) else OpenResult(-1, 2, null)
        }
        val lpa = Lpa(both, phoneHasEsim = true)
        lpa.discover()
        assertTrue(lpa.onBuiltInSlot)
        trayFull = true
        lpa.discover()
        assertEquals(0, lpa.slot)
        assertFalse(lpa.onBuiltInSlot)
    }

    private val eidOnly: (String) -> String = { "BF3E125A10${eid}9000" }

    @Test fun anEmptySlotListHoldsTheCardBack() {
        // Android 9 without root: nothing says this isn't the phone's own chip.
        val lpa = Lpa(FakeCard(eidOnly), phoneHasEsim = true)
        assertEquals(eid, lpa.discover())
        assertTrue(lpa.onBuiltInSlot)
    }

    @Test fun aSlotListThatFailsHoldsTheCardBack() {
        // The phone process just restarted: the list fails, and the opens after it reach the new process.
        val restarted = object : ApduBackend by FakeCard(eidOnly) {
            override fun slots(): List<SlotInfo> = throw ApduException("Android's SIM service restarted. Try again in a moment.")
        }
        val lpa = Lpa(restarted, phoneHasEsim = true)
        assertEquals(eid, lpa.discover())
        assertTrue(lpa.onBuiltInSlot)
    }

    @Test fun theSlotListComingBackClearsTheHold() {
        var list = emptyList<SlotInfo>()
        val card = object : ApduBackend by FakeCard(eidOnly) { override fun slots() = list }
        val lpa = Lpa(card, phoneHasEsim = true)
        lpa.discover()
        assertTrue(lpa.onBuiltInSlot)
        list = listOf(SlotInfo(euicc = false, removable = true), SlotInfo(euicc = true, removable = true))
        lpa.discover()
        assertFalse(lpa.onBuiltInSlot)
    }

    @Test fun noSlotListHoldsNothingBackWhereAccessRulesKeepTheChipShut() {
        // Direct card access never has a list, so this would be every card there.
        val lpa = Lpa(FakeCard(eidOnly), phoneHasEsim = true, accessRulesApply = { true })
        assertEquals(eid, lpa.discover())
        assertFalse(lpa.onBuiltInSlot)
    }

    @Test fun noSlotListHoldsNothingBackOnPhonesWithoutEsim() {
        val lpa = Lpa(FakeCard(eidOnly), phoneHasEsim = false)
        assertEquals(eid, lpa.discover())
        assertFalse(lpa.onBuiltInSlot)
    }

    @Test fun discoverTriesTheLastSlotFirst() {
        val opened = ArrayList<Int>()
        val card = object : ApduBackend by FakeCard({ "BF3E125A10${eid}9000" }) {
            override fun openChannel(slot: Int, port: Int, aidHex: String): OpenResult {
                opened += slot
                return if (slot == 0) OpenResult(1, 0, null) else OpenResult(-1, 2, null)
            }
        }
        val lpa = Lpa(card)
        lpa.discover()
        assertEquals(listOf(1, 0), opened)
        opened.clear()
        lpa.discover()
        assertEquals(listOf(0), opened)
    }

    @Test fun theSlotFromTheLastRunIsTriedFirst() {
        val opened = ArrayList<Int>()
        val card = object : ApduBackend by FakeCard({ "BF3E125A10${eid}9000" }) {
            override fun openChannel(slot: Int, port: Int, aidHex: String): OpenResult {
                opened += slot
                return if (slot == 0) OpenResult(1, 0, null) else OpenResult(-1, 2, null)
            }
        }
        val lpa = Lpa(card)
        lpa.slotHint = 0
        lpa.discover()
        assertEquals(listOf(0), opened)
    }

    @Test fun anOutOfDateSlotOnlyCostsOneTry() {
        val opened = ArrayList<Int>()
        val card = object : ApduBackend by FakeCard({ "BF3E125A10${eid}9000" }) {
            override fun openChannel(slot: Int, port: Int, aidHex: String): OpenResult {
                opened += slot
                return if (slot == 1) OpenResult(1, 0, null) else OpenResult(-1, 2, null)
            }
        }
        val lpa = Lpa(card)
        lpa.slotHint = 0
        assertEquals(eid, lpa.discover())
        assertEquals(1, lpa.slot)
        assertEquals(listOf(0, 1), opened)
    }

    @Test fun theSlotFromTheLastRunIsNoSubstituteForFindingTheCard() {
        val (lpa, _) = connected { error("unexpected $it") }
        val fresh = Lpa(object : ApduBackend by FakeCard({ "9000" }) {})
        fresh.slotHint = lpa.slot
        assertThrows(IllegalStateException::class.java) { fresh.getProfiles() }
    }

    /** An ordinary SIM in slot 0, a standard card in slot 1 reported as built in, and no slots past those, which throw
     *  on Android 10+. */
    private inner class OrdinaryAndBuiltIn {
        val opened = ArrayList<Pair<Int, String>>()
        val backend = object : ApduBackend by FakeCard(eidOnly) {
            override fun slots() = listOf(SlotInfo(euicc = false, removable = true), SlotInfo(euicc = true, removable = false))
            override fun openChannel(slot: Int, port: Int, aidHex: String): OpenResult {
                opened += slot to aidHex
                if (slot > 1) throw ApduException("The card didn't answer. Check that it's in the phone, then try again.")
                return if (slot == 1 && aidHex == Lpa.ISD_R_OPEN) OpenResult(1, 0, null) else OpenResult(-1, 3, null)
            }
        }
    }

    @Test fun aCardPeopleConfirmedSparesAnOrdinarySimTheMakersIds() {
        val phone = OrdinaryAndBuiltIn()
        val lpa = Lpa(phone.backend, phoneHasEsim = true)
        lpa.slotHint = 1
        assertEquals(eid, lpa.discover())
        assertEquals(listOf(0, 2, 3, 1).map { it to Lpa.ISD_R_OPEN }, phone.opened)
    }

    @Test fun aCardNobodyConfirmedComesAfterTheMakersIds() {
        // A maker's card in the tray has to get its chance before the phone's own chip.
        val phone = OrdinaryAndBuiltIn()
        val lpa = Lpa(phone.backend, phoneHasEsim = true)
        lpa.discover()
        assertEquals(CardShell.ISD_R_AIDS.size * 3 + 1, phone.opened.size)
        assertEquals(1 to Lpa.ISD_R_OPEN, phone.opened.last())
    }

    @Test fun aSlotHeldBackInThisRunDoesNotCountAsConfirmed() {
        val phone = OrdinaryAndBuiltIn()
        val lpa = Lpa(phone.backend, phoneHasEsim = true)
        lpa.discover()
        phone.opened.clear()
        lpa.discover() // only the slot it found: no hint, so nobody has said it's theirs
        assertEquals(1 to Lpa.ISD_R_OPEN, phone.opened.last())
        assertEquals(CardShell.ISD_R_AIDS.size * 3 + 1, phone.opened.size)
    }

    @Test fun aConfirmedSlotThatLostItsCardStillFindsTheOthers() {
        val maker = CardShell.ISD_R_AIDS[2]
        val card = object : ApduBackend by FakeCard(eidOnly) {
            override fun slots() = listOf(SlotInfo(euicc = true, removable = true), SlotInfo(euicc = true, removable = false))
            override fun openChannel(slot: Int, port: Int, aidHex: String) =
                if (slot == 0 && aidHex == maker) OpenResult(1, 0, null) else OpenResult(-1, if (slot == 1) 2 else 3, null)
        }
        val lpa = Lpa(card, phoneHasEsim = true)
        lpa.slotHint = 1
        assertEquals(eid, lpa.discover())
        assertEquals(0, lpa.slot)
        assertFalse(lpa.onBuiltInSlot)
    }

    @Test fun aConfirmedSlotWinsOverAMakersCardInTheTray() {
        // The deliberate cost of sparing the ordinary SIM: only the usual ID goes round first.
        val maker = CardShell.ISD_R_AIDS[2]
        val card = object : ApduBackend by FakeCard(eidOnly) {
            override fun slots() = listOf(SlotInfo(euicc = true, removable = true), SlotInfo(euicc = true, removable = false))
            override fun openChannel(slot: Int, port: Int, aidHex: String) = when {
                slot == 0 && aidHex == maker -> OpenResult(1, 0, null)
                slot == 1 && aidHex == Lpa.ISD_R_OPEN -> OpenResult(1, 0, null)
                else -> OpenResult(-1, 3, null)
            }
        }
        val lpa = Lpa(card, phoneHasEsim = true)
        lpa.slotHint = 1
        lpa.discover()
        assertEquals(1, lpa.slot)
        assertTrue(lpa.onBuiltInSlot) // what's held back still goes by the EID in the app
    }

    @Test fun aStandardCardInTheTrayBeatsAConfirmedSlot() {
        val opened = ArrayList<Int>()
        val both = object : ApduBackend by FakeCard({ "BF3E125A10${eid}9000" }) {
            override fun slots() = listOf(SlotInfo(euicc = true, removable = true), SlotInfo(euicc = true, removable = false))
            override fun openChannel(slot: Int, port: Int, aidHex: String): OpenResult { opened += slot; return OpenResult(1, 0, null) }
        }
        val lpa = Lpa(both, phoneHasEsim = true)
        lpa.slotHint = 1
        lpa.discover()
        assertEquals(listOf(0), opened)
    }

    @Test fun configuredAddressesAreRead() {
        val reply = Tlv.buildSeq(0xBF3C, Tlv.build(0x81, "lpa.ds.gsma.com".toByteArray()))
        val (lpa, card) = connected { Hex.encode(reply) + "9000" }
        assertEquals(null to "lpa.ds.gsma.com", lpa.configuredAddresses())
        assertEquals("BF3C00", data(card.apdus.single()))
    }

    @Test fun longRequestsAreSplitIntoStoreDataBlocks() {
        val (lpa, card) = connected { "9000" }
        val payload = ByteArray(250) { it.toByte() }
        lpa.sendEs10(1, payload)
        assertEquals(listOf("80E2110078", "80E2110178", "80E291020A"), card.apdus.map { it.take(10) })
        assertEquals(Hex.encode(payload), card.apdus.joinToString("") { data(it) })
    }

    @Test fun responsesAreReassembledAcrossGetResponse() {
        val (lpa, card) = connected { if (it.startsWith("00C0")) "CCDD9000" else "AABB6102" }
        assertEquals("AABBCCDD", Hex.encode(lpa.sendEs10(1, byteArrayOf(1))))
        assertEquals("00C0000002", card.apdus.last())
    }

    /** BF36 with 8 segments, as in boundProfilePackageIsSegmentedLikeLpac. */
    private fun eightSegmentBpp(): ByteArray {
        val a1 = Tlv.buildSeq(0xA1, Tlv.build(0x88, ByteArray(2)), Tlv.build(0x88, ByteArray(2)))
        val a3 = Tlv.buildSeq(0xA3, Tlv.build(0x86, ByteArray(5)), Tlv.build(0x86, ByteArray(5)))
        return Tlv.buildSeq(0xBF36, Tlv.build(0xBF23, ByteArray(4)), Tlv.build(0xA0, Tlv.build(0x87, ByteArray(3))), a1, a3)
    }

    @Test fun aProactiveCommandWaitingOnAnIntermediateBlockIsNotAFailure() {
        val (lpa, card) = connected { "9110" }
        assertEquals(0, lpa.sendEs10(1, ByteArray(250)).size)
        assertEquals(3, card.apdus.size)
    }

    @Test fun anInstallCarriesOnThroughSegmentsThatAnswer91xxWithoutData() {
        var n = 0 // lower case, as Android formats the status word
        val (lpa, card) = connected { if (++n == 8) "BF370091ab" else "91ab" }
        assertEquals("BF3700", Hex.encode(lpa.loadBpp(1, eightSegmentBpp())))
        assertEquals(8, card.apdus.size)
    }

    @Test fun dataWithAProactiveCommandWaitingStaysSuccess() {
        val (lpa, _) = connected(reply = onCard { "BF32038001009140" })
        lpa.disable(esim) // no exception
        val (lpa2, _) = connected(reply = onCard { "BF32038001029140" })
        assertEquals("That eSIM is already off.", assertThrows(ApduException::class.java) { lpa2.disable(esim) }.message)
    }

    @Test fun getResponseFinishingWith91xxKeepsAllData() {
        val (lpa, _) = connected { if (it.startsWith("00C0")) "CCDD91ab" else "AABB6102" }
        assertEquals("AABBCCDD", Hex.encode(lpa.sendEs10(1, byteArrayOf(1))))
    }

    @Test fun anErrorOnGetResponseIsAnErrorNotATruncatedReply() {
        val (lpa, _) = connected { if (it.startsWith("00C0")) "6F00" else "AABB6100" }
        val e = assertThrows(ApduException::class.java) { lpa.sendEs10(1, byteArrayOf(1)) }
        assertTrue(e.message!!, "6F00" in e.message!!)
    }

    @Test fun dataWithAnErrorStatusIsAnError() {
        // As direct card access gives it: Android fetches a long reply by itself, so both arrive together.
        val (lpa, _) = connected { "AABB6F00" }
        assertThrows(ApduException::class.java) { lpa.sendEs10(1, byteArrayOf(1)) }
    }

    @Test fun cardFullInLowerCaseAfterDataSaysSo() {
        val (lpa, _) = connected { "AABB6a84" }
        assertEquals(Lpa.CARD_FULL, assertThrows(ApduException::class.java) { lpa.sendEs10(1, byteArrayOf(1)) }.message)
    }

    @Test fun otherNinetyStatusesStayFailuresWithoutData() {
        // 9300 "toolkit busy": the card did not do the command, so it must not pass as an empty success.
        for (sw in listOf("9300", "9240", "9804")) {
            val (lpa, _) = connected { sw }
            assertThrows(sw, ApduException::class.java) { lpa.sendEs10(1, ByteArray(250)) }
            val (lpa2, _) = connected { sw }
            assertThrows(sw, ApduException::class.java) { lpa2.sendEs10(1, byteArrayOf(1)) }
        }
    }

    @Test fun cardFullOnAnIntermediateBlockStaysPlainWords() {
        val (lpa, card) = connected { "6A84" }
        assertEquals(Lpa.CARD_FULL, assertThrows(ApduException::class.java) { lpa.sendEs10(1, ByteArray(250)) }.message)
        assertEquals(1, card.apdus.size)
    }

    @Test fun profilesAreParsed() {
        fun e3(iccidSwapped: String, aid: String, state: Int, name: String) = Tlv.buildSeq(0xE3,
            Tlv.build(0x5A, Hex.decode(iccidSwapped)), Tlv.build(0x4F, Hex.decode(aid)),
            Tlv.build(0x9F70, byteArrayOf(state.toByte())), Tlv.build(0x91, name.toByteArray()),
            Tlv.build(0x95, byteArrayOf(2)))
        val list = Tlv.build(0xBF2D, Tlv.buildSeq(0xA0,
            e3("984474680000000030F7", aid, 1, "1GLOBAL"),
            e3("98103254769810325476", aid.dropLast(4) + "1100", 0, "Firsty")))
        val (lpa, card) = connected { Hex.encode(list) + "9000" }

        val p = lpa.getProfiles()
        assertEquals("BF2D0A5C085A4F9F7090919295", data(card.apdus.single()))
        assertEquals(listOf("8944478600000000037", "89012345678901234567"), p.map { it.iccid })
        assertEquals(listOf(true, false), p.map { it.enabled })
        assertEquals(listOf("1GLOBAL", "Firsty"), p.map { it.displayName })
        assertEquals(aid, p[0].isdpAid)
        assertEquals(0, card.openChannels)
    }

    @Test fun theCardsOwnListErrorIsNotAnEmptyList() {
        val (lpa, card) = connected { "BF2D0381017F9000" } // profileInfoListError undefinedError
        assertEquals("The card couldn't list its eSIMs (code 127). Try again.",
            assertThrows(ApduException::class.java) { lpa.getProfiles() }.message)
        assertEquals(0, card.openChannels)
    }

    @Test fun anEmptyListStaysAnEmptyList() {
        for (reply in listOf("BF2D02A000", "BF2D00")) {
            val (lpa, _) = connected { reply + "9000" }
            assertEquals(emptyList<EuiccProfile>(), lpa.getProfiles())
        }
    }

    @Test fun enableSendsProfileIdAndRefreshFlag() {
        val (lpa, card) = connected(reply = onCard { "BF31038001009000" })
        lpa.enable(esim)
        assertEquals("BF3117A0124F10${aid}8101FF", data(card.sent().single()))
    }

    @Test fun aReplyLostToTheCardRestartingIsLeftToTheReloadBothWays() {
        // Android's answer when a transmit fails: the caller's re-read of the list says what happened.
        val (on, card) = connected(reply = onCard { "6F00" })
        on.enable(esim)
        assertEquals(0, card.openChannels)
        val (off, card2) = connected(reply = onCard { "6F00" })
        off.disable(esim)
        assertEquals(0, card2.openChannels)
        // The helper's way of saying the same.
        val (dropped, _) = connected(reply = onCard { throw ApduException("Card connection lost or timed out.") })
        dropped.disable(esim)
    }

    @Test fun whatTheCardRefusesIsStillReportedBothWays() {
        val (off, _) = connected(reply = onCard { "BF32038001029000" })
        assertEquals("That eSIM is already off.", assertThrows(ApduException::class.java) { off.disable(esim) }.message)
        val (rules, _) = connected(reply = onCard { "BF32038001039000" })
        assertEquals("This eSIM's own rules don't allow this.", assertThrows(ApduException::class.java) { rules.disable(esim) }.message)
        val (on, _) = connected(reply = onCard { "BF31038001029000" })
        assertEquals("That eSIM is already on.", assertThrows(ApduException::class.java) { on.enable(esim) }.message)
    }

    @Test fun anotherEsimAtTheSameAidIsNeverTouched() {
        // The list on screen came from card A; card B numbers its own eSIMs, so the AID means another one there.
        val b = listWith(swapped = "98103254769810325476")
        for (act in listOf<Lpa.() -> Unit>({ delete(esim) }, { enable(esim) }, { disable(esim) })) {
            val (lpa, card) = connected(reply = onCard(b) { error("must not reach the card: $it") })
            assertEquals(Lpa.GONE, assertThrows(ApduException::class.java) { lpa.act() }.message)
            assertTrue(card.sent().isEmpty())
            assertEquals(0, card.openChannels)
        }
    }

    @Test fun theSameEsimUnderAnotherAidIsRefusedToo() {
        val moved = listWith(atAid = aid.dropLast(4) + "1100")
        val (lpa, card) = connected(reply = onCard(moved) { error("must not reach the card: $it") })
        assertEquals(Lpa.GONE, assertThrows(ApduException::class.java) { lpa.delete(esim) }.message)
        assertTrue(card.sent().isEmpty())
    }

    @Test fun deleteStillGoesByAidOnOneChannel() {
        val (lpa, card) = connected(reply = onCard { "BF33038001009000" })
        lpa.delete(esim)
        assertEquals("BF33124F10$aid", data(card.sent().single()))
        assertEquals(2, card.apdus.size)
        assertEquals(1, card.opens)
    }

    @Test fun anEsimWithoutAnIccidGoesByItsAidAlone() {
        val list = Hex.encode(Tlv.build(0xBF2D, Tlv.buildSeq(0xA0, Tlv.buildSeq(0xE3, Tlv.build(0x4F, Hex.decode(aid)))))) + "9000"
        val (lpa, card) = connected(reply = onCard(list) { "BF33038001009000" })
        lpa.delete(esim.copy(iccid = ""))
        assertEquals(1, card.sent().size)
    }

    @Test fun aFailedCheckBeforeSwitchingIsNotTakenForTheCardRestarting() {
        // setState forgives a lost reply to the switch itself, which can have gone through. Nothing was sent here.
        for (act in listOf<Lpa.() -> Unit>({ enable(esim) }, { disable(esim) })) {
            val (lpa, card) = connected(reply = onCard("6985") { error("must not reach the card: $it") })
            assertThrows(ApduException::class.java) { lpa.act() }
            assertTrue(card.sent().isEmpty())
        }
    }

    @Test fun anInstallStopsBeforeTheProviderWhenAnotherCardIsIn() {
        var inSlot = eid
        val card = FakeCard { if (it.endsWith("BF3E035C015A")) "BF3E125A10${inSlot}9000" else error("must not go on: $it") }
        val lpa = Lpa(card)
        assertEquals(eid, lpa.discover())
        inSlot = "89049032000000000000000000099999"
        val e = assertThrows(ApduException::class.java) {
            lpa.downloadProfile(ActivationCode("smdp.invalid", "ABC"), null) { _, _ -> }
        }
        assertEquals(Lpa.OTHER_CARD, e.message)
    }

    @Test fun setNicknameSendsPaddedIccidAndUtf8Name() {
        val (lpa, card) = connected { "BF29038001009000" }
        lpa.setNickname("8944478600000000037", " Saily ")
        assertEquals("BF29135A0A984474680000000030F790055361696C79", data(card.apdus.single()))
        assertEquals(0, card.openChannels)
    }

    @Test fun cardInfoIsReadFromEuiccInfo2() {
        // BF22 { 81 profileVersion, 82 svn, 83 firmware, 84 extCardResource { 81 apps=3, 82 free NVM=0x064000,
        // 83 free RAM=0x1000 }, A9 CI key IDs { GSMA RSP2 Root CI1, another } }
        val ext = Tlv.build(0x81, byteArrayOf(3)) + Tlv.build(0x82, Hex.decode("064000")) + Tlv.build(0x83, Hex.decode("1000"))
        val gsma = "81370F5125D0B1D408D4C3B232E6D25E795BEBFB"
        val other = "0B1359633D2AB469761CA9D82B2229350DB722D7"
        val info2 = Tlv.buildSeq(0xBF22, Tlv.build(0x81, Hex.decode("020301")), Tlv.build(0x82, Hex.decode("020202")),
            Tlv.build(0x83, Hex.decode("040200")), Tlv.build(0x84, ext),
            Tlv.buildSeq(0xA9, Tlv.build(0x04, Hex.decode(gsma)), Tlv.build(0x04, Hex.decode(other))))
        val (lpa, card) = connected { Hex.encode(info2) + "9000" }
        val info = lpa.cardInfo()
        assertEquals(Lpa.CardInfo(freeBytes = 409600, installedApps = 3, standard = "2.2.2", firmware = "4.2.0",
            roots = listOf(gsma, other)), info)
        assertEquals(listOf("GSMA", "Unknown 0B135963"), info.rootNames)
        assertEquals("BF2200", data(card.apdus.single()))
        assertEquals(0, card.openChannels)
    }

    @Test fun cardInfoIsUnknownWhenTheCardDoesNotSayIt() {
        val (lpa, _) = connected { Hex.encode(Tlv.buildSeq(0xBF22, Tlv.build(0x81, Hex.decode("020300")))) + "9000" }
        assertEquals(Lpa.CardInfo(freeBytes = null, installedApps = null), lpa.cardInfo())
    }

    @Test fun setNicknameRejectsNamesTheCardCannotStore() {
        val (lpa, card) = connected { error("must not reach the card") }
        assertThrows(ApduException::class.java) { lpa.setNickname("8944478600000000037", "é".repeat(33)) }
        assertTrue(card.apdus.isEmpty())
    }

    @Test fun cardResultCodesBecomeReadableErrors() {
        val (lpa, _) = connected(reply = onCard { "BF33038001029000" })
        val e = assertThrows(ApduException::class.java) { lpa.delete(esim) }
        assertEquals("Turn the eSIM off before deleting it.", e.message)
    }

    @Test fun eachOperationExplainsTheCardsResultCodes() {
        val rules = "This eSIM's own rules don't allow this."
        val busy = "The card is busy. Try again in a moment."
        val ops = listOf<Triple<String, Lpa.() -> Unit, Map<Int, String>>>(
            Triple("BF31", { enable(esim) }, mapOf(2 to "That eSIM is already on.", 9 to "The card refused to turn on the eSIM (code 9).")),
            Triple("BF32", { disable(esim) }, mapOf(2 to "That eSIM is already off.", 9 to "The card refused to turn off the eSIM (code 9).")),
            Triple("BF33", { delete(esim) }, mapOf(2 to "Turn the eSIM off before deleting it.", 9 to "The card refused to delete the eSIM (code 9).")),
            Triple("BF29", { setNickname(esim.iccid, "Trip") }, mapOf(2 to "The card refused to rename the eSIM (code 2).",
                9 to "The card refused to rename the eSIM (code 9).")))
        for ((tag, act, own) in ops) for ((code, words) in own + mapOf(1 to Lpa.GONE, 3 to rules, 5 to busy)) {
            val (lpa, _) = connected(reply = onCard { "%s0380010%X9000".format(tag, code) })
            assertEquals("$tag $code", words, assertThrows(ApduException::class.java) { lpa.act() }.message)
        }
    }

    @Test fun installResultsAreExplained() {
        fun result(outcome: ByteArray) = Tlv.buildSeq(0xBF37, Tlv.buildSeq(0xBF27, Tlv.buildSeq(0xA2, outcome)))
        fun failed(vararg reason: Byte) = result(Tlv.buildSeq(0xA1, Tlv.build(0x80, byteArrayOf(4)),
            if (reason.isEmpty()) ByteArray(0) else Tlv.build(0x81, reason)))
        val (lpa, _) = connected { error("must not reach the card") }
        fun message(r: ByteArray) = assertThrows(ApduException::class.java) { lpa.verifyInstall(r) }.message!!
        lpa.verifyInstall(result(Tlv.build(0xA0, ByteArray(0)))) // successResult
        assertEquals("This eSIM is already on the card.", message(failed(9)))
        assertEquals("The install was interrupted. Try again.", message(failed(11)))
        assertTrue(message(failed(12)), message(failed(12)).startsWith("The card couldn't install this eSIM (error 12). "))
        assertTrue(message(failed()), message(failed()).startsWith("The card couldn't install this eSIM (error unknown). "))
        assertTrue(message(ByteArray(0)), message(ByteArray(0)).startsWith("The card didn't say whether the install worked"))
    }

    @Test fun aPackageTheCardNeverAnswersLeavesTheInstallUnknown() {
        val (lpa, _) = connected { "9000" }
        val e = assertThrows(ApduException::class.java) { lpa.loadBpp(1, eightSegmentBpp()) }
        assertTrue(e.message!!, e.message!!.startsWith("The card didn't say whether the install worked"))
    }

    @Test fun missingConfirmationCodeFailsBeforeAnyCardOrNetworkTraffic() {
        val (lpa, card) = connected { error("must not reach the card") }
        val ac = ActivationCode("smdp.example.com", "ABC", ccRequired = true)
        assertThrows(ConfirmationCodeNeededException::class.java) { lpa.downloadProfile(ac, null) { _, _ -> } }
        assertTrue(card.apdus.isEmpty())
    }

    @Test fun aCardWithoutRoomSaysSoInPlainWords() {
        val a1 = Tlv.buildSeq(0xA1, Tlv.build(0x88, ByteArray(2)))
        val bpp = Tlv.buildSeq(0xBF36, Tlv.build(0xBF23, ByteArray(4)), Tlv.build(0xA0, ByteArray(3)), a1,
            Tlv.buildSeq(0xA3, Tlv.build(0x86, ByteArray(5))))
        // Refused at the data block (status 6A84 "not enough memory space")...
        val (lpa, _) = connected { "6A84" }
        assertEquals(Lpa.CARD_FULL, assertThrows(ApduException::class.java) { lpa.loadBpp(1, bpp) }.message)
        // ...or in the install result: errorResult { bppCommandId 81, errorReason 81 = 10 }.
        val result = Tlv.buildSeq(0xBF37, Tlv.buildSeq(0xBF27, Tlv.buildSeq(0xA2, Tlv.buildSeq(0xA1,
            Tlv.build(0x80, byteArrayOf(4)), Tlv.build(0x81, byteArrayOf(10))))))
        val (lpa2, _) = connected { Hex.encode(result) + "9000" }
        val installResult = lpa2.loadBpp(1, bpp)
        assertEquals(Lpa.CARD_FULL, assertThrows(ApduException::class.java) { lpa2.verifyInstall(installResult) }.message)
    }

    @Test fun boundProfilePackageIsSegmentedLikeLpac() {
        val bf23 = Tlv.build(0xBF23, ByteArray(4))
        val a0 = Tlv.build(0xA0, Tlv.build(0x87, ByteArray(3)))
        val a1 = Tlv.buildSeq(0xA1, Tlv.build(0x88, ByteArray(2)), Tlv.build(0x88, ByteArray(2)))
        val a3 = Tlv.buildSeq(0xA3, Tlv.build(0x86, ByteArray(5)), Tlv.build(0x86, ByteArray(5)))
        val bpp = Tlv.buildSeq(0xBF36, bf23, a0, a1, a3)
        var n = 0
        val (lpa, card) = connected { if (++n == 8) "BF37009000" else "9000" }

        val progress = ArrayList<Float>()
        assertEquals("BF3700", Hex.encode(lpa.loadBpp(1, bpp) { progress += it }))

        val segs = card.apdus.map(::data)
        assertEquals(8, segs.size) // BF36+BF23, A0, A1 header, 2x 88, A3 header, 2x 86
        assertEquals("BF3628" + Hex.encode(bf23), segs[0])
        assertEquals(Hex.encode(a0), segs[1])
        assertEquals("A108", segs[2])
        assertEquals("A30E", segs[5])
        assertEquals(Hex.encode(bpp), segs.joinToString("")) // every byte once, in order
        assertEquals((1..7).map { it / 8f }, progress)
    }

    private val aSim = "98103254769810325476"
    private val otherSim = "98214365879021436587"

    private fun noticeMeta(seq: String, event: String, iccid: String? = aSim, address: String = "smdp.example.com") =
        Tlv.buildSeq(0xBF2F, Tlv.build(0x80, Hex.decode(seq)), Tlv.build(0x81, Hex.decode(event)),
            Tlv.build(0x0C, address.toByteArray()), iccid?.let { Tlv.build(0x5A, Hex.decode(it)) } ?: ByteArray(0))

    /** Connected to a card holding [metas], in that order; each is also its signed notice, as far as these tests go. */
    private fun holding(vararg metas: ByteArray): Pair<Lpa, FakeCard> {
        val list = Tlv.buildSeq(0xBF28, Tlv.buildSeq(0xA0, *metas))
        val stored = metas.associateBy { Hex.encode(Tlv.parse(it)!!.find(0x80)!!.value) }
        return connected {
            val seq = Tlv.parse(Hex.decode(data(it)))?.find(0x80)?.value?.let(Hex::encode)
            when (data(it).take(4)) {
                "BF28" -> Hex.encode(list) + "9000"
                "BF2B" -> Hex.encode(Tlv.buildSeq(0xBF2B, Tlv.buildSeq(0xA0, stored.getValue(seq!!)))) + "9000"
                "BF30" -> "BF30038001009000"
                else -> error("unexpected $it")
            }
        }
    }

    private fun FakeCard.cleared() = apdus.map(::data).filter { it.startsWith("BF30") }

    // Event bits: 0780 install, 0640 enable, 0520 disable, 0410 delete. Sequence 200 needs a leading zero byte.

    @Test fun switchNoticesAreSentLikeInstallsAndDeletesOldestFirst() {
        val enable = noticeMeta("05", "0640")
        val delete = noticeMeta("06", "0410")
        val install = noticeMeta("00C8", "0780")
        val (lpa, card) = holding(enable, delete, install)

        val pending = lpa.takeNotifications()
        assertEquals(listOf(Lpa.Event.ENABLE, Lpa.Event.DELETE, Lpa.Event.INSTALL), pending.map { it.event })
        assertEquals(listOf(5, 6, 200), pending.map { it.seq })
        assertEquals(listOf(enable, delete, install).map { Es9Client.b64(it) }, pending.map { it.signed })
        assertEquals("smdp.example.com", pending[0].address)
        assertEquals("89012345678901234567", pending[0].iccid)
        assertTrue(card.cleared().isEmpty()) // only once they've been delivered
        assertEquals(0, card.openChannels)
    }

    @Test fun onlyTheNewestSwitchNoticeOfAnEsimIsKept() {
        // Listed out of order: the sequence numbers say which is newest. Each eSIM has its own.
        val (lpa, card) = holding(noticeMeta("03", "0640"), noticeMeta("01", "0640"), noticeMeta("02", "0520"),
            noticeMeta("04", "0520", otherSim), noticeMeta("05", "0640", otherSim))
        assertEquals(listOf(3, 5), lpa.takeNotifications().map { it.seq })
        assertEquals(listOf("BF3003800101", "BF3003800102", "BF3003800104"), card.cleared())
        assertEquals(0, card.openChannels)
    }

    @Test fun installAndDeleteNoticesAreAllKept() {
        val (lpa, card) = holding(noticeMeta("01", "0780"), noticeMeta("02", "0640"), noticeMeta("03", "0410"),
            noticeMeta("04", "0780"), noticeMeta("05", "0640"), noticeMeta("06", "0410"))
        assertEquals(listOf(1, 3, 4, 5, 6), lpa.takeNotifications().map { it.seq })
        assertEquals(listOf("BF3003800102"), card.cleared())
    }

    @Test fun switchNoticesWithNoIccidCantPileUpEither() {
        // A provider that has shut down never takes them, and each switch adds two.
        val (lpa, card) = holding(noticeMeta("01", "0640", iccid = null), noticeMeta("02", "0520", iccid = null),
            noticeMeta("03", "0640", iccid = null), noticeMeta("04", "0520", iccid = null),
            noticeMeta("05", "0640", iccid = null))
        assertEquals(listOf(4, 5), lpa.takeNotifications().map { it.seq })
        assertEquals(listOf("BF3003800101", "BF3003800102", "BF3003800103"), card.cleared())
        assertEquals(0, card.openChannels)
    }

    @Test fun switchNoticesWithNoIccidAreKeptForEachProvider() {
        val (lpa, card) = holding(noticeMeta("01", "0640", iccid = null), noticeMeta("02", "0640", iccid = null, address = "other.example.com"))
        assertEquals(listOf(1, 2), lpa.takeNotifications().map { it.seq })
        assertTrue(card.cleared().isEmpty())
    }

    @Test fun anEmptyIccidCountsAsNone() {
        // All filler digits: read as "", which isn't the same eSIM as another's.
        val (lpa, _) = holding(noticeMeta("01", "0640", iccid = "FFFFFFFFFFFFFFFFFFFF"),
            noticeMeta("02", "0640", iccid = "FFFFFFFFFFFFFFFFFFFF", address = "other.example.com"))
        assertEquals(listOf(1, 2), lpa.takeNotifications().map { it.seq })
    }

    @Test fun switchNoticesWithAnIccidAreNotBoundByTheOnesWithout() {
        val (lpa, card) = holding(noticeMeta("01", "0640"), noticeMeta("02", "0640", iccid = null), noticeMeta("03", "0640", otherSim))
        assertEquals(listOf(1, 2, 3), lpa.takeNotifications().map { it.seq })
        assertTrue(card.cleared().isEmpty())
    }

    @Test fun installAndDeleteNoticesWithNoIccidAreAllKept() {
        val (lpa, card) = holding(noticeMeta("01", "0780", iccid = null), noticeMeta("02", "0780", iccid = null),
            noticeMeta("03", "0410", iccid = null), noticeMeta("04", "0410", iccid = null))
        assertEquals(listOf(1, 2, 3, 4), lpa.takeNotifications().map { it.seq })
        assertTrue(card.cleared().isEmpty())
    }

    @Test fun deliveredNoticesAreClearedBySequenceNumber() {
        val (lpa, card) = connected { "BF30038001009000" }
        lpa.removeNotifications(listOf(6, 200))
        assertEquals(listOf("BF3003800106", "BF3004800200C8"), card.apdus.map(::data))
        assertEquals(0, card.openChannels)
    }

    @Test fun noticesWithNothingToSendCountAsDelivered() {
        val (lpa, _) = connected { error("must not reach the card") }
        assertTrue(lpa.deliver(Lpa.Notification(1, Lpa.Event.DELETE, "unreachable.invalid", null)))
    }
}
