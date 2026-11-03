package com.esimonsim.apdu

import com.esimonsim.lpa.Hex
import com.esimonsim.lpa.Lpa
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** Which way in the app picks, and when it asks again: root, then the card's own access rules, then Shizuku, then
 *  Wireless debugging. Every way is faked; nothing here needs a phone. */
class CardAccessTest {
    private val prefs = MemoryPrefs()
    private val adb = FakeAdb()
    private var suStarts = 0
    private var cards = mapOf(1 to FakeCard.LOCKED)
    /** See [FakeReaders.atrs]. */
    private var atrs = emptyMap<Int, String?>()
    private var su: () -> Process = { throw IOException("Cannot run program \"su\": error=2, No such file or directory") }

    private var readers: FakeReaders? = null
    private var shizuku: ShizukuApduBackend.Gate = CardAccess.NoShizuku

    /** Android 16 unless [sdk] says otherwise: 10 and older have no Wireless debugging. */
    private fun access(suFound: Boolean = false, sdk: Int = 36, found: () -> Boolean = { suFound }) = CardAccess(prefs,
        OmapiApduBackend { FakeReaders(cards, atrs = atrs).also { readers = it } },
        RootApduBackend({ "/data/app/base.apk" }, { suStarts++; su() }),
        adb, findSu = found, sdk = sdk, shizuku = ShizukuApduBackend({ "/data/app/base.apk" }, shizuku))

    @Test fun cardThatLetsTheAppInNeedsNoSetup() {
        cards = mapOf(0 to FakeCard.ORDINARY, 1 to FakeCard.OPEN)
        val a = access()
        a.connect(viaAdb = false)
        assertEquals(AccessMethod.DIRECT, a.method)
        assertTrue(a.isReady())
        assertFalse(a.canSetUpSim) // Android keeps mobile data and roaming from ordinary apps
        assertEquals(1, suStarts) // root first, but this phone isn't rooted
        assertTrue(adb.connects.isEmpty())
        assertEquals("DIRECT", prefs.values["method"]) // kept for next time
    }

    @Test fun directAccessSaysACardWasTakenOut() {
        // What a refresh of the chosen card goes by, as OMAPI answers no card the way it answers an ordinary SIM.
        val inPhone = mutableMapOf(0 to FakeCard.OPEN, 1 to FakeCard.OPEN)
        cards = inPhone
        val a = access()
        a.connect(viaAdb = false)
        assertEquals(AccessMethod.DIRECT, a.method)
        inPhone[1] = FakeCard.EMPTY
        assertEquals(false, a.present(1))
        assertEquals(true, a.present(0))
    }

    @Test fun rootComesBeforeWirelessDebugging() {
        adb.paired = true
        su = { FakeSu(helper = helperAs(0)) }
        val a = access(suFound = true)
        a.connect()
        assertEquals(AccessMethod.ROOT, a.method)
        assertTrue(a.canSetUpSim)
        assertTrue(adb.connects.isEmpty())
        assertTrue(a.useSim(1, "8944", data = true, roaming = true).applied)
    }

    @Test fun notRootedLockedCardUsesWirelessDebugging() {
        adb.paired = true
        val a = access(suFound = true)
        a.connect()
        assertEquals(AccessMethod.ADB, a.method)
        assertEquals(listOf<Int?>(null), adb.connects)
        assertEquals(1, suStarts)
        assertFalse(a.rootFound) // su was there but wouldn't run, as on test builds of Android
        // Ruled out once the app has started: not tried again by itself.
        a.disconnect()
        a.connect()
        assertEquals(1, suStarts)
        assertEquals(2, adb.connects.size)
    }

    @Test fun nothingSetUpAsksForSetupWithoutAnError() {
        val a = access()
        assertTrue(a.withoutSetup)
        val e = assertThrows(SetupNeededException::class.java) { a.connect() }
        assertNull(e.message) // first run: nothing broke, it just needs setting up
        assertTrue(adb.connects.isEmpty()) // never paired: Wireless debugging can't work yet
        assertFalse(a.withoutSetup)
        assertNull(a.method)
    }

    @Test fun checkingAgainOnSetupNeverStartsOnAdb() {
        // Android 10: without viaAdb = false, "again" would try the debugging port, and the phone would ask.
        adb.tcpOpen = true
        val a = access(sdk = 29)
        val e = assertThrows(SetupNeededException::class.java) { a.connect(viaAdb = false, again = true) }
        assertNull(e.message)
        assertTrue(adb.connects.isEmpty())
        // The card went in meanwhile.
        cards = mapOf(1 to FakeCard.OPEN)
        a.connect(viaAdb = false, again = true)
        assertEquals(AccessMethod.DIRECT, a.method)
        assertTrue(adb.connects.isEmpty())
    }

    @Test fun rootIsAskedEachTimeTheAppStarts() {
        adb.paired = true
        su = { FakeSu(exitAtOnce = true) { null } } // the root app said no
        val first = access(suFound = true)
        first.connect()
        assertEquals(AccessMethod.ADB, first.method)
        assertTrue(first.rootDeclined)
        // Not again by itself while the app runs...
        first.disconnect(); first.connect()
        assertEquals(1, suStarts)
        // ...but "Use root" asks, and so does the next start, picking up a grant made meanwhile.
        assertThrows(RootApduBackend.DeniedException::class.java) { first.connectRoot() }
        assertEquals(2, suStarts)
        su = { FakeSu(helper = helperAs(0)) }
        val later = access(suFound = true)
        later.connect()
        assertEquals(AccessMethod.ROOT, later.method)
        assertFalse(later.rootDeclined)
    }

    @Test fun reconnectingOnRootUsesSuAndNothingElse() {
        // Midway through an operation another way in would number the slots differently.
        adb.paired = true
        cards = mapOf(1 to FakeCard.OPEN)
        su = { FakeSu(helper = helperAs(0)) }
        val a = access(suFound = true)
        a.connect()
        assertEquals(AccessMethod.ROOT, a.method)
        a.disconnect()
        assertEquals(1, suStarts)
        a.reconnect()
        assertEquals(2, suStarts)
        assertEquals(AccessMethod.ROOT, a.method)
        assertTrue(adb.connects.isEmpty())
        assertNull(readers)
    }

    @Test fun reconnectingOnDirectAccessDoesNotSkipACardThatRefusedBefore() {
        cards = mapOf(1 to FakeCard.OPEN)
        val a = access()
        a.connect()
        assertEquals(AccessMethod.DIRECT, a.method)
        // The saved list names this very card: connect() would skip it, but it just worked.
        prefs.values[CardAccess.REFUSED] = setOf(com.esimonsim.lpa.Hex.encode(byteArrayOf(0x3B, FakeCard.OPEN.ordinal.toByte())))
        a.disconnect()
        a.reconnect()
        assertEquals(AccessMethod.DIRECT, a.method)
        assertTrue(a.isReady())
    }

    @Test fun useRootLeavesAdbOnlyOnceRootWorks() {
        adb.paired = true
        val a = access(suFound = true)
        a.connect()
        assertEquals(AccessMethod.ADB, a.method)
        su = { FakeSu(exitAtOnce = true) { null } }
        assertThrows(RootApduBackend.DeniedException::class.java) { a.connectRoot() }
        assertTrue(a.isReady())
        assertEquals(AccessMethod.ADB, a.method)
        su = { FakeSu(helper = helperAs(0)) }
        a.connectRoot()
        assertEquals(AccessMethod.ROOT, a.method)
        assertFalse(adb.isReady())
    }

    @Test fun rootThatWentAwaySaysWhy() {
        prefs.values["method"] = "ROOT"
        val e = assertThrows(SetupNeededException::class.java) { access().connect() }
        assertTrue(e.message!!, e.message!!.startsWith("Root isn't there any more"))
    }

    @Test fun rootBeatsDirectAccess() {
        cards = mapOf(1 to FakeCard.OPEN)
        val readers = ArrayList<FakeReaders>()
        su = { FakeSu(helper = helperAs(0)) }
        val a = CardAccess(prefs, OmapiApduBackend { FakeReaders(cards).also { readers += it } },
            RootApduBackend({ "x" }, { suStarts++; su() }), adb, findSu = { true })
        a.connect()
        assertEquals(AccessMethod.ROOT, a.method)
        assertTrue(a.canSetUpSim)
        assertTrue(readers.isEmpty())
    }

