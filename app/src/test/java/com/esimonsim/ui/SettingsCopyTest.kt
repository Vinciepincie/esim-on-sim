package com.esimonsim.ui

import com.esimonsim.adb.DebugSettings
import com.esimonsim.apdu.AccessMethod
import com.esimonsim.apdu.AccessMethod.ADB
import com.esimonsim.apdu.AccessMethod.DIRECT
import com.esimonsim.apdu.AccessMethod.ROOT
import com.esimonsim.apdu.AccessMethod.SHIZUKU
import com.esimonsim.apdu.AllowNeededException
import com.esimonsim.apdu.CardAccess
import com.esimonsim.apdu.DebuggingPortClosedException
import com.esimonsim.apdu.NearbyDevicesNeededException
import com.esimonsim.apdu.OmapiApduBackend
import com.esimonsim.apdu.PairingRequiredException
import com.esimonsim.apdu.RootApduBackend
import com.esimonsim.apdu.ShizukuApduBackend
import com.esimonsim.apdu.ShizukuStatus
import com.esimonsim.lpa.Lpa
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/** What Settings › Card access says, in each state (see SettingsScreen). */
class SettingsCopyTest {
    private val eid = "89049032000000000000000000012345"
    private val one = listOf(CardRow(0, 0, eid, listed = true))
    private val wifiOn = DebugSettings.State(developerOptions = true, wifi = true, wirelessDebugging = true)
    /** Automatic, reaching one card directly, on a dual-SIM phone, after the first look for cards. */
    private val ready = LpaUi(access = AccessStatus.READY, method = DIRECT, cards = one, lookingForCards = false,
        multiSim = true, settings = wifiOn)
    private val shizukuAndAdb = listOf(DIRECT, SHIZUKU, ADB)
    private val nbsp = " "

    // ---------------- the row ----------------

    @Test fun automaticShowsTheChoiceAndSaysWhatsInUse() {
        assertEquals("Automatic", accessValue(ready, choosable = true, sdk = 34))
        assertEquals("Now using direct access. Your card lets this app manage it, so nothing needs setting up.",
            accessHelp(ready, choosable = true, sdk = 34))
    }

    @Test fun aChosenWayInUseIsJustThatWay() {
        val ui = ready.copy(method = SHIZUKU, preferred = SHIZUKU, settings = wifiOn.copy(usbDebugging = true))
        assertEquals("Shizuku", accessValue(ui, true, 34))
        assertEquals("Works while Shizuku is running. This app's helper ends when you swipe the app away from Recent " +
            "apps or tap \"Stop card access\" below.", accessHelp(ui, true, 34))
        assertEquals("Wireless debugging", accessValue(ready.copy(preferred = ADB), true, 34))
        assertEquals("USB debugging", accessValue(ready.copy(preferred = ADB), true, 29))
    }

    @Test fun aFallbackSaysWhyAndWhatToDo() {
        val ui = ready.copy(preferred = SHIZUKU, preferredFailure = ShizukuApduBackend.NotRunningException(),
            shizuku = ShizukuStatus.STOPPED)
        assertEquals("Shizuku", accessValue(ui, true, 34))
        assertEquals("Shizuku isn't running, so card access uses direct access for now. Start Shizuku, then tap it " +
            "here.\n\nYour card lets this app manage it, so nothing needs setting up.", accessHelp(ui, true, 34))
        // Wireless debugging was off, so nothing was tried.
        val off = ready.copy(preferred = ADB, preferredFailure = CardAccess.NotTried, paired = true)
        assertEquals("Wireless debugging is off, so card access uses direct access for now. Turn it on, then tap it " +
            "here.\n\nYour card lets this app manage it, so nothing needs setting up.", accessHelp(off, true, 34))
        val root = ready.copy(method = SHIZUKU, preferred = ROOT, preferredFailure = RootApduBackend.DeniedException(),
            settings = wifiOn.copy(usbDebugging = true))
        assertEquals("Your root app didn't allow this app, so card access uses Shizuku for now. Allow $APP_NAME in " +
            "your root app, then tap it here.", accessHelp(root, true, 34).substringBefore("\n\n"))
    }

