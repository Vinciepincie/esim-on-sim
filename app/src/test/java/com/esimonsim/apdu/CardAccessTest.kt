package com.esimonsim.apdu

import com.esimonsim.lpa.Lpa
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    private var su: () -> Process = { throw IOException("Cannot run program \"su\": error=2, No such file or directory") }

    private var readers: FakeReaders? = null
    private var shizuku: ShizukuApduBackend.Gate = CardAccess.NoShizuku

    /** Android 16 unless [sdk] says otherwise: 10 and older have no Wireless debugging. */
    private fun access(suFound: Boolean = false, sdk: Int = 36, found: () -> Boolean = { suFound }) = CardAccess(prefs,
        OmapiApduBackend { FakeReaders(cards).also { readers = it } },
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
}
