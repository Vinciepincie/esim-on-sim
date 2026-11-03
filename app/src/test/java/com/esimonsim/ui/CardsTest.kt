package com.esimonsim.ui

import com.esimonsim.apdu.AccessMethod
import com.esimonsim.apdu.ShizukuStatus
import com.esimonsim.lpa.Card
import com.esimonsim.lpa.EuiccProfile
import com.esimonsim.lpa.SlotLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The card switcher's rows, and what decides where a refresh or an install goes. */
class CardsTest {
    private val a = "89049032000000000000000000012345"
    private val b = "89049032000000000000000000013456"
    private fun esim(name: String, on: Boolean) = EuiccProfile("89", "A0", on, null, name, null, 2)
    private fun card(eid: String, slot: Int) = SlotLook(slot, SlotLook.What.CARD, Card(eid, slot, "", false), listOf(esim("Airalo", true)))

    @Test fun aLookAddsCardsAndForgetsEmptiedSlots() {
        val rows = rowsAfter(emptyList(), listOf(card(b, 0), SlotLook(1, SlotLook.What.SAME), SlotLook(2, SlotLook.What.IDLE)))
        assertEquals(listOf(CardRow(0, 0, b, "Airalo", true), CardRow(2, 2)), rows)
        assertEquals(listOf(CardRow(2, 2)), rowsAfter(rows, listOf(SlotLook(0, SlotLook.What.EMPTY))))
        // Unchanged or busy: what's known stands.
        assertEquals(rows, rowsAfter(rows, listOf(SlotLook(0, SlotLook.What.AGAIN), SlotLook(2, SlotLook.What.HUNG))))
    }

    @Test fun rowsGoByTheNumberThePhoneUses() {
        val rows = rowsAfter(emptyList(), listOf(SlotLook(1, SlotLook.What.CARD, Card(b, 1, "", false), null, logical = 0),
            SlotLook(0, SlotLook.What.IDLE)))
        assertEquals(listOf(0, 0), rows.map { it.sim })
        assertEquals("Card in SIM 1", rows.first { it.slot == 1 }.title)
        // A card whose list couldn't be read says nothing it doesn't know.
        assertNull(rows.first { it.slot == 1 }.note)
    }

    @Test fun theCardInViewGoesByWhatsOnScreen() {
        val old = listOf(CardRow(0, 0, a, "Firsty", true), CardRow(1, 1, b, "Airalo", true))
        val rows = withView(old, 0, a, listOf(esim("Roamless", true), esim("Firsty", false)))
        assertEquals("Roamless is on", rows.first { it.slot == 0 }.note)
        assertEquals(old[1], rows[1])
        // Moved to another slot: its old row goes.
        assertEquals(listOf(CardRow(1, 1, a, null, true)), withView(listOf(CardRow(0, 0, a)), 1, a, emptyList()))
        assertEquals("No eSIM on", withView(old, 0, a, listOf(esim("Firsty", false)))[0].note)
        assertEquals(old, withView(old, 0, null, emptyList()))
    }

    @Test fun aCardTakenOutAndPutBackIsLookedAtAgain() {
        val seen = seenAfter(emptyMap(), listOf(SlotLook(0, SlotLook.What.CARD, token = "a"), SlotLook(1, SlotLook.What.SAME, token = "b")))
        assertEquals(mapOf(0 to "a"), seen) // the slot in view is never looked behind
        // Out: no token. Back with the same one: not "unchanged", as its row went.
        val out = seenAfter(seen, listOf(SlotLook(0, SlotLook.What.EMPTY)))
        assertEquals(emptyMap<Int, String>(), out)
        // Busy or hung: asked again next time.
        assertEquals(emptyMap<Int, String>(), seenAfter(seen, listOf(SlotLook(0, SlotLook.What.AGAIN, token = "a"))))
        assertEquals(mapOf(0 to "c"), seenAfter(seen, listOf(SlotLook(0, SlotLook.What.EMPTY, token = "c"))))
        assertEquals(seen, seenAfter(seen, listOf(SlotLook(0, SlotLook.What.SAME, token = "a"))))
    }