    @Test fun directAccessSetsUpSimOnlyThroughAPairing() {
        cards = mapOf(1 to FakeCard.OPEN)
        val a = access()
        a.connect()
        assertEquals(AccessMethod.DIRECT, a.method)
        assertFalse(a.canSetUpSim)
        assertThrows(CardAccess.NoSimSettingsException::class.java) { a.useSim(1, "8944", data = true, roaming = false) }
        // Paired just for this (Details › Mobile data and roaming): the card itself stays on direct access.
        adb.paired = true
        assertTrue(a.canSetUpSim)
        assertTrue(a.useSim(1, "8944", data = true, roaming = false).applied)
        assertEquals(listOf<Int?>(null), adb.connects)
        assertEquals(AccessMethod.DIRECT, a.method)
        // Wireless debugging off: says so, instead of a vague refusal.
        adb.ready = false
        adb.paired = false; adb.helper = true
        val off = object : CardAccess.Adb by adb {
            override fun connect(port: Int?, authorize: Boolean) = throw java.io.IOException("Couldn't find Wireless debugging.")
        }
        val b = CardAccess(MemoryPrefs(), OmapiApduBackend { FakeReaders(cards) },
            RootApduBackend({ "x" }, { su() }), off, findSu = { false })
        b.connect()
        assertThrows(CardAccess.NoSimSettingsException::class.java) { b.useSim(1, "8944", data = true, roaming = false) }
    }

    @Test fun helperThatIsNotRootIsRefused() {
        su = { FakeSu(helper = helperAs(2000)) }
        assertThrows(RootApduBackend.DeniedException::class.java) { access(suFound = true).connectRoot() }
    }

    @Test fun portMeansWirelessDebuggingOnly() {
        cards = mapOf(1 to FakeCard.OPEN)
        val readers = ArrayList<FakeReaders>()
        val a = CardAccess(prefs, OmapiApduBackend { FakeReaders(cards).also { readers += it } },
            RootApduBackend({ "x" }, { suStarts++; su() }), adb, findSu = { true })
        a.connect(port = 37123)
        assertEquals(AccessMethod.ADB, a.method)
        assertEquals(listOf<Int?>(37123), adb.connects)
        assertTrue(readers.isEmpty())
        assertEquals(0, suStarts)
    }

    @Test fun directAccessThatStopsWorkingSaysWhy() {
        prefs.values["method"] = "DIRECT"
        cards = emptyMap() // card taken out
        val a = access()
        val e = assertThrows(SetupNeededException::class.java) { a.connect() }
        assertTrue(e.message!!, e.message!!.startsWith("Couldn't find your eSIM card"))
        // Not tried again by itself (that would flash "Connecting" on every return to the app)...
        assertFalse(a.withoutSetup)
        cards = mapOf(1 to FakeCard.OPEN)
        assertThrows(SetupNeededException::class.java) { a.connect() }
        // ...but "Reconnect" does, and it's still the way in.
        a.connect(again = true)
        assertEquals(AccessMethod.DIRECT, a.method)
    }

    @Test fun cardSwappedForLockedOneNeedsWirelessDebugging() {
        prefs.values["method"] = "DIRECT"
        adb.paired = true
        val a = access()
        a.connect()
        assertEquals(AccessMethod.ADB, a.method)
    }

    @Test fun wirelessDebuggingFailingDoesNotHideWhyTheCardFailed() {
        prefs.values["method"] = "DIRECT"
        cards = emptyMap()
        val off = object : CardAccess.Adb by adb {
            override val wasPaired = true
            override fun connect(port: Int?, authorize: Boolean) = throw IOException("Couldn't find Wireless debugging.")
        }
        val a = CardAccess(prefs, OmapiApduBackend { FakeReaders(cards) }, RootApduBackend({ "x" }, { su() }), off,
            findSu = { false })
        val e = assertThrows(SetupNeededException::class.java) { a.connect(again = true) }
        assertTrue(e.message!!, e.message!!.startsWith("Couldn't find your eSIM card"))
    }

    @Test fun nearbyDevicesOnlyComesFirstWhenNothingElseFailed() {
        val nearby = object : CardAccess.Adb by adb {
            override val wasPaired = true
            override fun connect(port: Int?, authorize: Boolean) = throw NearbyDevicesNeededException()
        }
        fun access() = CardAccess(prefs, OmapiApduBackend { FakeReaders(cards) }, RootApduBackend({ "x" }, { su() }),
            nearby, findSu = { false }, sdk = 37)
        // Card access was over Wireless debugging: its Reconnect screen asks for the permission.
        prefs.values["method"] = "ADB"
        assertThrows(NearbyDevicesNeededException::class.java) { access().connect() }
        // It reached the card directly: that failing is the news, as for any other Wireless debugging error.
        prefs.values["method"] = "DIRECT"
        cards = emptyMap()
        val e = assertThrows(SetupNeededException::class.java) { access().connect() }
        assertTrue(e.message!!, e.message!!.startsWith("Couldn't find your eSIM card"))
    }

    @Test fun roamingThatMayNotLastIsPassedOn() {
        adb.paired = true
        adb.roamingMayReset = true
        val a = access(sdk = 28)
        a.connect()
        assertEquals(SimSettingsResult(applied = true, roamingMayReset = true), a.useSim(1, "8944", data = true, roaming = true))
        assertEquals(SimSettingsResult(applied = true), a.useSim(1, "8944", data = true, roaming = false))
    }

    @Test fun useRootKeepsDirectAccessWhenRefused() {
        cards = mapOf(1 to FakeCard.OPEN)
        val a = access(suFound = true)
        a.connect() // not rooted yet: direct
        assertEquals(AccessMethod.DIRECT, a.method)
        su = { FakeSu(exitAtOnce = true) { null } }
        assertThrows(RootApduBackend.DeniedException::class.java) { a.connectRoot() }
        assertTrue(a.isReady())
        assertEquals(AccessMethod.DIRECT, a.method)
        su = { FakeSu(helper = helperAs(0)) }
        a.connectRoot()
        assertEquals(AccessMethod.ROOT, a.method)
        assertTrue(readers!!.closed) // direct access ended once root took over
    }

    @Test fun rootAllowedLaterIsPickedUpOnReturn() {
        var visible = false // KernelSU and APatch hide su until the app is allowed
        val a = access(found = { visible })
        assertThrows(SetupNeededException::class.java) { a.connect() }
        assertFalse(a.withoutSetup)
        assertFalse(a.rootAppeared())
        visible = true
        su = { FakeSu(helper = helperAs(0)) }
        assertTrue(a.rootAppeared())
        assertTrue(a.withoutSetup)
        a.connect()
        assertEquals(AccessMethod.ROOT, a.method)
    }

    @Test fun lostPairingIsTold() {
        cards = mapOf(1 to FakeCard.OPEN)
        val a = access()
        a.connect()
        val e = assertThrows(CardAccess.NoSimSettingsException::class.java) { a.useSim(1, "8944", data = true, roaming = true) }
        assertTrue(e.needsPairing)
    }

    @Test fun reconnectWithinAnOperationKeepsTheSameWay() {
        adb.paired = true
        cards = mapOf(1 to FakeCard.OPEN)
        su = { FakeSu(helper = helperAs(0)) }
        val a = access(suFound = true)
        a.connect(port = 37123) // by hand, with a port: root and direct were never tried
        adb.ready = false // the link dropped during a switch
        a.reconnect()
        assertEquals(AccessMethod.ADB, a.method)
        assertEquals(listOf<Int?>(37123, null), adb.connects)
        assertEquals(0, suStarts) // no root question in the middle of a switch
        assertEquals(null, readers) // and no other numbering of slots
    }

