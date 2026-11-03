package com.esimonsim.lpa

import com.esimonsim.adb.CardShell
import com.esimonsim.apdu.ApduBackend
import com.esimonsim.apdu.ApduException
import com.esimonsim.apdu.FakeCard
import com.esimonsim.apdu.FakeReaders
import com.esimonsim.apdu.OmapiApduBackend
import com.esimonsim.apdu.OpenResult
import com.esimonsim.apdu.SlotInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Several cards in one phone: looking through the slots, and an object per card. */
class ScanTest {
    private val eid0 = "89049032000000000000000000012345"
    private val eid1 = "89049032000000000000000000013456"
    private val usual = Lpa.ISD_R_OPEN
    private val maker = CardShell.ISD_R_AIDS[2]

    private class Esim(val eid: String, val aid: String = Lpa.ISD_R_OPEN, val name: String = "Roamless")

    /** Cards by slot. An empty slot answers [emptyStatus]; a card asked for an ID it doesn't have, 3. */
    private class Phone(val cards: Map<Int, Esim>, var slots: List<SlotInfo> = emptyList(),
                        val emptyStatus: (Int) -> Int = { 2 }) : ApduBackend {
        val opened = ArrayList<Pair<Int, String>>()
        var openChannels = 0
        var ready = true
        /** Opening a channel here takes card access down. */
        var hangsAt = -1
        /** Reading the list of the card here takes card access down. */
        var listHangsAt = -1
        var slotsAsked = 0
        private val at = HashMap<Int, Int>()
        private var next = 1
        override fun isReady() = ready
        override fun openChannel(slot: Int, port: Int, aidHex: String): OpenResult {
            opened += slot to aidHex
            if (slot == hangsAt) { ready = false; throw ApduException("Card connection lost or timed out.") }
            val card = cards[slot] ?: return OpenResult(-1, emptyStatus(slot), null)
            if (card.aid != aidHex) return OpenResult(-1, 3, null)
            openChannels++
            return OpenResult(next++, 0, null).also { at[it.channel] = slot }
        }
        override fun transmit(slot: Int, port: Int, channel: Int, cla: Int, ins: Int, p1: Int, p2: Int, p3: Int, dataHex: String): String {
            assertEquals(at[channel], slot)
            if (slot == listHangsAt && dataHex.startsWith("BF2D")) { ready = false; throw ApduException("timed out") }
            val card = cards.getValue(slot)
            return when {
                dataHex == "BF3E035C015A" -> "BF3E125A10${card.eid}9000"
                dataHex.startsWith("BF2D") -> Hex.encode(Tlv.build(0xBF2D, Tlv.buildSeq(0xA0, Tlv.buildSeq(0xE3,
                    Tlv.build(0x5A, Hex.decode("984474680000000030F7")), Tlv.build(0x4F, Hex.decode("A0000005591010FFFFFFFF8900001000")),
                    Tlv.build(0x9F70, byteArrayOf(1)), Tlv.build(0x92, card.name.toByteArray()))))) + "9000"
                else -> "9000"
            }
        }
        override fun closeChannel(slot: Int, port: Int, channel: Int) { openChannels-- }
        override fun slots(): List<SlotInfo> { slotsAsked++; return slots }
    }

    private fun slot(euicc: Boolean = true, removable: Boolean = true, present: Boolean? = true, active: Boolean? = true,
                     logical: Int? = null, eid: String? = null, token: String? = "t") =
        SlotInfo(euicc, removable, present, active, logical, eid, token)

    private fun List<SlotLook>.at(slot: Int) = first { it.slot == slot }

    @Test fun anotherCardIsFoundWithItsEsimsAndTheOneInViewIsLeftAlone() {
        val phone = Phone(mapOf(0 to Esim(eid0, name = "Airalo"), 1 to Esim(eid1)))
        val looks = Lpa(phone).scan(inView = 1)
        val other = looks.at(0)
        assertEquals(SlotLook.What.CARD, other.what)
        assertEquals(Card(eid0, 0, usual, false), other.card)
        assertEquals("Airalo", other.profiles!!.single().displayName)
        assertEquals(SlotLook.What.SAME, looks.at(1).what)
        assertEquals(SlotLook.What.EMPTY, looks.at(2).what)
        // Nothing opened where the card in view is, and with no list to go by, only the usual ID.
        assertTrue(phone.opened.none { it.first == 1 })
        assertTrue(phone.opened.all { it.second == usual })
        assertEquals(0, phone.openChannels)
    }