    @Test fun withNothingToChooseTheRowSaysWhatsInUse() {
        val helper = ready.copy(method = ADB, background = true, settings = wifiOn.copy(usbDebugging = true))
        assertEquals("Background helper", accessValue(helper, choosable = false, sdk = 27))
        assertEquals("Set up with a computer. Keeps working while USB debugging is on, until your phone restarts. " +
            "After a restart, it needs the computer again.", accessHelp(helper, false, 27))
        assertEquals("USB debugging", accessValue(helper.copy(background = false), false, 27))
        assertEquals("Not set up", accessValue(LpaUi(), false, 27))
        assertEquals("Not set up yet. Go back to follow the steps.", accessHelp(LpaUi(), false, 27))
    }

    @Test fun theBackgroundHelperSaysWhatKeepsItGoing() {
        val helper = ready.copy(method = ADB, background = true)
        assertEquals("Now using the background helper. Stops when Wireless debugging goes off, which Android also " +
            "does when Wi-Fi drops or changes.", accessHelp(helper, true, 34))
        assertEquals("Now using the background helper. Keeps working without Wi-Fi until your phone restarts or this " +
            "app is updated, if USB debugging is on. Otherwise it stops when Wireless debugging goes off.",
            accessHelp(helper.copy(settings = wifiOn.copy(usbDebugging = null)), true, 37))
    }

    @Test fun notConnectedSaysWhereToGo() {
        assertEquals("Connecting to your card…", accessHelp(LpaUi(access = AccessStatus.CONNECTING), true, 34))
        assertEquals("Card access is stopped. Choose a way here, or go back and tap \"Reconnect\".",
            accessHelp(LpaUi(stopped = true, method = DIRECT), true, 34))
        assertEquals("Not set up yet. Choose a way here, or go back to follow the steps.", accessHelp(LpaUi(), true, 34))
        assertEquals("Not connected right now. Go back to reconnect, or choose another way here.",
            accessHelp(LpaUi(method = ADB), true, 34))
        assertEquals("Not connected right now. Go back to reconnect.", accessHelp(LpaUi(method = ADB), false, 27))
    }

    // ---------------- the owner's note ----------------

    @Test fun theNoteAsksWhenNoCardIsNamed() = assertEquals("Two eSIM cards in your SIM slots, but only one shows? " +
        "Choose Shizuku or Wireless debugging here to reach both.", ownerNote(ready, shizukuAndAdb, 34))

    @Test fun theNoteNamesCardsDirectAccessCantOpen() {
        val ui = ready.copy(cards = one + CardRow(1, 1, locked = true))
        assertEquals(listOf(2), ui.lockedSims)
        assertEquals("The card in SIM${nbsp}2 doesn't let this app in directly. Choose root, Shizuku or Wireless " +
            "debugging here to manage it too.", ownerNote(ui, listOf(ROOT) + shizukuAndAdb, 34))
        assertEquals("The cards in SIM${nbsp}2 and SIM${nbsp}3 don't let this app in directly. Choose Shizuku or " +
            "Wireless debugging here to manage them too.",
            ownerNote(ui.copy(cards = ui.cards + CardRow(2, 2, locked = true)), shizukuAndAdb, 34))
        // A card found in SIM 2 shows there's room for it, whatever the phone says about its SIMs.
        assertEquals(ownerNote(ui, shizukuAndAdb, 34), ownerNote(ui.copy(multiSim = false), shizukuAndAdb, 34))
    }

    @Test fun android9SaysWhatTheOthersReach() = assertEquals("Two eSIM cards in your SIM slots, but only one shows? " +
        "Choose USB debugging here to reach both. On this version of Android, it reaches a card outside SIM${nbsp}1 " +
        "only while one of its eSIMs is on.", ownerNote(ready, listOf(DIRECT, ADB), 28))