    @Test fun slowSimServiceRulesNothingOut() {
        prefs.values["method"] = "DIRECT"
        var slow = true
        val a = CardAccess(prefs, OmapiApduBackend {
            if (slow) throw OmapiApduBackend.BindTimeoutException() else FakeReaders(mapOf(1 to FakeCard.OPEN))
        }, RootApduBackend({ "x" }, { su() }), adb, findSu = { false })
        val e = assertThrows(SetupNeededException::class.java) { a.connect() }
        assertTrue(e.message!!, e.message!!.startsWith("Android's SIM service didn't answer"))
        assertTrue(a.withoutSetup)
        slow = false
        a.connect()
        assertEquals(AccessMethod.DIRECT, a.method)
    }

    @Test fun cardsThatRefusedAreNotAskedAgainAtEachStart() {
        adb.paired = true
        cards = mapOf(0 to FakeCard.LOCKED, 1 to FakeCard.LOCKED) // a carrier SIM and a 5ber, say
        access().connect()
        assertEquals(16, readers!!.opened.size) // every eSIM manager ID on both
        // The next start reads who's there, and asks nobody.
        access().connect()
        assertEquals(0, readers!!.opened.size)
        assertEquals(AccessMethod.ADB, prefs.values["method"]?.let { AccessMethod.valueOf(it as String) })
        // Another kind of card in the slot is asked as usual.
        cards = mapOf(0 to FakeCard.LOCKED, 1 to FakeCard.OPEN)
        val later = access()
        later.connect()
        assertEquals(AccessMethod.DIRECT, later.method)
    }

    @Test fun reconnectAsksRefusedCardsAgain() {
        adb.paired = true
        cards = mapOf(1 to FakeCard.LOCKED)
        access().connect()
        val again = access()
        again.connect(again = true)
        assertEquals(8, readers!!.opened.size)
    }

    @Test fun reconnectingByHandChecksTheCardAgain() {
        val a = access()
        assertThrows(SetupNeededException::class.java) { a.connect() }
        cards = mapOf(1 to FakeCard.OPEN) // e.g. another card put in
        assertThrows(SetupNeededException::class.java) { a.connect() } // not by itself
        a.connect(again = true)
        assertEquals(AccessMethod.DIRECT, a.method)
    }

    @Test fun backgroundHelperEndsOnceRootWorks() {
        adb.helper = true
        su = { FakeSu(helper = helperAs(0)) }
        access(suFound = true).connect()
        assertEquals(1, adb.stops)
    }

    @Test fun backgroundHelperStaysWithDirectAccess() {
        adb.helper = true // it can still change SIM settings
        cards = mapOf(1 to FakeCard.OPEN)
        access().connect()
        assertEquals(0, adb.stops)
    }

    @Test fun stopEndsEverything() {
        su = { FakeSu(helper = helperAs(0)) }
        val a = access(suFound = true)
        a.connect()
        a.stop()
        assertFalse(a.isReady())
        assertEquals(1, adb.stops)
        assertThrows(ApduException::class.java) { a.openChannel(1, 0, Lpa.ISD_R_OPEN) }
    }

    @Test fun rootHelperProtocol() {
        val seen = ArrayList<JSONObject>()
        su = { FakeSu { q -> seen += q; helperAs(0)(q).apply { if (q.getString("op") == "open") put("channel", 2).put("status", 0).put("select", "6F00") } } }
        val a = access(suFound = true)
        a.connect()
        val r = a.openChannel(1, 0, Lpa.ISD_R_OPEN)
        assertEquals(2, r.channel)
        assertEquals(Lpa.ISD_R_OPEN, seen.last().getString("aid")) // the helper selects with the shorter ID itself
    }

    @Test fun rootHelperThatDiesDropsAccess() {
        var answers = 0
        su = { FakeSu { q -> if (++answers > 1) null else helperAs(0)(q) } }
        val a = access(suFound = true)
        a.connect()
        assertThrows(ApduException::class.java) { a.openChannel(1, 0, Lpa.ISD_R_OPEN) }
        assertFalse(a.isReady())
    }

    // ---------------- Shizuku ----------------

    @Test fun rootBeatsShizuku() {
        val gate = FakeShizuku(mayRun = true).also { shizuku = it }
        su = { FakeSu(helper = helperAs(0)) }
        val a = access(suFound = true)
        a.connect()
        assertEquals(AccessMethod.ROOT, a.method)
        assertEquals(0, gate.starts)
    }

    @Test fun cardThatLetsTheAppInIsNeverAskedAboutShizuku() {
        val gate = FakeShizuku().also { shizuku = it }
        cards = mapOf(1 to FakeCard.OPEN)
        val a = access()
        a.connect()
        assertEquals(AccessMethod.DIRECT, a.method)
        assertEquals(0, gate.asks)
        assertEquals(0, gate.starts)
    }

    @Test fun shizukuComesBeforeWirelessDebugging() {
        adb.paired = true
        shizuku = FakeShizuku(mayRun = true)
        val a = access()
        a.connect()
        assertEquals(AccessMethod.SHIZUKU, a.method)
        assertTrue(a.isReady())
        assertTrue(a.canSetUpSim)
        assertTrue(adb.connects.isEmpty())
        assertTrue(a.useSim(1, "8944", data = true, roaming = true).applied)
        assertEquals("SHIZUKU", prefs.values["method"]) // kept for next time
    }

    @Test fun shizukuNeedsNoWirelessDebuggingOrComputer() {
        val gate = FakeShizuku(mayRun = true).also { shizuku = it }
        val a = access(sdk = 29)
        a.connect(viaAdb = false)
        assertEquals(AccessMethod.SHIZUKU, a.method)
        assertEquals(1, gate.starts)
        assertTrue(adb.authorized.isEmpty())
    }

    @Test fun portMeansNoShizukuEither() {
        val gate = FakeShizuku(mayRun = true).also { shizuku = it }
        val a = access()
        a.connect(port = 37123)
        assertEquals(AccessMethod.ADB, a.method)
        assertEquals(0, gate.starts)
    }

    @Test fun whateverGoesWrongWithShizukuWirelessDebuggingStillWorks() {
        adb.paired = true
        for (failure in listOf(IllegalStateException("exec failed"), RuntimeException("gone"), SecurityException("denied"))) {
            shizuku = FakeShizuku(mayRun = true, process = { throw failure })
            val a = access()
            a.connect()
            assertEquals(AccessMethod.ADB, a.method)
        }
    }

    @Test fun firstStartWaitsForItsConnectionOnce() {
        adb.paired = true
        val gate = FakeShizuku(running = false, mayRun = true, arrives = true).also { shizuku = it }
        val a = access()
        a.connect()
        assertEquals(AccessMethod.SHIZUKU, a.method)
        assertEquals(1, gate.waits)
        a.stop()
        a.connect()
        assertEquals(1, gate.waits)
    }

    @Test fun adbThatWorkedLastTimeIsntHeldUpByAStoppedShizuku() {
        adb.paired = true
        prefs.values[CardAccess.METHOD] = "ADB"
        val gate = FakeShizuku(running = false, mayRun = true).also { shizuku = it }
        val a = access()
        a.connect()
        assertEquals(AccessMethod.ADB, a.method)
        assertEquals(0, gate.waits)
    }

    @Test fun aShizukuAlreadyRunningStillComesBeforeAdb() {
        adb.paired = true
        prefs.values[CardAccess.METHOD] = "ADB"
        val gate = FakeShizuku(running = true, mayRun = true).also { shizuku = it }
        val a = access()
        a.connect()
        assertEquals(AccessMethod.SHIZUKU, a.method)
        assertEquals(0, gate.waits)
    }