    @Test fun aSlotWhoseCardAndroidStillSeesTheSameCostsTheCardNothing() {
        val phone = Phone(mapOf(0 to Esim(eid0)), listOf(slot(token = "a"), slot(token = "b")))
        val looks = Lpa(phone).scan(inView = 1, seen = mapOf(0 to "a"))
        assertEquals(SlotLook.What.SAME, looks.at(0).what)
        assertTrue(phone.opened.isEmpty())
        // A new token there is a card to look at.
        assertEquals(SlotLook.What.CARD, Lpa(phone).scan(inView = 1, seen = mapOf(0 to "old")).at(0).what)
    }

    @Test fun slotsThePhoneIsSureOfAreNotOpened() {
        val phone = Phone(mapOf(0 to Esim(eid0), 1 to Esim(eid1), 2 to Esim(eid1)),
            listOf(slot(present = false), slot(active = false), slot(euicc = false, active = false), slot()))
        val looks = Lpa(phone).scan(inView = 3)
        assertEquals(SlotLook.What.EMPTY, looks.at(0).what)
        // An eSIM card in a slot the phone isn't using: worth a word, though it can't be opened.
        assertEquals(SlotLook.What.IDLE, looks.at(1).what)
        assertEquals(SlotLook.What.EMPTY, looks.at(2).what)
        assertTrue(phone.opened.isEmpty())
    }

    @Test fun whatAndroidDoesntSayIsOpenedAsBefore() {
        // A slot that's just {} in the helper's list: no field means "doesn't say", never "no".
        val phone = Phone(mapOf(0 to Esim(eid0)), listOf(SlotInfo(euicc = false, removable = true), slot()))
        assertEquals(SlotLook.What.CARD, Lpa(phone).scan(inView = 1).at(0).what)
    }

    @Test fun thePhonesOwnChipStaysOutUntilPeopleSayItsTheirs() {
        // An eSIM phone, its chip in slot 0, which Android may know the EID of.
        val chip = slot(removable = false, eid = eid0)
        val phone = Phone(mapOf(0 to Esim(eid0), 1 to Esim(eid1)), listOf(chip, slot()))
        assertEquals(SlotLook.What.EMPTY, Lpa(phone, phoneHasEsim = true).scan(inView = 1).at(0).what)
        assertTrue(phone.opened.isEmpty())
        val mine = Lpa(phone, phoneHasEsim = true).scan(inView = 1, own = setOf(eid0)).at(0)
        assertEquals(SlotLook.What.CARD, mine.what)
        assertTrue(mine.card!!.builtIn)
        // Without the feature, a tray called fixed is just a tray: the POCO calls its card's that.
        assertFalse(Lpa(phone).scan(inView = 1).at(0).card!!.builtIn)
    }

    @Test fun aChipAndroidHasNoEidForIsOnlyReadForItsEidAndOnlyIfSomeoneSaidAChipIsTheirs() {
        val phone = Phone(mapOf(0 to Esim(eid0), 1 to Esim(eid1)), listOf(slot(removable = false), slot()))
        assertEquals(SlotLook.What.EMPTY, Lpa(phone, phoneHasEsim = true).scan(inView = 1).at(0).what)
        assertTrue(phone.opened.isEmpty())
        // Someone's chip, but not this one: nothing of it is listed.
        assertEquals(SlotLook.What.EMPTY, Lpa(phone, phoneHasEsim = true).scan(inView = 1, own = setOf(eid1)).at(0).what)
        assertEquals(1, phone.opened.size)
    }

    @Test fun anInactiveChipIsNoCardToSwitchTo() {
        // Single-SIM mode with the tray in use: the parked chip mustn't give one-card people a switcher.
        val phone = Phone(mapOf(1 to Esim(eid1)), listOf(slot(removable = false, active = false), slot()))
        assertEquals(SlotLook.What.EMPTY, Lpa(phone, phoneHasEsim = true).scan(inView = 1).at(0).what)
    }

    @Test fun anOrdinarySimIsAskedForEveryIdOnceThenLeftAlone() {
        val phone = Phone(mapOf(1 to Esim(eid1)), listOf(slot(euicc = false, token = "sim"), slot()), emptyStatus = { 3 })
        val look = Lpa(phone).scan(inView = 1).at(0)
        assertEquals(SlotLook.What.EMPTY, look.what)
        assertTrue(look.noManager)
        assertEquals(CardShell.ISD_R_AIDS, phone.opened.map { it.second })
        phone.opened.clear()
        assertEquals(SlotLook.What.EMPTY, Lpa(phone).scan(inView = 1, ordinary = { s, t -> s == 0 && t == "sim" }).at(0).what)
        assertTrue(phone.opened.isEmpty())
    }