    @Test fun noNoteWhereItWouldntHelp() {
        assertNull("single SIM", ownerNote(ready.copy(multiSim = false), shizukuAndAdb, 34))
        assertNull("still looking", ownerNote(ready.copy(lookingForCards = true), shizukuAndAdb, 34))
        assertNull("both found", ownerNote(ready.copy(cards = one + CardRow(1, 1, "8904", listed = true)), shizukuAndAdb, 34))
        assertNull("every card in reach", ownerNote(ready.copy(method = SHIZUKU), shizukuAndAdb, 34))
        assertNull("nothing else to choose", ownerNote(ready, listOf(DIRECT), 34))
        assertNull("not connected", ownerNote(ready.copy(access = AccessStatus.SETUP), shizukuAndAdb, 34))
    }

    // ---------------- the dialog ----------------

    @Test fun eachRowSaysWhatChoosingItMeans() {
        assertEquals("Uses the first of the ways below that works. Now: Direct.", wayRowHelp(null, ready, 34))
        assertEquals("Uses the first of the ways below that works.", wayRowHelp(null, LpaUi(), 34))
        assertEquals("In use. Nothing to set up.", wayRowHelp(DIRECT, ready, 34))
        assertEquals("Didn't open your card last time. Most cards only let their maker's app in.",
            wayRowHelp(DIRECT, ready.copy(method = SHIZUKU, directRefused = true), 34))
        assertEquals("Nothing to set up, for cards that let any app in.", wayRowHelp(DIRECT, LpaUi(), 34))
        assertEquals("Your root app may ask. Works without Wi-Fi.", wayRowHelp(ROOT, ready, 34))
        assertEquals("Your root app didn't allow this app. Allow $APP_NAME there first.",
            wayRowHelp(ROOT, ready.copy(rootDeclined = true), 34))
        assertEquals("In use. Works without Wi-Fi.", wayRowHelp(ROOT, ready.copy(method = ROOT), 34))
    }

    @Test fun shizukuSaysHowItStands() {
        fun help(s: ShizukuStatus) = wayRowHelp(SHIZUKU, ready.copy(shizuku = s), 34)
        assertEquals("Already allows this app. Works while Shizuku is running.", help(ShizukuStatus.ALLOWED))
        assertEquals("Shizuku asks you to allow this app. Works while it's running.", help(ShizukuStatus.NOT_ASKED))
        assertEquals("You said no to this app in Shizuku. Choose it to be asked again.", help(ShizukuStatus.DECLINED))
        assertEquals("Not running. Tap to open Shizuku and start it, then tap it here again.", help(ShizukuStatus.STOPPED))
        assertEquals("Not on this phone any more.", help(ShizukuStatus.ABSENT))
        assertEquals("In use. Works while Shizuku is running.", wayRowHelp(SHIZUKU, ready.copy(method = SHIZUKU), 34))
    }

    @Test fun theChosenWayInFallbackSaysWhyAndATapTriesAgain() = assertEquals("Chosen, but Shizuku isn't running. " +
        "Start Shizuku, then tap it here.", wayRowHelp(SHIZUKU, ready.copy(preferred = SHIZUKU,
        preferredFailure = ShizukuApduBackend.NotRunningException(), shizuku = ShizukuStatus.STOPPED), 34))

    @Test fun debuggingSaysWhatsSetUp() {
        assertEquals("In use, through its background helper.",
            wayRowHelp(ADB, ready.copy(method = ADB, background = true), 34))
        assertEquals("In use. Works while Wireless debugging is on.", wayRowHelp(ADB, ready.copy(method = ADB), 34))
        assertEquals("Needs pairing again: about a minute.", wayRowHelp(ADB, ready.copy(pairingExpired = true), 34))
        assertEquals("Paired. Turn on Wireless debugging, then tap it here.",
            wayRowHelp(ADB, ready.copy(paired = true, settings = wifiOn.copy(wirelessDebugging = false)), 34))
        assertEquals("Already paired. Works while Wireless debugging is on.", wayRowHelp(ADB, ready.copy(paired = true), 34))
        assertEquals("Pair this app once, on Wi-Fi: about 2 minutes. Works while Wireless debugging is on.",
            wayRowHelp(ADB, ready, 34))
        assertEquals("In use, until your phone restarts.", wayRowHelp(ADB, ready.copy(method = ADB), 29))
        assertEquals("Set up with a computer before. After each restart, it needs the computer again.",
            wayRowHelp(ADB, ready.copy(paired = true), 29))
        assertEquals("Set up with a computer: about 5 minutes. Again after each restart.", wayRowHelp(ADB, ready, 29))
    }