    @Test fun shizukuIsStillWaitedForWhereAdbCantTakeOver() {
        prefs.values[CardAccess.METHOD] = "ADB"
        // Reconnect: someone asked, perhaps having just started it.
        adb.paired = true
        var gate = FakeShizuku(running = false, mayRun = true, arrives = true).also { shizuku = it }
        var a = access()
        a.connect(again = true)
        assertEquals(AccessMethod.SHIZUKU, a.method)
        assertEquals(1, gate.waits)
        // ADB not tried this time.
        prefs.values[CardAccess.METHOD] = "ADB"
        gate = FakeShizuku(running = false, mayRun = true, arrives = true).also { shizuku = it }
        a = access()
        a.connect(viaAdb = false)
        assertEquals(AccessMethod.SHIZUKU, a.method)
        assertEquals(1, gate.waits)
        // Chosen, whatever worked last time.
        prefs.values[CardAccess.METHOD] = "ADB"
        prefs.values[CardAccess.PREFERRED] = "SHIZUKU"
        gate = FakeShizuku(running = false, mayRun = true, arrives = true).also { shizuku = it }
        a = access()
        a.connect()
        assertEquals(AccessMethod.SHIZUKU, a.method)
        assertEquals(1, gate.waits)
    }

    @Test fun phonesWithoutShizukuNeverWaitForIt() {
        adb.paired = true
        val gate = FakeShizuku(installed = false, running = false).also { shizuku = it }
        val a = access()
        a.connect()
        assertEquals(AccessMethod.ADB, a.method)
        assertEquals(0, gate.waits)
        assertEquals(ShizukuStatus.ABSENT, a.shizukuStatus)
    }

    @Test fun stoppedShizukuLeavesSetupToSayWhatToDo() {
        val gate = FakeShizuku(running = false).also { shizuku = it }
        val a = access()
        val e = assertThrows(SetupNeededException::class.java) { a.connect() }
        assertNull(e.message) // it never was the way in: nothing broke
        assertEquals(ShizukuStatus.STOPPED, a.shizukuStatus)
        assertFalse(a.shizukuAppeared())
        assertFalse(a.withoutSetup)
        // Started from the setup screen: picked up by itself, and only then asks.
        gate.running = true
        assertEquals(ShizukuStatus.NOT_ASKED, a.shizukuStatus)
        assertTrue(a.shizukuAppeared())
        assertFalse(a.shizukuAppeared()) // once
        assertTrue(a.withoutSetup)
        a.connect()
        assertEquals(AccessMethod.SHIZUKU, a.method)
        assertEquals(ShizukuStatus.ALLOWED, a.shizukuStatus)
    }

    @Test fun shizukuThatStopsWorkingSaysWhy() {
        prefs.values["method"] = "SHIZUKU"
        val gate = FakeShizuku(running = false, mayRun = true).also { shizuku = it }
        val a = access()
        assertTrue(a.withoutSetup)
        val e = assertThrows(SetupNeededException::class.java) { a.connect() }
        assertTrue(e.message!!, e.message!!.startsWith("Shizuku isn't running."))
        assertTrue(e.message!!, e.message!!.endsWith(" Otherwise, set up Wireless debugging."))
        // Not tried again by itself...
        assertFalse(a.withoutSetup)
        // ...until it's back.
        gate.running = true
        assertTrue(a.shizukuAppeared())
        a.connect()
        assertEquals(AccessMethod.SHIZUKU, a.method)
    }

    @Test fun wirelessDebuggingFailingDoesNotHideWhyShizukuFailed() {
        prefs.values["method"] = "SHIZUKU"
        shizuku = FakeShizuku(running = false)
        val off = object : CardAccess.Adb by adb {
            override val wasPaired = true
            override fun connect(port: Int?, authorize: Boolean) = throw IOException("Couldn't find Wireless debugging.")
        }
        val a = CardAccess(prefs, OmapiApduBackend { FakeReaders(cards) }, RootApduBackend({ "x" }, { su() }), off,
            findSu = { false }, shizuku = ShizukuApduBackend({ "x" }, shizuku))
        val e = assertThrows(SetupNeededException::class.java) { a.connect() }
        assertTrue(e.message!!, e.message!!.startsWith("Shizuku isn't running."))
    }

    @Test fun savedShizukuThatIsGoneFallsBack() {
        prefs.values["method"] = "SHIZUKU"
        adb.paired = true
        shizuku = FakeShizuku(installed = false, running = false)
        val a = access()
        a.connect()
        assertEquals(AccessMethod.ADB, a.method)
    }

    @Test fun methodFromAnotherBuildCountsAsNone() {
        prefs.values["method"] = "SOMETHING_NEWER"
        assertNull(access().method)
    }

    @Test fun refusalIsRememberedBecauseShizukuDoesNot() {
        adb.paired = true
        val gate = FakeShizuku(answer = false).also { shizuku = it }
        val first = access()
        first.connect()
        assertEquals(AccessMethod.ADB, first.method)
        assertEquals(1, gate.asks)
        assertEquals(ShizukuStatus.DECLINED, first.shizukuStatus)
        // Not at the next start...
        val later = access()
        later.connect()
        assertEquals(1, gate.asks)
        // ...nor on Reconnect, for someone who reaches the card another way...
        later.disconnect()
        later.connect(again = true)
        assertEquals(1, gate.asks)
        // ...but "Use Shizuku" does.
        gate.answer = true
        later.useShizuku()
        assertEquals(2, gate.asks)
        assertEquals(AccessMethod.SHIZUKU, later.method)
        assertEquals(ShizukuStatus.ALLOWED, later.shizukuStatus)
    }

    @Test fun noAnswerIsNotARefusal() {
        adb.paired = true
        val gate = FakeShizuku(answer = null).also { shizuku = it }
        val a = access()
        a.connect()
        assertEquals(AccessMethod.ADB, a.method)
        assertEquals(ShizukuStatus.NOT_ASKED, a.shizukuStatus)
        assertTrue(a.shizukuAppeared())
        assertFalse(a.shizukuAppeared())
        // Answered after the app stopped waiting: that's the news, though Shizuku was worth asking all along.
        gate.mayRun = true
        assertEquals(ShizukuStatus.ALLOWED, a.shizukuStatus)
        assertTrue(a.shizukuAppeared())
        assertFalse(a.shizukuAppeared())
    }

    @Test fun answerAfterTheWaitEndedStillConnects() {
        val gate = FakeShizuku(answer = null).also { shizuku = it }
        val a = access()
        assertThrows(SetupNeededException::class.java) { a.connect() }
        a.shizukuAppeared()
        gate.mayRun = true
        assertTrue(a.shizukuAppeared())
        assertTrue(a.withoutSetup)
        a.connect()
        assertEquals(AccessMethod.SHIZUKU, a.method)
    }

    @Test fun slowSimServiceAsksNoQuestion() {
        val gate = FakeShizuku().also { shizuku = it }
        var slow = true
        val a = CardAccess(prefs, OmapiApduBackend {
            if (slow) throw OmapiApduBackend.BindTimeoutException() else FakeReaders(mapOf(1 to FakeCard.OPEN))
        }, RootApduBackend({ "x" }, { su() }), adb, findSu = { false }, shizuku = ShizukuApduBackend({ "x" }, gate))
        assertThrows(SetupNeededException::class.java) { a.connect() }
        assertEquals(0, gate.asks) // the card may well let this app in, as it does here
        assertTrue(a.withoutSetup)
        slow = false
        a.connect()
        assertEquals(AccessMethod.DIRECT, a.method)
        assertEquals(0, gate.asks)
    }

    @Test fun slowSimServiceDoesNotHoldUpShizukuWhenThatIsTheWayIn() {
        prefs.values["method"] = "SHIZUKU"
        shizuku = FakeShizuku(mayRun = true)
        val a = CardAccess(prefs, OmapiApduBackend { throw OmapiApduBackend.BindTimeoutException() },
            RootApduBackend({ "x" }, { su() }), adb, findSu = { false }, shizuku = ShizukuApduBackend({ "x" }, shizuku))
        a.connect()
        assertEquals(AccessMethod.SHIZUKU, a.method)
    }

    @Test fun nobodyInTheAppMeansNoQuestion() {
        val gate = FakeShizuku().also { shizuku = it }
        adb.paired = true
        val a = access()
        a.connect(mayAsk = false)
        assertEquals(0, gate.asks) // it would open over whatever else is on screen
        assertEquals(AccessMethod.ADB, a.method)
        assertTrue(a.shizukuQuestionWaits)
        assertTrue(a.withoutSetup) // not ruled out
        a.stop()
        a.connect()
        assertEquals(1, gate.asks)
        assertEquals(AccessMethod.SHIZUKU, a.method)
        assertFalse(a.shizukuQuestionWaits)
    }