    @Test fun onlyNoSuchAppletSaysThereIsNoEsimManager() {
        val phone = Phone(mapOf(1 to Esim(eid1)), listOf(slot(euicc = false, present = null, active = null), slot()),
            emptyStatus = { 4 })
        assertFalse(Lpa(phone).scan(inView = 1).at(0).noManager)
    }

    @Test fun aMakersCardIsFoundUnderItsOwnIdAndKeepsIt() {
        val phone = Phone(mapOf(0 to Esim(eid0, aid = maker), 1 to Esim(eid1)), listOf(slot(), slot()))
        val found = Lpa(phone).scan(inView = 1).at(0).card!!
        assertEquals(maker, found.isdR)
        phone.opened.clear()
        Lpa(phone).at(found).getProfiles()
        assertEquals(listOf(0 to maker), phone.opened)
    }

    @Test fun aCardWithNoFreeChannelIsAskedAgainNextTime() {
        val phone = Phone(mapOf(1 to Esim(eid1)), listOf(slot(), slot()))
        assertEquals(SlotLook.What.AGAIN, Lpa(phone).scan(inView = 1).at(0).what)
        // With nothing to say there is a card, status 2 is just an empty slot.
        phone.slots = emptyList()
        assertEquals(SlotLook.What.EMPTY, Lpa(phone).scan(inView = 1).at(0).what)
    }

    @Test fun aSlotThatTakesCardAccessDownEndsTheLook() {
        val phone = Phone(mapOf(1 to Esim(eid1)), listOf(slot(), slot(), slot(), slot()))
        phone.hangsAt = 0
        val looks = Lpa(phone).scan(inView = 1)
        assertEquals(SlotLook.What.HUNG, looks.at(0).what)
        assertTrue(looks.none { it.slot >= 2 })
        // Next time it's skipped.
        phone.ready = true
        phone.opened.clear()
        phone.slots = listOf(slot(), slot(), slot(), slot())
        assertEquals(SlotLook.What.AGAIN, Lpa(phone).scan(inView = 1, skip = setOf(0)).at(0).what)
        assertTrue(phone.opened.none { it.first == 0 })
    }

    @Test fun aQuickLookOnlyGoesWhereAndroidSaysSomethingChanged() {
        val phone = Phone(mapOf(0 to Esim(eid0), 1 to Esim(eid1)))
        assertTrue(Lpa(phone).scan(inView = 1, quick = true).all { it.what == SlotLook.What.SAME || it.what == SlotLook.What.AGAIN })
        assertTrue(phone.opened.isEmpty())
        // What Android is sure of counts even then: a card taken out isn't offered any more.
        phone.slots = listOf(slot(present = false, token = null), slot())
        assertEquals(SlotLook.What.EMPTY, Lpa(phone).scan(inView = 1, quick = true).at(0).what)
        // And a slot that took card access down is still one the phone says is empty.
        assertEquals(SlotLook.What.EMPTY, Lpa(phone).scan(inView = 1, skip = setOf(0)).at(0).what)
    }

    @Test fun theSlotWhoseListHungIsTheOneToBlame() {
        // Its list is read quietly, as a card that won't list is still a card: the slot is blamed all the same.
        val phone = Phone(mapOf(0 to Esim(eid0), 2 to Esim(eid1)), listOf(slot(), slot(), slot()))
        phone.listHangsAt = 0
        val looks = Lpa(phone).scan(inView = 1)
        assertEquals(SlotLook.What.HUNG, looks.at(0).what)
        assertTrue(looks.none { it.slot == 2 })
    }

    @Test fun aSlotTurnedOnIsLookedBehindThoughItsTokenStayed() {
        val phone = Phone(mapOf(0 to Esim(eid0), 1 to Esim(eid1)), listOf(slot(active = false, token = "t"), slot()))
        val off = Lpa(phone).scan(inView = 1)
        assertEquals(SlotLook.What.IDLE, off.at(0).what)
        assertNull(off.at(0).token) // decided by Android's list: nothing to skip it by next time
        phone.slots = listOf(slot(token = "t"), slot())
        assertEquals(SlotLook.What.CARD, Lpa(phone).scan(inView = 1, seen = com.esimonsim.ui.seenAfter(emptyMap(), off)).at(0).what)
    }

    @Test fun cardAccessThatWentElsewhereBlamesNoSlot() {
        val phone = Phone(mapOf(0 to Esim(eid0), 1 to Esim(eid1)), listOf(slot(), slot(), slot()))
        phone.ready = false
        val looks = Lpa(phone).scan(inView = 1)
        assertTrue(looks.none { it.what == SlotLook.What.HUNG })
        assertTrue(phone.opened.isEmpty())
    }