    @Test fun theLeadLineSaysWhatReachesTheCard() {
        assertEquals("Direct access can't open the card in SIM${nbsp}2. To manage both cards, choose Shizuku or " +
            "Wireless debugging.", leadLine(2, shizukuAndAdb, 34, both = true))
        assertEquals("Direct access can't open the card in SIM${nbsp}1. To manage it, choose root, Shizuku or " +
            "Wireless debugging.", leadLine(1, listOf(ROOT) + shizukuAndAdb, 34, both = false))
        assertNull(leadLine(2, listOf(DIRECT), 34, both = true))
    }

    @Test fun theFooterComparesWithDirectAccess() {
        assertEquals("Root, Shizuku and Wireless debugging can reach every card in your phone. Direct only reaches " +
            "cards that let this app in.", footer(listOf(ROOT) + shizukuAndAdb, 34))
        assertEquals("On this version of Android, USB debugging reaches a card in SIM${nbsp}1, or in another SIM slot " +
            "while one of its eSIMs is on. Direct reaches any slot, but only cards that let this app in.",
            footer(listOf(DIRECT, ADB), 28))
        assertEquals("On this version of Android, root and USB debugging reach", footer(listOf(ROOT, DIRECT, ADB), 28)
            ?.substringBefore(" a card"))
        assertNull("no direct access", footer(listOf(SHIZUKU, ADB), 34))
    }

    @Test fun listsEndWithOr() {
        assertEquals("Shizuku or Wireless debugging", listed(listOf("Shizuku", "Wireless debugging"), "or"))
        assertEquals("root, Shizuku or USB debugging", listed(listOf("root", "Shizuku", "USB debugging"), "or"))
        assertEquals("a and b", listed(listOf("a", "b")))
    }

    // ---------------- why a way didn't work ----------------

    @Test fun eachFailureHasItsWhy() {
        fun why(e: Exception?, way: AccessMethod = SHIZUKU, sdk: Int = 34, paired: Boolean = true, shizuku: Boolean = true) =
            fallbackWhy(e, way, sdk, paired, shizuku)
        assertEquals("Shizuku isn't running", why(ShizukuApduBackend.NotRunningException()))
        assertEquals("Shizuku isn't on this phone any more", why(ShizukuApduBackend.NotRunningException(), shizuku = false))
        assertEquals("Shizuku doesn't allow this app", why(ShizukuApduBackend.DeniedException(answered = true)))
        assertEquals("Shizuku couldn't start this app's card helper", why(ShizukuApduBackend.StartException(IOException())))
        assertEquals("root isn't available to this app", why(RootApduBackend.NotRootedException(), ROOT))
        assertEquals("your root app didn't allow this app", why(RootApduBackend.DeniedException(), ROOT))
        assertEquals("your card didn't let this app in directly", why(OmapiApduBackend.UnavailableException(true), DIRECT))
        assertEquals("Android's SIM service didn't answer in time",
            why(OmapiApduBackend.UnavailableException(false, OmapiApduBackend.BindTimeoutException()), DIRECT))
        assertEquals("no eSIM card answered directly", why(OmapiApduBackend.UnavailableException(false), DIRECT))
        assertEquals("your phone forgot this app's pairing", why(PairingRequiredException(), ADB))
        assertEquals("this app needs \"Nearby devices\" to find Wireless debugging", why(NearbyDevicesNeededException(), ADB))
        assertEquals("USB debugging needs your computer again after a restart", why(DebuggingPortClosedException(), ADB, 29))
        assertEquals("your phone no longer allows this app's debugging access", why(AllowNeededException(true), ADB, 29))
        assertEquals("USB debugging is off", why(CardAccess.NotTried, ADB, 29))
        assertEquals("Wireless debugging isn't set up", why(CardAccess.NotTried, ADB, paired = false))
        assertEquals("this app couldn't connect through Wireless debugging", why(IOException("refused"), ADB))
        assertEquals("direct access didn't connect", why(CardAccess.NotTried, DIRECT))
    }