    @Test fun alreadyAllowedNeedsNobodyInTheApp() {
        val gate = FakeShizuku(mayRun = true).also { shizuku = it }
        val a = access()
        a.connect(mayAsk = false)
        assertEquals(AccessMethod.SHIZUKU, a.method)
        assertEquals(0, gate.asks)
        assertFalse(a.shizukuQuestionWaits)
    }

    @Test fun stoppedShizukuHasNoQuestionToLeave() {
        shizuku = FakeShizuku(running = false)
        val a = access()
        assertThrows(SetupNeededException::class.java) { a.connect(mayAsk = false) }
        assertFalse(a.shizukuQuestionWaits)
    }

    @Test fun uninstalledShizukuIsNotToBeStarted() {
        prefs.values["method"] = "SHIZUKU"
        shizuku = FakeShizuku(installed = false, running = false)
        val e = assertThrows(SetupNeededException::class.java) { access().connect() }
        assertTrue(e.message!!, e.message!!.startsWith("Shizuku isn't on this phone any more."))
    }

    @Test fun allowedInShizukuItselfClearsTheRefusal() {
        val gate = FakeShizuku(answer = false).also { shizuku = it }
        val a = access()
        assertThrows(SetupNeededException::class.java) { a.connect() }
        assertFalse(a.shizukuAppeared())
        gate.mayRun = true // in Shizuku's own app, which sends no word
        assertTrue(a.shizukuAppeared())
        a.connect()
        assertEquals(AccessMethod.SHIZUKU, a.method)
        assertEquals(1, gate.asks)
    }

    @Test fun reconnectAsksWhenShizukuIsTheWayIn() {
        prefs.values["method"] = "SHIZUKU"
        prefs.values["shizuku-declined"] = true
        val gate = FakeShizuku().also { shizuku = it }
        val a = access()
        assertThrows(SetupNeededException::class.java) { a.connect() }
        assertEquals(0, gate.asks)
        a.connect(again = true)
        assertEquals(1, gate.asks)
        assertEquals(AccessMethod.SHIZUKU, a.method)
    }

    @Test fun cancelReachesTheQuestion() {
        val gate = FakeShizuku().also { shizuku = it }
        access().cancelConnect()
        assertEquals(1, gate.cancels)
    }

    @Test fun reconnectWithinAnOperationStaysOnShizuku() {
        adb.paired = true
        cards = mapOf(1 to FakeCard.OPEN) // would win if it were tried
        val gate = FakeShizuku(mayRun = true).also { shizuku = it }
        val a = access()
        a.useShizuku()
        a.disconnect() // the helper dropped during a switch
        a.reconnect()
        assertEquals(AccessMethod.SHIZUKU, a.method)
        assertEquals(2, gate.starts)
        assertEquals(0, suStarts)
        assertEquals(null, readers)
        assertTrue(adb.connects.isEmpty())
    }

    @Test fun reconnectWithinAnOperationNeverAsks() {
        adb.paired = true
        val gate = FakeShizuku(mayRun = true).also { shizuku = it }
        val a = access()
        a.connect()
        a.disconnect()
        gate.mayRun = false
        assertThrows(ShizukuApduBackend.DeniedException::class.java) { a.reconnect() }
        assertEquals(0, gate.asks)
        assertEquals(AccessMethod.SHIZUKU, a.method) // and no other way in midway
        assertTrue(adb.connects.isEmpty())
    }

    @Test fun reconnectOverAdbLeavesShizukuAlone() {
        adb.paired = true
        val gate = FakeShizuku(installed = true, running = false).also { shizuku = it }
        val a = access()
        a.connect()
        assertEquals(AccessMethod.ADB, a.method)
        gate.running = true
        gate.mayRun = true
        adb.ready = false
        a.reconnect()
        assertEquals(AccessMethod.ADB, a.method)
        assertEquals(0, gate.starts)
    }

    @Test fun useShizukuKeepsCardAccessWhenRefused() {
        adb.paired = true
        shizuku = FakeShizuku(answer = false)
        val a = access()
        a.connect()
        assertEquals(AccessMethod.ADB, a.method)
        assertThrows(ShizukuApduBackend.DeniedException::class.java) { a.useShizuku() }
        assertTrue(a.isReady())
        assertEquals(AccessMethod.ADB, a.method)
    }

    @Test fun useShizukuEndsTheWayItReplaces() {
        shizuku = FakeShizuku(running = true)
        su = { FakeSu(helper = helperAs(0)) }
        val a = access(suFound = true)
        a.connect()
        assertEquals(AccessMethod.ROOT, a.method)
        a.useShizuku()
        assertEquals(AccessMethod.SHIZUKU, a.method)
        a.connectRoot() // and back
        assertEquals(AccessMethod.ROOT, a.method)
    }

    @Test fun directAccessUsesShizukuForSimSettings() {
        cards = mapOf(1 to FakeCard.OPEN)
        adb.paired = true // not needed now
        val gate = FakeShizuku(mayRun = true).also { shizuku = it }
        val a = access()
        a.connect()
        assertEquals(AccessMethod.DIRECT, a.method)
        a.shizukuAppeared() // after every connection, off the main thread
        assertTrue(a.canSetUpSim)
        assertTrue(a.useSim(1, "8944", data = true, roaming = true).applied)
        assertEquals(AccessMethod.DIRECT, a.method)
        assertEquals(1, gate.starts)
        assertTrue(adb.connects.isEmpty())
        assertTrue(a.useSim(1, "8944", data = false, roaming = true).applied)
        assertEquals(1, gate.starts) // the same helper
        a.stop()
        assertFalse(a.isReady())
    }

    @Test fun directAccessGetsShizukuAllowedWithoutLeavingDirect() {
        cards = mapOf(1 to FakeCard.OPEN)
        val gate = FakeShizuku().also { shizuku = it }
        val a = access()
        a.connect()
        assertFalse(a.canSetUpSim)
        a.allowShizuku() // Details › Mobile data and roaming
        assertEquals(AccessMethod.DIRECT, a.method)
        assertTrue(a.canSetUpSim)
        assertEquals(0, gate.starts)
    }

    @Test fun refusingForSimSettingsIsRemembered() {
        cards = mapOf(1 to FakeCard.OPEN)
        shizuku = FakeShizuku(answer = false)
        val a = access()
        a.connect()
        assertThrows(ShizukuApduBackend.DeniedException::class.java) { a.allowShizuku() }
        assertEquals(ShizukuStatus.DECLINED, a.shizukuStatus)
        assertEquals(AccessMethod.DIRECT, a.method)
    }

    @Test fun switchesNeverAskShizuku() {
        cards = mapOf(1 to FakeCard.OPEN)
        val gate = FakeShizuku().also { shizuku = it }
        val a = access()
        a.connect()
        assertThrows(CardAccess.NoSimSettingsException::class.java) { a.useSim(1, "8944", data = true, roaming = true) }
        assertEquals(0, gate.asks)
    }

    @Test fun simSettingsFallBackToAdbWhenShizukuFails() {
        cards = mapOf(1 to FakeCard.OPEN)
        adb.paired = true
        shizuku = FakeShizuku(mayRun = true, process = { throw IOException("stuck") })
        val a = access()
        a.connect()
        assertTrue(a.useSim(1, "8944", data = true, roaming = true).applied)
        assertEquals(listOf<Int?>(null), adb.connects)
    }

    @Test fun simSettingsSayWhenShizukuWontStartTheHelper() {
        cards = mapOf(1 to FakeCard.OPEN)
        shizuku = FakeShizuku(mayRun = true, process = { throw IOException("stuck") })
        val a = access()
        a.connect()
        val e = assertThrows(CardAccess.NoSimSettingsException::class.java) { a.useSim(1, "8944", data = true, roaming = true) }
        assertTrue(e.shizukuFailed)
        assertTrue(e.shizukuCantStart)
        assertFalse(e.needsPairing)
    }