    @Test fun androidSixHasOnlyOneCardToReach() {
        val phone = Phone(mapOf(0 to Esim(eid0)))
        assertTrue(Lpa(phone, slotReach = { SlotReach.FIRST_ONLY }).scan(inView = 0).isEmpty())
        assertEquals(0, phone.slotsAsked)
    }

    @Test fun aLookCanBeStoppedBetweenSlots() {
        val phone = Phone(mapOf(0 to Esim(eid0), 1 to Esim(eid1)))
        var left = 1
        assertEquals(1, Lpa(phone).scan(inView = 3) { left-- > 0 }.size)
    }

    @Test fun aCardsObjectWorksOnThatCardWhateverElseIsFound() {
        val phone = Phone(mapOf(0 to Esim(eid0, name = "Airalo"), 1 to Esim(eid1)))
        val first = Lpa(phone)
        first.slotHint = 1
        assertEquals(eid1, first.discover())
        val other = first.at(Card(eid0, 0, usual, false))
        phone.opened.clear()
        assertEquals("Airalo", other.read().single().displayName)
        assertEquals(listOf(0 to usual), phone.opened) // one channel: the EID, then the list
        assertEquals(1, first.slot)
        assertEquals(Card(eid1, 1, usual, false), first.card)
    }

    @Test fun readingAChosenCardNeverShowsAnotherOne() {
        val phone = Phone(mapOf(0 to Esim(eid1)))
        val chosen = Lpa(phone).at(Card(eid0, 0, usual, false))
        assertEquals(Lpa.OTHER_CARD, assertThrows(ApduException::class.java) { chosen.read() }.message)
        assertEquals(0, phone.openChannels)
    }

    @Test fun anotherLookStartsWhereTheCardWasUnlessAccessChanged() {
        val phone = Phone(mapOf(0 to Esim(eid0), 1 to Esim(eid1)))
        val lpa = Lpa(phone)
        lpa.slotHint = 1
        lpa.discover()
        phone.opened.clear()
        lpa.again().discover()
        assertEquals(1, phone.opened.first().first)
        // Without its slot, the hint decides: the card someone chose, say.
        val fresh = lpa.again(sameSlot = false)
        fresh.slotHint = 0
        assertEquals(eid0, fresh.discover())
    }

    @Test fun theChipScreenKnowsOfACardInASlotThePhoneIsntUsing() {
        val phone = Phone(mapOf(0 to Esim(eid0)), listOf(slot(removable = false), slot(active = false), slot(euicc = false, active = false)))
        val lpa = Lpa(phone, phoneHasEsim = true)
        lpa.discover()
        assertTrue(lpa.onBuiltInSlot)
        assertEquals(1, lpa.idleSlot)
        // Nothing to say without a list, or with only an ordinary SIM unused.
        phone.slots = listOf(slot(removable = false), slot(), slot(euicc = false, active = false))
        val other = Lpa(phone, phoneHasEsim = true)
        other.discover()
        assertEquals(-1, other.idleSlot)
        assertNull(Lpa(phone).card)
    }

    @Test fun onDirectAccessAnEsimCardThatRefusesIsShownForWhatItIs() {
        // A 5ber in SIM 1 and an ordinary SIM in SIM 3, both refusing, beside the card that lets this app in.
        val readers = FakeReaders(mapOf(0 to FakeCard.LOCKED, 1 to FakeCard.OPEN, 2 to FakeCard.REFUSING_SIM),
            atrs = mapOf(0 to "3B9F96803FC7828031E073FE211B57AA8660F001001EE1")) { "BF3E125A10${eid1}9000" }
        val backend = OmapiApduBackend { readers }
        backend.connect()
        val lpa = Lpa(backend, accessRulesApply = { true })
        assertEquals(eid1, lpa.discover())
        val looks = lpa.scan(inView = 1)
        assertEquals(SlotLook.What.LOCKED, looks.at(0).what)
        assertNull(looks.at(0).card)
        assertEquals(SlotLook.What.EMPTY, looks.at(2).what)
        assertEquals(SlotLook.What.EMPTY, looks.at(3).what) // no reader
        // OMAPI says nothing of its slots, so a quick look leaves them all for the next full one.
        assertTrue(lpa.scan(inView = 1, quick = true).filter { it.slot != 1 }.all { it.what == SlotLook.What.AGAIN })
    }
}