    @Test fun eachFailureSaysWhatToDo() {
        assertEquals("Start Shizuku, then tap it here.", fallbackAction(ShizukuApduBackend.NotRunningException(), SHIZUKU, true, true))
        assertEquals("Choose another way here.", fallbackAction(ShizukuApduBackend.NotRunningException(), SHIZUKU, true, false))
        assertEquals("Allow $APP_NAME in your root app, then tap it here.",
            fallbackAction(RootApduBackend.NotRootedException(), ROOT, true, true))
        assertEquals("Tap it here for the steps.", fallbackAction(DebuggingPortClosedException(), ADB, true, true))
        assertEquals("Turn it on, then tap it here.", fallbackAction(CardAccess.NotTried, ADB, true, true))
        assertEquals("Tap it here to set it up.", fallbackAction(CardAccess.NotTried, ADB, false, true))
        assertEquals("Tap it here to try again.", fallbackAction(OmapiApduBackend.UnavailableException(true), DIRECT, true, true))
    }

    // ---------------- a switch that failed ----------------

    @Test fun aFailedSwitchSaysWhy() {
        assertEquals("Root isn't available to this app. If your root app hides it, allow $APP_NAME there, then try again.",
            switchError(ROOT, RootApduBackend.NotRootedException(), 34, true))
        assertEquals("Your card doesn't let this app in directly. Most cards only let their maker's app in.",
            switchError(DIRECT, OmapiApduBackend.UnavailableException(true), 34, true))
        assertEquals("Shizuku didn't allow this app. Choose Shizuku again to be asked again.",
            switchError(SHIZUKU, ShizukuApduBackend.DeniedException(answered = true), 34, true))
        assertEquals("Your phone didn't allow this app. Try again, and tap \"OK\" when it asks.",
            switchError(ADB, AllowNeededException(true), 28, true))
        assertEquals("After a restart, USB debugging needs your computer again. Follow the steps to turn it on.",
            switchError(ADB, DebuggingPortClosedException(), 29, true))
        assertEquals("Wireless debugging is off. Turn it on, then choose it here again.",
            switchError(ADB, IOException("refused"), 34, mayConnect = false))
        assertEquals("Couldn't connect through Wireless debugging. Check that it's on, then try again.",
            switchError(ADB, IOException("refused"), 34, mayConnect = true))
        assertEquals("USB debugging is off. Turn it on in Developer options, then try again.",
            switchError(ADB, IOException("refused"), 29, mayConnect = false))
        // The pane for pairing again opens instead.
        assertNull(switchError(ADB, PairingRequiredException(), 34, true))
    }

    @Test fun aFailedSwitchIsNamedForTheWay() {
        assertEquals("Couldn't switch to root", switchTitle(ROOT, 34))
        assertEquals("Couldn't switch to direct access", switchTitle(DIRECT, 34))
        assertEquals("Couldn't switch to Shizuku", switchTitle(SHIZUKU, 34))
        assertEquals("Couldn't switch to USB debugging", switchTitle(ADB, 29))
        assertEquals("Couldn't switch card access", switchTitle(null, 34))
    }

    @Test fun theLockedOnlyMessageSaysWhichSim() {
        assertEquals(2, Lpa.lockedSim(Lpa.notFoundLocked(2)))
        assertNull(Lpa.lockedSim("Couldn't find the eSIM card."))
        assertNull(Lpa.lockedSim(null))
    }
}