    @Test fun simSettingsSayWhenShizukuStoppedMeanwhile() {
        cards = mapOf(1 to FakeCard.OPEN)
        val gate = FakeShizuku(mayRun = true).also { shizuku = it }
        val a = access()
        a.connect()
        a.shizukuAppeared()
        gate.running = false // during the switch
        val e = assertThrows(CardAccess.NoSimSettingsException::class.java) { a.useSim(1, "8944", data = true, roaming = true) }
        assertTrue(e.shizukuFailed)
        assertFalse(e.shizukuCantStart)
        assertFalse(e.needsPairing) // it never was about a pairing
    }

    @Test fun canSetUpSimAsksNothingOnTheMainThread() {
        cards = mapOf(1 to FakeCard.OPEN)
        var reads = 0
        val gate = object : ShizukuApduBackend.Gate by FakeShizuku() {
            override val allowed: Boolean get() { reads++; return false }
        }
        shizuku = gate
        val a = access()
        a.connect()
        reads = 0
        assertFalse(a.canSetUpSim)
        assertEquals(0, reads)
    }

    @Test fun backgroundHelperEndsOnceShizukuWorks() {
        adb.helper = true
        shizuku = FakeShizuku(mayRun = true)
        access().connect()
        assertEquals(1, adb.stops)
    }

    @Test fun backgroundHelperStaysWhenShizukuDoesNotWork() {
        adb.helper = true
        shizuku = FakeShizuku(running = false)
        val a = access()
        a.connect()
        assertEquals(AccessMethod.ADB, a.method)
        assertEquals(0, adb.stops)
    }

    @Test fun stopEndsThisAppsHelperAndCarriesOn() {
        var su: FakeSu? = null
        shizuku = FakeShizuku(mayRun = true, process = { FakeSu(serverGone = true, helper = helperAs(2000)).also { su = it } })
        val a = access()
        a.connect()
        a.stop() // Shizuku's server already gone
        assertFalse(a.isReady())
        assertTrue(su!!.destroyed)
        assertEquals(1, adb.stops)
    }

    @Test fun closeEndsThisAppsHelper() {
        var su: FakeSu? = null
        shizuku = FakeShizuku(mayRun = true, process = { FakeSu(helper = helperAs(2000)).also { su = it } })
        val a = access()
        a.connect()
        a.close()
        assertTrue(su!!.destroyed)
    }

    @Test fun readingCoversShizuku() {
        shizuku = FakeShizuku(mayRun = true)
        val a = access()
        a.connect()
        assertEquals(3, a.reading { 3 })
    }

    // ---------------- Android 10 and older: the debugging port a computer turned on ----------------

    @Test fun android10SetsUpWithTheFirstConnection() {
        adb.tcpOpen = true // "adb tcpip 5555" was run
        val a = access(sdk = 29)
        // At start-up, never set up: nothing is tried, so the phone asks nothing nobody started.
        assertThrows(SetupNeededException::class.java) { a.connect() }
        assertTrue(adb.connects.isEmpty())
        // "Connect", at the end of setup: tried although never paired, and the phone may ask to allow the app.
        a.connect(again = true)
        assertEquals(AccessMethod.ADB, a.method)
        assertEquals(listOf(true), adb.authorized)
        // Later starts connect by themselves, without a question.
        a.disconnect()
        a.connect()
        assertEquals(listOf(true, false), adb.authorized)
        // So does a reconnect within an operation.
        adb.ready = false
        a.reconnect()
        assertEquals(listOf(true, false, false), adb.authorized)
    }

    @Test fun android11AndNewerNeverAsk() {
        adb.paired = true
        access(sdk = 30).connect(again = true)
        assertEquals(listOf(false), adb.authorized) // Wireless debugging pairs instead
        val unpaired = FakeAdb()
        val a = CardAccess(MemoryPrefs(), OmapiApduBackend { FakeReaders(cards) }, RootApduBackend({ "x" }, { su() }),
            unpaired, findSu = { false }, sdk = 30)
        assertThrows(SetupNeededException::class.java) { a.connect(again = true) }
        assertTrue(unpaired.connects.isEmpty()) // can't work before pairing
    }

    @Test fun phoneThatDidNotAllowTheAppSaysSo() {
        adb.tcpOpen = true
        adb.paired = true // set up before
        adb.allows = false
        val e = assertThrows(AllowNeededException::class.java) { access(sdk = 29).connect(again = true) }
        assertTrue(e.asked)
        // Not hidden behind why an earlier way in failed: only people can fix it, there and then.
        prefs.values["method"] = "DIRECT"
        cards = emptyMap()
        assertThrows(AllowNeededException::class.java) { access(sdk = 29).connect(again = true) }
    }

    @Test fun otherwiseSetUpWithAComputerBelowAndroid11() {
        prefs.values["method"] = "DIRECT"
        val old = assertThrows(SetupNeededException::class.java) { access(sdk = 29).connect() }
        assertTrue(old.message!!, old.message!!.endsWith(" Otherwise, set it up with a computer."))
        val new = assertThrows(SetupNeededException::class.java) { access(sdk = 30).connect() }
        assertTrue(new.message!!, new.message!!.endsWith(" Otherwise, set up Wireless debugging."))
    }

    @Test fun simSettingsWithDirectAccessAskWhileSomeoneIsThere() {
        cards = mapOf(1 to FakeCard.OPEN)
        adb.tcpOpen = true
        val a = access(sdk = 29)
        a.connect()
        assertEquals(AccessMethod.DIRECT, a.method)
        assertFalse(a.canSetUpSim)
        a.setUpSimSettings() // Details › Mobile data and roaming
        assertEquals(listOf(true), adb.authorized)
        assertTrue(a.canSetUpSim)
        assertEquals(AccessMethod.DIRECT, a.method) // the card itself stays on direct access
        adb.ready = false
        assertTrue(a.useSim(1, "8944", data = true, roaming = true).applied)
        assertEquals(listOf(true, false), adb.authorized) // a switch later: no question
    }

    // ---------------- a way chosen in this app's Settings ----------------

    /** Each way that's tried, in turn. */
    private val tried = ArrayList<String>()

    /** Every way there, each failing and saying when it's tried: root turned down, a card that refuses this app,
     *  Shizuku that allows this app but won't start its helper, and Wireless debugging paired but off. */
    private fun everyWayFails(): CardAccess {
        su = { tried += "root"; FakeSu(exitAtOnce = true) { null } }
        shizuku = FakeShizuku(mayRun = true, process = { tried += "shizuku"; throw IOException("stuck") })
        val off = object : CardAccess.Adb by adb {
            override val wasPaired = true
            override fun connect(port: Int?, authorize: Boolean) { tried += "adb"; throw IOException("Couldn't find Wireless debugging.") }
        }
        return CardAccess(prefs, OmapiApduBackend { tried += "direct"; FakeReaders(cards) },
            RootApduBackend({ "x" }, { su() }), off, findSu = { true }, shizuku = ShizukuApduBackend({ "x" }, shizuku))
    }

    @Test fun aChosenWayGoesFirstAndOnlyWaysThatAskNothingBackItUp() {
        val orders = mapOf(
            null to listOf("root", "direct", "shizuku", "adb"), // as ever
            AccessMethod.DIRECT to listOf("direct", "shizuku", "adb"),
            AccessMethod.ROOT to listOf("root", "shizuku", "adb", "direct"),
            AccessMethod.SHIZUKU to listOf("shizuku", "adb", "direct"),
            AccessMethod.ADB to listOf("adb", "shizuku", "direct"))
        for ((way, order) in orders) {
            prefs.values.clear()
            tried.clear()
            val a = everyWayFails()
            a.prefer(way)
            assertThrows(IOException::class.java) { a.connect() }
            assertEquals("$way", order, tried)
        }
    }