    @Test fun aSlotThePhoneIsntUsingLeadsToItsSettings() {
        assertEquals("Your phone isn't using its SIM slot. Open SIM settings", CardRow(0, 0).note)
        // Not by a number: in single-SIM mode the card in use is SIM 1, whatever its slot.
        assertEquals("Card not in use", CardRow(0, 0).title)
    }

    @Test fun onlyACardSomeoneChoseFromSeveralStaysPutOnRefresh() {
        assertTrue(refreshStays(a, a, 2))
        assertFalse(refreshStays(a, a, 1)) // one card: looked for as it always was
        assertFalse(refreshStays(a, b, 2)) // shown because the chosen one wasn't there
        assertFalse(refreshStays(null, a, 2))
    }

    @Test fun theFormsCardHasToBeChosenAgainOnceSomethingElseIsInView() {
        // Opened for a, which was taken out: b alone is left, but nobody said b.
        val one = LpaUi(eid = b, slot = 1, cards = listOf(CardRow(1, 1, b)), lookingForCards = false, addTarget = a)
        assertTrue(one.pickCard)
        assertFalse(one.mayInstall)
        assertTrue(one.copy(addTarget = b).mayInstall)
        // While the card in view is still being read, nothing to choose yet.
        assertFalse(one.copy(eid = null, loading = true).pickCard)
    }

    @Test fun withOneCardNothingWaitsForAChoice() {
        val one = LpaUi(eid = a, slot = 1, cards = listOf(CardRow(1, 1, a)), lookingForCards = false)
        assertTrue(one.mayInstall)
        assertFalse(one.switcher)
        assertFalse(one.pickCard)
        // A slot the phone isn't using is a row, but no card to choose between.
        val idle = one.copy(cards = one.cards + CardRow(0, 0))
        assertTrue(idle.switcher)
        assertEquals(1, idle.manageable)
        assertTrue(idle.copy(addTarget = null).mayInstall)
    }

    @Test fun installingWaitsForTheFirstLookAndThenForAChoice() {
        val two = LpaUi(eid = a, slot = 0, cards = listOf(CardRow(0, 0, a), CardRow(1, 1, b)), lookingForCards = false)
        assertFalse(two.copy(lookingForCards = true, addTarget = a).mayInstall)
        assertTrue(two.pickCard)
        assertFalse(two.mayInstall)
        assertTrue(two.copy(addTarget = a).mayInstall)
        assertFalse(two.copy(addTarget = b).mayInstall) // chosen, but another card is in view
        assertFalse(two.copy(addTarget = a, loading = true).mayInstall)
        // Opened in the app: for the card in view.
        assertEquals(a, two.addOpened().addTarget)
        assertNull(two.addOpened(target = null).addTarget)
    }

    @Test fun anEsimCardThatRefusesDirectAccessIsARowToChangeCardAccessFrom() {
        val locked = CardRow(1, 1, locked = true)
        assertEquals("Card in SIM 2", locked.title) // the phone uses that slot, so it has its number
        assertEquals("Doesn't let this app in directly. Change card access", locked.note)
        val inView = CardRow(0, 0, a, "Airalo", true)
        val rows = rowsAfter(listOf(inView), listOf(SlotLook(0, SlotLook.What.SAME), SlotLook(1, SlotLook.What.LOCKED)))
        assertEquals(listOf(inView, locked), rows)
        assertEquals(rows, withView(rows, 0, a, listOf(esim("Airalo", true))))
        // Taken out, it goes; let in another way, it's a card like any other.
        assertEquals(listOf(inView), rowsAfter(rows, listOf(SlotLook(1, SlotLook.What.EMPTY))))
        assertEquals(CardRow(1, 1, b, "Airalo", true), rowsAfter(rows, listOf(card(b, 1)))[1])
        // A row to switch from, but no card to manage: one card, as far as installing goes.
        val ui = LpaUi(eid = a, slot = 0, cards = rows, lookingForCards = false)
        assertEquals(1, ui.manageable)
        assertTrue(ui.switcher)
        assertFalse(ui.pickCard)
        assertTrue(ui.mayInstall)
        assertEquals(listOf(2), ui.lockedSims)
        // Under the Add eSIM form, switching card access would go on under it: no row for that card.
        val adding = ui.copy(adding = true)
        assertFalse(adding.switcher)
        assertEquals(listOf(0), adding.menuRows.map { it.slot })
        assertEquals(listOf(0, 1), ui.menuRows.map { it.slot })
    }