    @Test fun waysBehindAChosenOneNeverAsk() {
        // Root that would ask, and Shizuku that would: neither was chosen.
        su = { FakeSu(helper = helperAs(0)) }
        val gate = FakeShizuku(installed = true, running = true, mayRun = false).also { shizuku = it }
        for (way in listOf(AccessMethod.ADB, AccessMethod.DIRECT)) {
            prefs.values.clear()
            val a = access(suFound = true)
            a.prefer(way)
            assertThrows(SetupNeededException::class.java) { a.connect() }
            assertEquals(0, suStarts)
            assertEquals(0, gate.asks)
            assertEquals(0, gate.waits) // nor waited for
        }
        // Not even when someone reconnects.
        val a = access(suFound = true)
        assertThrows(SetupNeededException::class.java) { a.connect(again = true) }
        assertEquals(0, suStarts)
        assertEquals(0, gate.asks)
    }

    @Test fun shizukuBehindAChosenWayIsWaitedForOnceWhereDirectAccessComesNext() {
        // A card that lets this app in, and Shizuku that allows it but hands over its connection only after the start.
        cards = mapOf(1 to FakeCard.OPEN)
        val gate = FakeShizuku(running = false, mayRun = true, arrives = true).also { shizuku = it }
        prefs.values[CardAccess.PREFERRED] = "ADB"
        adb.paired = true
        val a = access()
        a.connect(viaAdb = false) // Wireless debugging is off
        assertEquals(AccessMethod.SHIZUKU, a.method) // it reaches every card, where direct access may miss one
        assertEquals(1, gate.waits)
        // Once: Shizuku that isn't running then isn't waited for again.
        gate.running = false
        a.disconnect()
        a.connect(viaAdb = false)
        assertEquals(AccessMethod.DIRECT, a.method)
        assertEquals(1, gate.waits)
        // Behind a chosen Direct, which came first, there's nothing to wait for.
        prefs.values.clear()
        prefs.values[CardAccess.PREFERRED] = "DIRECT"
        cards = mapOf(1 to FakeCard.LOCKED)
        gate.running = false
        assertThrows(SetupNeededException::class.java) { access().connect(viaAdb = false) }
        assertEquals(1, gate.waits)
    }

    @Test fun usbDebuggingBehindAChosenWayOnlyAsksOnItsOwnScreens() {
        prefs.values[CardAccess.PREFERRED] = "DIRECT"
        adb.tcpOpen = true // a computer turned the debugging port on, and the phone never allowed this app
        val a = access(sdk = 29)
        assertThrows(SetupNeededException::class.java) { a.connect(again = true) }
        assertTrue(adb.authorized.isEmpty())
        // Its setup screen's "Connect": the phone may ask there.
        a.connect(again = true, forAdb = true)
        assertEquals(AccessMethod.ADB, a.method)
        assertEquals(listOf(true), adb.authorized)
        // Allowed now: tried behind the chosen way too, without asking.
        a.disconnect()
        a.connect(again = true)
        assertEquals(AccessMethod.ADB, a.method)
        assertEquals(listOf(true, false), adb.authorized)
    }

    @Test fun aChosenWayThatFailsFallsBackAndSaysWhy() {
        prefs.values[CardAccess.PREFERRED] = "SHIZUKU"
        adb.paired = true
        val gate = FakeShizuku(running = false, mayRun = true).also { shizuku = it }
        val a = access()
        a.connect()
        assertEquals(AccessMethod.ADB, a.method)
        assertEquals(AccessMethod.SHIZUKU, a.preferred) // the choice stands for next time
        assertTrue(a.preferredFailure is ShizukuApduBackend.NotRunningException)
        assertEquals(1, gate.waits) // the chosen way is waited for, as ever
        // Started again: Reconnect takes the chosen way.
        gate.running = true
        a.disconnect()
        a.connect(again = true)
        assertEquals(AccessMethod.SHIZUKU, a.method)
        assertNull(a.preferredFailure)
    }

    @Test fun directAccessComesLastBehindAChosenWay() {
        prefs.values[CardAccess.PREFERRED] = "SHIZUKU"
        shizuku = FakeShizuku(running = false)
        cards = mapOf(1 to FakeCard.OPEN)
        adb.paired = true
        val a = access()
        a.connect()
        assertEquals(AccessMethod.ADB, a.method) // it reaches every card, where direct access may miss one
        assertNull(readers)
        // With nothing else, direct access it is.
        adb.paired = false
        val b = access()
        b.connect()
        assertEquals(AccessMethod.DIRECT, b.method)
        assertTrue(b.preferredFailure is ShizukuApduBackend.NotRunningException)
    }

    @Test fun chosenWirelessDebuggingThatIsOffCostsNothing() {
        prefs.values[CardAccess.PREFERRED] = "ADB"
        adb.paired = true
        cards = mapOf(1 to FakeCard.OPEN)
        val a = access()
        a.connect(viaAdb = false) // as the app connects while Wireless debugging is off
        assertTrue(adb.connects.isEmpty())
        assertEquals(AccessMethod.DIRECT, a.method)
        assertSame(CardAccess.NotTried, a.preferredFailure)
        assertNull(a.reason(CardAccess.NotTried))
        // A fallback that worked once doesn't cover up the chosen way next time.
        a.disconnect()
        a.connect()
        assertEquals(AccessMethod.ADB, a.method)
        assertNull(a.preferredFailure)
    }

    @Test fun chosenAdbSaysWhatsWrongWithItWhenNothingElseConnects() {
        prefs.values[CardAccess.PREFERRED] = "ADB"
        for (failure in listOf(PairingRequiredException(), DebuggingPortClosedException(), NearbyDevicesNeededException())) {
            val broken = object : CardAccess.Adb by adb {
                override val wasPaired = true
                override fun connect(port: Int?, authorize: Boolean) = throw failure
            }
            val a = CardAccess(prefs, OmapiApduBackend { FakeReaders(cards) }, RootApduBackend({ "x" }, { su() }), broken,
                findSu = { false }, sdk = 29)
            assertSame(failure, assertThrows(IOException::class.java) { a.connect(again = true) })
            assertSame(failure, a.preferredFailure)
        }
    }

    @Test fun aChosenWayIsWorthConnectingUntilItFailed() {
        prefs.values[CardAccess.PREFERRED] = "DIRECT"
        val a = access(suFound = true)
        assertTrue(a.withoutSetup)
        val e = assertThrows(SetupNeededException::class.java) { a.connect() }
        assertTrue(e.message!!, e.message!!.startsWith("Couldn't open your card directly"))
        assertTrue(a.preferredFailure is OmapiApduBackend.UnavailableException)
        assertTrue(a.directRefused)
        // Root was never tried, but it's no way in behind a chosen one: nothing left to try by itself.
        assertFalse(a.withoutSetup)
        assertEquals(0, suStarts)
        cards = mapOf(1 to FakeCard.OPEN)
        a.connect(again = true)
        assertEquals(AccessMethod.DIRECT, a.method)
        assertFalse(a.directRefused)
    }

    @Test fun switchingConnectsTheNewWayBeforeEndingTheOld() {
        cards = mapOf(1 to FakeCard.OPEN)
        adb.paired = true
        adb.helper = true // up for SIM settings
        val a = access()
        a.connect()
        assertEquals(AccessMethod.DIRECT, a.method)
        a.switchTo(AccessMethod.ADB)
        assertEquals(AccessMethod.ADB, a.method)
        assertTrue(a.isReady())
        assertTrue(readers!!.closed) // direct access ended once the new way worked
        // And back: the helper stays, for SIM settings.
        a.switchTo(AccessMethod.DIRECT)
        assertEquals(AccessMethod.DIRECT, a.method)
        assertEquals(0, adb.stops)
        assertTrue(adb.helper)
        assertFalse(adb.isReady())
        assertTrue(a.canSetUpSim)
        // The way in use already: nothing to do.
        val opened = readers
        a.switchTo(AccessMethod.DIRECT)
        assertSame(opened, readers)
    }

    @Test fun switchingToUsbDebuggingCanTakeThePortTypedAfterAdbTcpip() {
        cards = mapOf(1 to FakeCard.OPEN)
        adb.paired = true // allowed before
        val a = access(sdk = 29)
        a.connect(viaAdb = false)
        assertEquals(AccessMethod.DIRECT, a.method)
        a.switchTo(AccessMethod.ADB, port = 5556)
        assertEquals(AccessMethod.ADB, a.method)
        assertEquals(listOf<Int?>(5556), adb.connects)
        assertEquals(listOf(true), adb.authorized) // the phone may ask to allow this app
    }

    @Test fun aSwitchThatFailsLeavesCardAccessAsItWas() {
        adb.paired = true
        val a = access()
        a.connect()
        assertEquals(AccessMethod.ADB, a.method)
        assertThrows(OmapiApduBackend.UnavailableException::class.java) { a.switchTo(AccessMethod.DIRECT) }
        assertTrue(a.directRefused)
        assertThrows(ShizukuApduBackend.NotRunningException::class.java) { a.switchTo(AccessMethod.SHIZUKU) }
        assertThrows(RootApduBackend.NotRootedException::class.java) { a.switchTo(AccessMethod.ROOT) }
        assertTrue(a.isReady())
        assertEquals(AccessMethod.ADB, a.method)
        assertEquals(1, adb.connects.size)
    }

    @Test fun aCancelledSwitchLeavesCardAccessAsItWas() {
        cards = mapOf(1 to FakeCard.OPEN)
        adb.paired = true
        val gate = FakeShizuku(mayRun = true).also { shizuku = it }
        val a = access()
        a.connect(viaAdb = false)
        assertEquals(AccessMethod.DIRECT, a.method)
        // Cancel tapped while Wireless debugging connected, and while Shizuku started this app's helper.
        assertThrows(ConnectCancelledException::class.java) { a.switchTo(AccessMethod.ADB) { adb.connects.isNotEmpty() } }
        assertFalse(adb.isReady())
        assertThrows(ConnectCancelledException::class.java) { a.switchTo(AccessMethod.SHIZUKU) { gate.starts > 0 } }
        assertEquals(AccessMethod.DIRECT, a.method)
        assertEquals("DIRECT", prefs.values[CardAccess.METHOD])
        assertTrue(a.isReady())
        assertFalse(readers!!.closed)
        assertNull(a.preferred)
    }

    @Test fun switchingToShizukuAsksItWhenItDoesNotAllowThisAppYet() {
        cards = mapOf(1 to FakeCard.OPEN)
        val gate = FakeShizuku().also { shizuku = it }
        val a = access()
        a.connect()
        a.switchTo(AccessMethod.SHIZUKU)
        assertEquals(1, gate.asks)
        assertEquals(AccessMethod.SHIZUKU, a.method)
        // Already allowed, as for SIM settings: no question.
        a.switchTo(AccessMethod.DIRECT)
        a.switchTo(AccessMethod.SHIZUKU)
        assertEquals(1, gate.asks)
    }

    @Test fun automaticAgainTriesOnlyTheWaysAboveTheOneInUse() {
        val gate = FakeShizuku(mayRun = true).also { shizuku = it }
        prefs.values[CardAccess.PREFERRED] = "SHIZUKU"
        adb.paired = true
        val a = access()
        a.connect()
        assertEquals(AccessMethod.SHIZUKU, a.method)
        // The card refuses direct access: Shizuku stays, and nothing below it is tried.
        a.switchTo(null)
        assertEquals(AccessMethod.SHIZUKU, a.method)
        assertTrue(a.isReady())
        assertEquals(1, gate.starts)
        assertTrue(adb.connects.isEmpty())
        // A card that lets this app in: direct access, which comes first.
        cards = mapOf(1 to FakeCard.OPEN)
        a.switchTo(null)
        assertEquals(AccessMethod.DIRECT, a.method)
        assertTrue(a.isReady())
        a.prefer(null)
        assertNull(a.preferred)
    }

    @Test fun automaticAgainAsksNothingNew() {
        // Root turned down before, Shizuku not allowed yet: neither asked. Not connected: the ways that are set up.
        prefs.values[CardAccess.ROOT_DECLINED] = true
        val gate = FakeShizuku().also { shizuku = it }
        adb.paired = true
        val a = access(suFound = true)
        a.switchTo(null)
        assertEquals(AccessMethod.ADB, a.method)
        assertEquals(0, suStarts)
        assertEquals(0, gate.asks)
    }

    @Test fun cardsThatRefusedAreAddedToAndReplacedOnReconnect() {
        // A 5ber in SIM 1, a card that lets this app in in SIM 2.
        cards = mapOf(0 to FakeCard.LOCKED, 1 to FakeCard.OPEN)
        val fiveBer = Hex.encode(byteArrayOf(0x3B, FakeCard.LOCKED.ordinal.toByte()))
        access().connect()
        assertEquals(setOf(fiveBer), prefs.values[CardAccess.REFUSED])
        // The next start asks it nothing.
        access().connect()
        assertEquals(listOf(Lpa.ISD_R), readers!!.opened)
        // A card from before stays listed while another refuses...
        prefs.values[CardAccess.REFUSED] = setOf("3BFF")
        access().connect()
        assertEquals(setOf("3BFF", fiveBer), prefs.values[CardAccess.REFUSED])
        // ...until someone reconnects, which asks them all.
        access().connect(again = true)
        assertEquals(setOf(fiveBer), prefs.values[CardAccess.REFUSED])
        // Also when nothing lets this app in.
        prefs.values[CardAccess.REFUSED] = setOf("3BFF")
        cards = mapOf(0 to FakeCard.LOCKED)
        assertThrows(SetupNeededException::class.java) { access().connect() }
        assertEquals(setOf("3BFF", fiveBer), prefs.values[CardAccess.REFUSED])
    }

    @Test fun aCardOfTheMakeOfOneThatRefusedIsStillAskedAtTheNextStart() {
        // Two cards of one make, so one ATR: the one in SIM 1 refuses this app, the one in SIM 2 lets it in.
        cards = mapOf(0 to FakeCard.LOCKED, 1 to FakeCard.OPEN)
        atrs = mapOf(0 to "3B9F", 1 to "3B9F")
        access().connect()
        assertNull(prefs.values[CardAccess.REFUSED])
        val a = access()
        a.connect()
        assertEquals(AccessMethod.DIRECT, a.method)
    }

    @Test fun shizukuThatCantStartTheHelperIsNotTriedAgainByItself() {
        prefs.values["method"] = "SHIZUKU"
        val gate = FakeShizuku(mayRun = true, process = { throw IOException("stuck") }).also { shizuku = it }
        val a = access()
        val e = assertThrows(SetupNeededException::class.java) { a.connect() }
        assertTrue(e.message!!, e.message!!.startsWith("Shizuku couldn't start"))
        assertFalse(a.withoutSetup) // or it would try again at every return to the app
        assertThrows(SetupNeededException::class.java) { a.connect(again = true) } // but Reconnect does
        assertEquals(2, gate.starts)
        assertTrue(a.shizukuAppeared()) // and so does Shizuku changing
        assertTrue(a.withoutSetup)
    }

    @Test fun theChoiceIsKeptForNextTime() {
        val a = access()
        assertNull(a.preferred)
        a.prefer(AccessMethod.SHIZUKU)
        assertEquals(AccessMethod.SHIZUKU, access().preferred)
        a.prefer(null)
        assertNull(access().preferred)
        assertFalse(CardAccess.PREFERRED in prefs.values)
        prefs.values[CardAccess.PREFERRED] = "SOMETHING_NEWER"
        assertNull(access().preferred)
    }

    @Test fun directAccessIsOfferedWhereThePhoneHasSimReaders() {
        var asked = 0
        fun access(supported: Boolean, readers: Map<Int, FakeCard>) = CardAccess(prefs,
            OmapiApduBackend { FakeReaders(readers) }, RootApduBackend({ "x" }, { su() }), adb, findSu = { false },
            directSupported = { asked++; supported })
        val a = access(true, emptyMap())
        assertEquals(0, asked) // not at start
        assertTrue(a.directAvailable)
        assertThrows(SetupNeededException::class.java) { a.connect() }
        assertFalse(a.directAvailable) // readers, but none for SIM cards
        assertEquals(1, asked)
        assertFalse(access(false, cards).directAvailable)
    }
}