    @Test fun cardAccessOffersTheWaysThisPhoneHas() {
        val all = listOf(AccessMethod.ROOT, AccessMethod.DIRECT, AccessMethod.SHIZUKU, AccessMethod.ADB)
        fun ways(sdk: Int, direct: Boolean = true, root: Boolean = true, shizuku: ShizukuStatus = ShizukuStatus.ALLOWED,
                 method: AccessMethod? = null, preferred: AccessMethod? = null) =
            accessWays(sdk, direct, root, shizuku, method, preferred)
        for (sdk in listOf(28, 29, 30, 37)) assertEquals(all, ways(sdk))
        // No SIM readers for apps before Android 9.
        for (sdk in listOf(23, 27)) assertEquals(all - AccessMethod.DIRECT, ways(sdk))
        // Only what's there, and always ADB: Wireless debugging, or with a computer.
        for (sdk in listOf(23, 27, 28, 29, 30, 37))
            assertEquals(listOf(AccessMethod.ADB), ways(sdk, direct = false, root = false, shizuku = ShizukuStatus.ABSENT))
        assertEquals(listOf(AccessMethod.DIRECT, AccessMethod.ADB), ways(29, root = false, shizuku = ShizukuStatus.ABSENT))
        assertEquals(listOf(AccessMethod.ROOT, AccessMethod.ADB), ways(30, direct = false, shizuku = ShizukuStatus.ABSENT))
        // Shizuku that's installed is there to choose, running or not.
        for (s in listOf(ShizukuStatus.STOPPED, ShizukuStatus.NOT_ASKED, ShizukuStatus.DECLINED))
            assertEquals(all, ways(37, shizuku = s))
        // The way in use or chosen stays, so the setting can show it, also once it's gone.
        for (w in all - AccessMethod.ADB) {
            val expected = listOf(w, AccessMethod.ADB)
            assertEquals(expected, ways(30, direct = false, root = false, shizuku = ShizukuStatus.ABSENT, method = w))
            assertEquals(expected, ways(30, direct = false, root = false, shizuku = ShizukuStatus.ABSENT, preferred = w))
        }
    }

    @Test fun waysGoByTheirNames() {
        assertEquals("Automatic", wayLabel(null, 37))
        assertEquals(listOf("Root", "Direct", "Shizuku", "Wireless debugging"),
            listOf(AccessMethod.ROOT, AccessMethod.DIRECT, AccessMethod.SHIZUKU, AccessMethod.ADB).map { wayLabel(it, 30) })
        assertEquals("USB debugging", wayLabel(AccessMethod.ADB, 29))
        assertEquals("direct access", wayName(AccessMethod.DIRECT, 30, background = false))
        assertEquals("root", wayName(AccessMethod.ROOT, 30, background = false))
        assertEquals("Shizuku", wayName(AccessMethod.SHIZUKU, 30, background = false))
        assertEquals("Wireless debugging", wayName(AccessMethod.ADB, 30, background = false))
        assertEquals("USB debugging", wayName(AccessMethod.ADB, 29, background = false))
        for (sdk in listOf(29, 30)) assertEquals("the background helper", wayName(AccessMethod.ADB, sdk, background = true))
    }

    @Test fun simNumbersAreAndroidsWhereKnown() {
        val ui = LpaUi(slot = 1, method = AccessMethod.ADB)
        assertEquals(2, ui.simNumber)
        assertEquals(-1, ui.androidSlot) // the helper's slots are physical ones
        assertEquals(1, ui.copy(sim = 0).simNumber)
        assertEquals(0, ui.copy(sim = 0).androidSlot)
        assertEquals(1, ui.copy(method = AccessMethod.DIRECT).androidSlot)
    }
}
