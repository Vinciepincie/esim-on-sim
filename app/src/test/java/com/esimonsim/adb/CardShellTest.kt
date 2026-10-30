package com.esimonsim.adb

import android.os.DeadObjectException
import android.os.IBinder
import com.esimonsim.apdu.ApduException
import com.esimonsim.lpa.Lpa
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** The helper against stand-ins for Android's telephony services: which call it picks on each version, how it
 *  finds the subscription that reaches a slot, and how it degrades where Android doesn't offer something. */
class CardShellTest {
    private val isdR = "A0000005591010FFFFFFFF89000001"
    private val iccid = "8944478600000000037"

    /** One call into a stand-in: the picked overload's name and parameter types, and the arguments. */
    data class Call(val method: String, val args: List<Any?>)

    /** Answers like the phone process: channels numbered from 1, [reply] to each APDU. */
    private inner class FakePhone : InvocationHandler {
        val calls = ArrayList<Call>()
        var reply: String? = "9000"
        var slots: Array<Any?>? = null
        /** What every call throws while set. */
        var failure: Exception? = null
        private var next = 1
        fun reset() { calls.clear(); next = 1 }
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            calls += Call(signature(method), args?.toList().orEmpty())
            failure?.let { throw it }
            return when {
                method.name.startsWith("iccOpenLogicalChannel") -> Response(next++, 1, byteArrayOf(0x90.toByte(), 0))
                method.name.startsWith("iccTransmitApdu") -> reply
                method.name.startsWith("iccCloseLogicalChannel") -> true
                method.name == "getUiccSlotsInfo" -> slots ?: throw SecurityException("READ_PRIVILEGED_PHONE_STATE")
                else -> null
            }
        }
    }

    /** Android's SIM list: [active] subscriptions by slot, and what getSubId says for each slot (by default
     *  Android's placeholder for an empty one). */
    private inner class FakeSubs : InvocationHandler {
        val calls = ArrayList<Call>()
        val active = HashMap<Int, SubInfo>()
        val subIds = HashMap<Int, IntArray>()
        var sims = 2
        /** Android 14+: slots past this one throw IllegalArgumentException. */
        var lastSlot = Int.MAX_VALUE
        var failure: Exception? = null
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            calls += Call(signature(method), args?.toList().orEmpty())
            return when (method.name) {
                "getActiveSubscriptionInfoForSimSlotIndex" -> {
                    failure?.let { throw it }
                    val slot = args!![0] as Int
                    if (slot > lastSlot) throw IllegalArgumentException("Invalid slot index")
                    active[slot]
                }
                "getSubId" -> (args!![0] as Int).let { subIds[it] ?: intArrayOf(-2 - it) }
                "getActiveSubInfoCountMax" -> sims
                "setDataRoaming" -> 0
                else -> null
            }
        }
    }

    /** A check the helper asked to repeat in the background: run here by hand, one at a time. */
    data class Watch(val ms: Long, val times: Int, val check: Callable<Boolean>)

    private val phone = FakePhone()
    private val subs = FakeSubs()
    /** How often the helper fetched each service: again after the phone process restarted. */
    private var phoneFetches = 0
    private var subFetches = 0
    /** The phone process is between lives: Android has no such service to give. */
    private var telephonyDown = false
    private val settings = HashMap<String, String>()
    private val commands = ArrayList<List<String>>()
    private val watches = ArrayList<Watch>()
    /** False: "settings put" changes nothing, as Android 6's command does on a failure, without saying so. */
    private var settingsKept = true

    private fun signature(m: Method) = "${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})"

    /** The API level of each stand-in below. */
    private val sdks = mapOf(Phone23::class.java to 23, Phone24::class.java to 24, PhoneOem24::class.java to 24,
        Phone26::class.java to 26, Phone27::class.java to 27, Phone28::class.java to 28, Phone29::class.java to 29,
        Phone31::class.java to 31)

    private fun shell(api: Class<*>, subApi: Class<*> = Sub23::class.java, request: Class<*>? = null) = CardShell(
        api, {
            if (telephonyDown) throw IllegalStateException("Telephony service unavailable")
            phoneFetches++
            Proxy.newProxyInstance(api.classLoader, arrayOf(api), phone)
        }, request, subApi, {
            subFetches++
            Proxy.newProxyInstance(subApi.classLoader, arrayOf(subApi), subs)
        },
        { command ->
            commands += command.toList()
            if (command[1] == "put") { if (settingsKept) settings[command[3]] = command[4]; "" }
            else settings[command[3]] ?: "null"
        },
        sdks[api] ?: 33,
    ) { ms, times, check -> watches += Watch(ms, times, check) }

    private fun android13() = shell(Phone33::class.java, Sub30::class.java, Request::class.java)

    private fun CardShell.open(slot: Int, port: Int = 0, aid: String = isdR) =
        handle(JSONObject().put("op", "open").put("slot", slot).put("port", port).put("aid", aid))

    /** GetEID, as the app sends it first. */
    private fun CardShell.transmit(slot: Int, channel: Int, port: Int = 0) = handle(JSONObject().put("op", "transmit")
        .put("slot", slot).put("port", port).put("channel", channel).put("cla", 0x80).put("ins", 0xE2).put("p1", 0x91)
        .put("p2", 0).put("p3", 6).put("data", "BF3E035C015A"))

    private val apdu = listOf(0x80, 0xE2, 0x91, 0, 6, "BF3E035C015A")

    private fun CardShell.close(slot: Int, channel: Int, port: Int = 0) =
        handle(JSONObject().put("op", "close").put("slot", slot).put("port", port).put("channel", channel))

    private fun CardShell.useSimAnswer(data: Boolean = true, roaming: Boolean = true) = handle(JSONObject()
        .put("op", "useSim").put("slot", 1).put("iccid", iccid).put("data", data).put("roaming", roaming))

    private fun CardShell.useSim(data: Boolean = true, roaming: Boolean = true) =
        useSimAnswer(data, roaming).getBoolean("applied")

    private fun CardShell.slots(): List<Map<String, Any>> = handle(JSONObject().put("op", "slots")).getJSONArray("slots")
        .let { all: JSONArray -> List(all.length()) { all.getJSONObject(it).let { s -> s.keys().asSequence().associateWith(s::get) } } }

    /** Opens, sends one APDU and closes in [slot]: the calls the phone got. */
    private fun CardShell.roundTrip(slot: Int): List<Call> {
        phone.reset()
        val opened = open(slot)
        assertEquals(1, opened.getInt("channel"))
        assertEquals("9000", opened.getString("select"))
        assertEquals("9000", transmit(slot, 1).getString("response"))
        close(slot, 1)
        return phone.calls.toList()
    }

    @Test fun android13AndLaterOpenWithARequestAndTransmitByPort() {
        val calls = android13().roundTrip(1)
        assertEquals(listOf("iccOpenLogicalChannel(Request)",
            "iccTransmitApduLogicalChannelByPort(int,int,int,int,int,int,int,int,String)",
            "iccCloseLogicalChannel(Request)"), calls.map { it.method })
        val request = calls[0].args.single() as Request
        assertEquals(listOf(1, 0, -1, isdR, "com.android.shell", 0),
            with(request) { listOf(slotIndex, portIndex, subId, aid, callingPackage, p2) })
        assertNotNull(request.binder) // Android closes the helper's channels when it goes away
        assertEquals(listOf(1, 0, 1) + apdu, calls[1].args)
        assertEquals(1, (calls[2].args.single() as Request).channel)
        assertTrue(subs.calls.isEmpty())
    }

    @Test fun android13AndLaterReachOtherPorts() {
        val shell = android13()
        shell.open(1, port = 1)
        shell.transmit(1, 1, port = 1)
        assertEquals(1, (phone.calls[0].args.single() as Request).portIndex)
        assertEquals(listOf(1, 1, 1) + apdu, phone.calls[1].args)
        // Earlier versions only have port 0.
        val e = assertThrows(IllegalArgumentException::class.java) { shell(Phone29::class.java).open(1, port = 1) }
        assertEquals("Only port zero is supported", e.message)
    }

    @Test fun theRequestCallsWinOverOneParameterCallsOfTheSameName() {
        val calls = shell(PhoneOem33::class.java, Sub30::class.java, Request::class.java).roundTrip(0)
        assertEquals(listOf("iccOpenLogicalChannel(Request)",
            "iccTransmitApduLogicalChannelByPort(int,int,int,int,int,int,int,int,String)",
            "iccCloseLogicalChannel(Request)"), calls.map { it.method })
    }

    @Test fun android10To12LUseTheSlotCalls() {
        for (api in listOf(Phone29::class.java, Phone31::class.java)) {
            val calls = shell(api).roundTrip(1)
            assertEquals(listOf("iccOpenLogicalChannelBySlot(int,String,String,int)",
                "iccTransmitApduLogicalChannelBySlot(int,int,int,int,int,int,int,String)",
                "iccCloseLogicalChannelBySlot(int,int)"), calls.map { it.method })
            assertEquals(listOf(1, "com.android.shell", isdR, 0), calls[0].args)
            assertEquals(listOf(1, 1) + apdu, calls[1].args)
            assertEquals(listOf(1, 1), calls[2].args)
        }
        assertTrue(subs.calls.isEmpty()) // no subscription needed
    }

    @Test fun android7To9ReachASlotThroughItsActiveSubscription() {
        subs.active[1] = SubInfo(5, iccid)
        val opens = mapOf(
            Phone24::class.java to ("iccOpenLogicalChannel(int,String)" to listOf(5, isdR)),
            Phone26::class.java to ("iccOpenLogicalChannel(int,String,int)" to listOf(5, isdR, 0)),
            Phone27::class.java to ("iccOpenLogicalChannel(int,String,String,int)" to listOf(5, "com.android.shell", isdR, 0)),
            Phone28::class.java to ("iccOpenLogicalChannel(int,String,String,int)" to listOf(5, "com.android.shell", isdR, 0)),
        )
        for ((api, open) in opens) {
            val calls = shell(api).roundTrip(1)
            assertEquals(listOf(open.first, "iccTransmitApduLogicalChannel(int,int,int,int,int,int,int,String)",
                "iccCloseLogicalChannel(int,int)"), calls.map { it.method })
            assertEquals(open.second, calls[0].args)
            assertEquals(listOf(5, 1) + apdu, calls[1].args) // the channel's own subscription each time
            assertEquals(listOf(5, 1), calls[2].args)
        }
        assertEquals(Call("getActiveSubscriptionInfoForSimSlotIndex(int,String)", listOf(1, "com.android.shell")),
            subs.calls[0])
    }

    @Test fun slot0WithoutASubscriptionGoesThroughTheFirstSim() {
        // -1 reaches the first SIM's card even with no eSIM on; -2 is only Android's placeholder for the slot.
        val shell = shell(Phone27::class.java)
        shell.open(0)
        assertEquals(-1, phone.calls.single().args[0])
        // With one, its own.
        subs.active[0] = SubInfo(3, iccid)
        shell.open(0)
        assertEquals(3, phone.calls[1].args[0])
    }

    @Test fun otherSlotsWithoutASubscriptionAreReportedEmpty() {
        val shell = shell(Phone27::class.java)
        val answer = shell.open(1)
        assertEquals(0, answer.getInt("channel"))
        assertEquals(2, answer.getInt("status")) // STATUS_MISSING_RESOURCE: no card there
        assertEquals("", answer.getString("select"))
        assertTrue(phone.calls.isEmpty()) // never -1, which would reach slot 0's card instead
        assertThrows(IllegalArgumentException::class.java) { shell.transmit(1, 0) } // nothing was opened
    }

    @Test fun getSubIdIsTheFallback() {
        val shell = shell(Phone24::class.java)
        subs.subIds[1] = intArrayOf(-3, 7)
        shell.open(1)
        assertEquals(7, phone.calls.single().args[0])
        // Placeholders alone don't count.
        subs.subIds[1] = intArrayOf(-3)
        assertEquals(2, shell.open(1).getInt("status"))
        // Also used when the active subscription can't be read.
        subs.failure = SecurityException("no")
        subs.subIds[1] = intArrayOf(7)
        phone.calls.clear()
        shell.open(1)
        assertEquals(7, phone.calls.single().args[0])
    }

    @Test fun theSubscriptionIsLookedUpOnEveryOpen() {
        // Switching eSIMs gives the card a new subscription.
        val shell = shell(Phone26::class.java)
        subs.active[1] = SubInfo(5, iccid)
        shell.open(1)
        subs.active[1] = SubInfo(6, iccid)
        shell.open(1)
        assertEquals(listOf(5, 6), phone.calls.map { it.args[0] })
        subs.active.clear()
        assertEquals(2, shell.open(1).getInt("status"))
        // A channel keeps the subscription it was opened with.
        shell.transmit(1, 1)
        assertEquals(5, phone.calls.last().args[0])
    }

    @Test fun android6ReachesTheFirstSimOnly() {
        val shell = shell(Phone23::class.java)
        val calls = shell.roundTrip(0)
        assertEquals(listOf("iccOpenLogicalChannel(String)",
            "iccTransmitApduLogicalChannel(int,int,int,int,int,int,String)", "iccCloseLogicalChannel(int)"),
            calls.map { it.method })
        assertEquals(listOf(isdR), calls[0].args)
        assertEquals(listOf(1) + apdu, calls[1].args)
        assertEquals(listOf(1), calls[2].args)
        phone.calls.clear()
        assertEquals(2, shell.open(1).getInt("status"))
        assertTrue(phone.calls.isEmpty())
        assertTrue(subs.calls.isEmpty())
    }

    @Test fun phoneMakersOwnCallsOfTheSameShapeAreSkipped() {
        subs.active[1] = SubInfo(5, iccid)
        val calls = shell(PhoneOem24::class.java).roundTrip(1)
        assertEquals(listOf("iccOpenLogicalChannel(int,String)",
            "iccTransmitApduLogicalChannel(int,int,int,int,int,int,int,String)", "iccCloseLogicalChannel(int,int)"),
            calls.map { it.method })
        // Calls made by name too: an int and a boolean only fit their own types.
        phone.reset()
        val oem = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PhoneOem24::class.java), phone)
        CardShell.call(PhoneOem24::class.java, oem, "setDataEnabled", 5, true)
        assertEquals("setDataEnabled(int,boolean)", phone.calls.single().method)
        assertThrows(NoSuchMethodException::class.java) { CardShell.call(Phone23::class.java, oem, "setDataEnabled", 5, "on") }
    }

    @Test fun anAndroidWithoutKnownChannelCallsSaysSo() {
        val shell = shell(PhoneUnknown::class.java)
        val e = assertThrows(UnsupportedOperationException::class.java) { shell.open(0) }
        assertEquals(1, shell.handle(JSONObject().put("op", "ping")).getInt("version"))
        // The app knows this one by its words, with nothing else tying the two together.
        assertEquals("This version of Android has no way for this app to reach the card.",
            ApduException.fromHelper("UnsupportedOperationException: ${e.message}").message)
    }

    @Test fun answersWithoutAStatusWordAreErrors() {
        val shell = shell(Phone23::class.java)
        shell.open(0)
        phone.reply = "" // a channel Android doesn't know, on some versions
        assertThrows(IllegalStateException::class.java) { shell.transmit(0, 1) }
        phone.reply = null
        assertThrows(IllegalStateException::class.java) { shell.transmit(0, 1) }
        phone.reply = "6881" // Android 16+ for the same
        assertEquals("6881", shell.transmit(0, 1).getString("response"))
    }

    @Test fun everyEsimManagerIdOpensAndNoOther() {
        assertEquals(Lpa.ISD_R_OPEN, CardShell.ISD_R_AIDS[0])
        val shell = shell(Phone29::class.java)
        for (aid in CardShell.ISD_R_AIDS) assertTrue(aid, shell.open(0, aid = aid).getInt("channel") > 0)
        // The whole standard ID is direct access's: the helper has no use for it.
        assertThrows(IllegalArgumentException::class.java) { shell.open(0, aid = Lpa.ISD_R) }
    }

    @Test fun onlyTheCardManagerAndEs10AreAllowed() {
        val shell = shell(Phone29::class.java)
        assertThrows(IllegalArgumentException::class.java) { shell.open(0, aid = "A0000000871002FF") }
        shell.open(0)
        val select = JSONObject().put("op", "transmit").put("slot", 0).put("port", 0).put("channel", 1)
            .put("cla", 0).put("ins", 0xA4).put("p1", 4).put("p2", 0).put("p3", 2).put("data", "3F00")
        assertThrows(IllegalArgumentException::class.java) { shell.handle(select) }
    }

    @Test fun useSimOnAndroid13AndLater() {
        subs.active[1] = SubInfo(5, "${iccid}F")
        subs.sims = 1
        val answer = android13().useSimAnswer()
        assertTrue(answer.getBoolean("applied"))
        assertFalse(answer.has("roamingMayReset")) // Android's own roaming switch was used
        assertTrue(watches.isEmpty())
        assertEquals(Call("getActiveSubscriptionInfoForSimSlotIndex(int,String,String)",
            listOf(0, "com.android.shell", null)), subs.calls[0])
        assertEquals(listOf("setDataRoamingEnabled(int,boolean)", "setDataEnabledForReason(int,int,boolean,String)"),
            phone.calls.map { it.method })
        assertEquals(listOf(5, 0, true, "com.android.shell"), phone.calls[1].args)
        assertEquals(listOf(5), subs.calls.single { it.method.startsWith("setDefaultDataSubId") }.args)
        assertTrue(commands.isEmpty())
    }

    @Test fun useSimOnAndroid10To12L() {
        subs.active[1] = SubInfo(5, iccid)
        assertTrue(shell(Phone31::class.java, Sub30::class.java).useSim())
        assertEquals(listOf("setDataRoamingEnabled(int,boolean)", "setDataEnabledForReason(int,int,boolean)"),
            phone.calls.map { it.method })
        phone.reset()
        assertTrue(shell(Phone29::class.java).useSim())
        assertEquals(listOf("setDataRoamingEnabled(int,boolean)", "setUserDataEnabled(int,boolean)"),
            phone.calls.map { it.method })
        assertTrue(commands.isEmpty())
    }

    @Test fun useSimOnAndroid6To9AlsoTurnsOnTheRoamingSetting() {
        subs.active[1] = SubInfo(5, iccid)
        // Android 9 with two SIMs: the SIM's own setting.
        assertTrue(shell(Phone28::class.java).useSim())
        assertEquals(listOf("setUserDataEnabled(int,boolean)"), phone.calls.map { it.method })
        assertEquals(listOf(1, 5), subs.calls.single { it.method.startsWith("setDataRoaming") }.args)
        assertEquals(listOf(listOf("/system/bin/settings", "put", "global", "data_roaming5", "1"),
            listOf("/system/bin/settings", "get", "global", "data_roaming5")), commands)
        // Android 6-8.1 with one SIM: the phone's.
        subs.sims = 1
        for (api in listOf(Phone23::class.java, Phone24::class.java, Phone26::class.java, Phone27::class.java)) {
            phone.reset()
            settings.clear()
            assertTrue(shell(api).useSim())
            assertEquals(Call("setDataEnabled(int,boolean)", listOf(5, true)), phone.calls.single())
            assertEquals(mapOf("data_roaming" to "1"), settings)
        }
    }

    @Test fun oneSimAndroid81And9MayTurnRoamingBackOff() {
        subs.active[0] = SubInfo(5, iccid)
        subs.sims = 1
        for (api in listOf(Phone27::class.java, Phone28::class.java)) {
            watches.clear()
            val answer = shell(api).useSimAnswer()
            assertTrue(answer.getBoolean("applied"))
            assertTrue(answer.getBoolean("roamingMayReset"))
            assertEquals(1000L to 10, watches.single().let { it.ms to it.times }) // each second for 10 seconds
        }
        // Not without roaming, before 8.1, or with two SIMs, where Android only fills in a missing setting.
        watches.clear()
        assertFalse(shell(Phone28::class.java).useSimAnswer(roaming = false).has("roamingMayReset"))
        assertFalse(shell(Phone26::class.java).useSimAnswer().has("roamingMayReset"))
        subs.sims = 2
        assertFalse(shell(Phone28::class.java).useSimAnswer().has("roamingMayReset"))
        assertTrue(watches.isEmpty())
    }

    @Test fun roamingThePhoneTurnsBackOffRightAfterIsTurnedOnOnceMore() {
        subs.active[0] = SubInfo(5, iccid)
        subs.sims = 1
        shell(Phone28::class.java).useSim(data = false)
        val watch = watches.single().check
        commands.clear()
        subs.calls.clear()
        // The new eSIM's carrier settings haven't loaded yet.
        assertFalse(watch.call())
        assertEquals(listOf(listOf("/system/bin/settings", "get", "global", "data_roaming")), commands)
        assertTrue(subs.calls.isEmpty())
        // They load, and Android puts the carrier's default back.
        settings["data_roaming"] = "0"
        assertTrue(watch.call())
        assertEquals("1", settings["data_roaming"])
        assertEquals(listOf(1, 5), subs.calls.single { it.method.startsWith("setDataRoaming") }.args)
    }

    @Test fun theRoamingWatchLeavesAnotherEsimAlone() {
        subs.active[0] = SubInfo(5, iccid)
        subs.sims = 1
        shell(Phone28::class.java).useSim(data = false)
        // Switched to another eSIM meanwhile, which gets its own carrier's default.
        subs.active[0] = SubInfo(6, "89012345678901234567")
        settings["data_roaming"] = "0"
        subs.calls.clear()
        assertTrue(watches.single().check.call())
        assertEquals("0", settings["data_roaming"])
        assertTrue(subs.calls.none { it.method.startsWith("setDataRoaming") })
    }

    @Test fun aNewerRoamingWatchReplacesTheLastOne() {
        subs.active[0] = SubInfo(5, iccid)
        subs.sims = 1
        shell(Phone28::class.java).useSim(data = false)
        shell(Phone28::class.java).useSim(data = false) // another connection's, say
        settings["data_roaming"] = "0"
        commands.clear()
        assertTrue(watches[0].check.call())
        assertTrue(commands.isEmpty())
        assertTrue(watches[1].check.call())
        assertEquals("1", settings["data_roaming"])
    }

    @Test fun backgroundChecksRunOnADaemonThreadAndStop() {
        val checks = AtomicInteger()
        val daemon = AtomicBoolean()
        val done = CountDownLatch(1)
        CardShell.inBackground(1, 3) {
            daemon.set(Thread.currentThread().isDaemon) // never keeps the helper running
            if (checks.incrementAndGet() == 3) done.countDown()
            false
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        Thread.sleep(50)
        assertEquals(3, checks.get()) // at most that many
        assertTrue(daemon.get())
        // A check that's done, or fails, is the last.
        for (last in listOf<() -> Boolean>({ true }, { throw IllegalStateException() })) {
            val ran = AtomicInteger()
            val first = CountDownLatch(1)
            CardShell.inBackground(1, 3) { ran.incrementAndGet(); first.countDown(); last() }
            assertTrue(first.await(5, TimeUnit.SECONDS))
            Thread.sleep(50)
            assertEquals(1, ran.get())
        }
    }

    @Test fun aRoamingSettingThePhoneDidNotKeepIsAnError() {
        subs.active[0] = SubInfo(5, iccid)
        settingsKept = false
        assertThrows(IllegalStateException::class.java) { shell(Phone27::class.java).useSim(data = false) }
    }

    @Test fun useSimWaitsForAndroidToPickTheEsimUp() {
        subs.active[0] = SubInfo(3, "89012345678901234567")
        assertFalse(android13().useSim())
        assertEquals(4, subs.calls.size) // every slot asked
        assertTrue(phone.calls.isEmpty())
        // Android 14+ throws past the last slot: that ends the search, and isn't an error.
        subs.calls.clear()
        subs.lastSlot = 1
        assertFalse(android13().useSim())
        assertEquals(3, subs.calls.size)
        subs.active[1] = SubInfo(5, iccid)
        assertTrue(android13().useSim())
    }

    @Test fun useSimReportsWhatThePhoneRefuses() {
        // Not taken for "not picked up yet", which the app would wait out.
        subs.failure = SecurityException("READ_PHONE_STATE")
        assertEquals("READ_PHONE_STATE", assertThrows(SecurityException::class.java) { android13().useSim() }.message)
    }

    /** Opens a channel in each of [slots], then ends the session: the arguments of each close the phone got. */
    private fun CardShell.closedChannels(vararg slots: Int): List<Call> {
        phone.reset()
        slots.forEach { open(it) }
        phone.calls.clear()
        close()
        return phone.calls.toList()
    }

    private fun requestOf(call: Call) = (call.args.single() as Request).let { listOf(it.slotIndex, it.portIndex, it.channel) }

    @Test fun endingASessionClosesItsChannelsOnEveryRoute() {
        // On Android 12 and older nothing else does: the card only has a few, and they outlive the helper.
        val request = android13().closedChannels(0, 1)
        assertEquals(setOf("iccCloseLogicalChannel(Request)"), request.map { it.method }.toSet())
        assertEquals(setOf(listOf(0, 0, 1), listOf(1, 0, 2)), request.map(::requestOf).toSet())

        val slot = shell(Phone29::class.java).closedChannels(0, 1)
        assertEquals(setOf("iccCloseLogicalChannelBySlot(int,int)"), slot.map { it.method }.toSet())
        assertEquals(setOf(listOf<Any?>(0, 1), listOf<Any?>(1, 2)), slot.map { it.args }.toSet())

        subs.active[0] = SubInfo(3, iccid)
        subs.active[1] = SubInfo(5, iccid)
        val sub = shell(Phone27::class.java).closedChannels(0, 1)
        assertEquals(setOf("iccCloseLogicalChannel(int,int)"), sub.map { it.method }.toSet())
        assertEquals(setOf(listOf<Any?>(3, 1), listOf<Any?>(5, 2)), sub.map { it.args }.toSet())

        val first = shell(Phone23::class.java).closedChannels(0, 0)
        assertEquals(setOf("iccCloseLogicalChannel(int)"), first.map { it.method }.toSet())
        assertEquals(setOf(listOf<Any?>(1), listOf<Any?>(2)), first.map { it.args }.toSet())
    }

    @Test fun endingASessionTriesEveryChannelAndForgetsThem() {
        val shell = shell(Phone29::class.java)
        shell.open(0)
        shell.open(1)
        phone.calls.clear()
        phone.failure = IllegalStateException("gone")
        shell.close()
        assertEquals(2, phone.calls.size)
        phone.failure = null
        phone.calls.clear()
        shell.close()
        assertTrue(phone.calls.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { shell.transmit(0, 1) }
    }

    @Test fun aPhoneProcessThatDiedIsFetchedAgainForTheNextRequest() {
        val shell = android13()
        shell.open(1)
        assertEquals(1, phoneFetches)
        phone.failure = DeadObjectException()
        val error = HelperServer.error(assertThrows(Exception::class.java) { shell.transmit(1, 1) }).getString("error")
        assertTrue(error, error.startsWith("DeadObjectException")) // what the app recognises
        phone.failure = null
        // Sent once, and never again by itself: it may have reached the card.
        assertEquals(1, phone.calls.count { it.method.startsWith("iccTransmit") })
        // The channels went with the old process.
        assertThrows(IllegalArgumentException::class.java) { shell.transmit(1, 1) }
        assertEquals(1, phoneFetches)
        assertEquals(2, shell.open(1).getInt("channel"))
        assertEquals("9000", shell.transmit(1, 2).getString("response"))
        assertEquals(2, phoneFetches)
    }

    @Test fun theSimListComesFromTheNewProcessToo() {
        subs.active[1] = SubInfo(5, iccid)
        val shell = shell(Phone27::class.java)
        shell.open(1)
        assertEquals(1, subFetches)
        phone.failure = DeadObjectException()
        assertThrows(Exception::class.java) { shell.open(1) }
        phone.failure = null
        shell.open(1)
        assertEquals(2, subFetches)
    }

    @Test fun aPhoneProcessThatIsNotBackYetFailsUntilItIs() {
        val shell = android13()
        shell.open(1)
        phone.failure = DeadObjectException()
        assertThrows(Exception::class.java) { shell.open(1) }
        phone.failure = null
        telephonyDown = true
        repeat(2) { assertThrows(IllegalStateException::class.java) { shell.open(1) } }
        telephonyDown = false
        assertTrue(shell.open(1).getInt("channel") > 0)
    }

    @Test fun aSessionOpensWhileThePhoneProcessIsAway() {
        // A session that can't open costs the app its connection, and with it the helper's key.
        telephonyDown = true
        val shell = android13()
        assertEquals(1, shell.handle(JSONObject().put("op", "ping")).getInt("version"))
        assertThrows(IllegalStateException::class.java) { shell.open(1) }
    }

    @Test fun otherFailuresKeepThePhoneAndTheChannels() {
        val shell = android13()
        shell.open(1)
        phone.failure = SecurityException("no")
        assertThrows(Exception::class.java) { shell.transmit(1, 1) }
        phone.failure = null
        assertEquals("9000", shell.transmit(1, 1).getString("response"))
        assertEquals(1, phoneFetches)
    }

    @Test fun aSubscriptionLookupStillNoticesThePhoneProcessDied() {
        // Android 7-9: not taken for a slot without a subscription, which the app would report as no card.
        val shell = shell(Phone27::class.java)
        shell.open(0)
        subs.failure = DeadObjectException()
        assertThrows(Exception::class.java) { shell.open(1) }
        subs.failure = null
        assertEquals(2, shell.open(1).getInt("status")) // the new process: this slot really has none
        assertEquals(2, subFetches)
    }

    @Test fun theSlotListStillNoticesThePhoneProcessDied() {
        // The app asks for it first, so the channel it opens next already reaches the new process.
        phone.slots = arrayOf(Slot10(true, true))
        val shell = shell(Phone29::class.java)
        phone.failure = DeadObjectException()
        assertThrows(Exception::class.java) { shell.slots() }
        phone.failure = null
        assertEquals(1, shell.slots().size)
        assertEquals(2, phoneFetches)
        // Anything else still only means Android doesn't say.
        phone.failure = SecurityException("no")
        assertEquals(emptyList<Any>(), shell.slots())
        assertEquals(2, phoneFetches)
    }

    @Test fun slotsAreListedWhereAndroidSaysAndEmptyElsewhere() {
        // Android 6-8: no such list.
        assertEquals(emptyList<Any>(), shell(Phone27::class.java).slots())
        // Android 9 as ADB shell: not allowed.
        assertEquals(emptyList<Any>(), shell(Phone28::class.java).slots())
        // Android 9 as root: no word on removable, which the app then takes as removable.
        phone.slots = arrayOf(Slot9(false), Slot9(true))
        assertEquals(listOf(mapOf("euicc" to false), mapOf("euicc" to true)), shell(Phone28::class.java).slots())
        // Android 10+.
        phone.slots = arrayOf(Slot10(true, true), null, Slot10(true, false))
        val listed = listOf(mapOf("euicc" to true, "removable" to true), emptyMap(),
            mapOf("euicc" to true, "removable" to false))
        assertEquals(listed, shell(Phone29::class.java).slots())
        phone.reset()
        assertEquals(listed, android13().slots())
        assertEquals(listOf("com.android.shell"), phone.calls.single().args)
    }

    // Stand-ins for the hidden telephony interfaces, with the methods each Android version has (ITelephony.aidl
    // and ISub.aidl at the AOSP release tags). Public, as the helper calls them by reflection.

    /** IccOpenLogicalChannelResponse. */
    class Response(val channel: Int, val status: Int, val selectResponse: ByteArray?)

    /** SubscriptionInfo. */
    class SubInfo(val subscriptionId: Int, val iccId: String)

    /** UiccSlotInfo on Android 9: no isRemovable() yet. */
    class Slot9(private val euicc: Boolean) { fun getIsEuicc() = euicc }

    /** UiccSlotInfo on Android 10+. */
    class Slot10(private val euicc: Boolean, private val removable: Boolean) {
        fun getIsEuicc() = euicc
        fun isRemovable() = removable
    }

    /** IccLogicalChannelRequest, Android 13+. */
    class Request {
        @JvmField var subId = -1
        @JvmField var slotIndex = -1
        @JvmField var portIndex = 0
        @JvmField var callingPackage: String? = null
        @JvmField var aid: String? = null
        @JvmField var p2 = 0
        @JvmField var channel = -1
        @JvmField var binder: IBinder? = null
    }

    /** Android 6 (API 23): the first SIM's card only. */
    interface Phone23 {
        fun iccOpenLogicalChannel(aid: String?): Response?
        fun iccTransmitApduLogicalChannel(channel: Int, cla: Int, ins: Int, p1: Int, p2: Int, p3: Int, data: String?): String?
        fun iccCloseLogicalChannel(channel: Int): Boolean
        fun setDataEnabled(subId: Int, enable: Boolean)
    }

    /** Android 7-7.1 (API 24-25): by subscription. */
    interface Phone24 {
        fun iccOpenLogicalChannel(subId: Int, aid: String?): Response?
        fun iccTransmitApduLogicalChannel(subId: Int, channel: Int, cla: Int, ins: Int, p1: Int, p2: Int, p3: Int, data: String?): String?
        fun iccCloseLogicalChannel(subId: Int, channel: Int): Boolean
        fun setDataEnabled(subId: Int, enable: Boolean)
    }

    /** Android 8.0 (API 26): the open takes P2. */
    interface Phone26 {
        fun iccOpenLogicalChannel(subId: Int, aid: String?, p2: Int): Response?
        fun iccTransmitApduLogicalChannel(subId: Int, channel: Int, cla: Int, ins: Int, p1: Int, p2: Int, p3: Int, data: String?): String?
        fun iccCloseLogicalChannel(subId: Int, channel: Int): Boolean
        fun setDataEnabled(subId: Int, enable: Boolean)
    }

    /** Android 8.1 (API 27): and the calling package. */
    interface Phone27 {
        fun iccOpenLogicalChannel(subId: Int, callingPackage: String?, aid: String?, p2: Int): Response?
        fun iccTransmitApduLogicalChannel(subId: Int, channel: Int, cla: Int, ins: Int, p1: Int, p2: Int, p3: Int, data: String?): String?
        fun iccCloseLogicalChannel(subId: Int, channel: Int): Boolean
        fun setDataEnabled(subId: Int, enable: Boolean)
    }

    /** Android 9 (API 28): channels as on 8.1, a slot list, and another mobile data call. */
    interface Phone28 {
        fun iccOpenLogicalChannel(subId: Int, callingPackage: String?, aid: String?, p2: Int): Response?
        fun iccTransmitApduLogicalChannel(subId: Int, channel: Int, cla: Int, ins: Int, p1: Int, p2: Int, p3: Int, data: String?): String?
        fun iccCloseLogicalChannel(subId: Int, channel: Int): Boolean
        fun setUserDataEnabled(subId: Int, enable: Boolean)
        fun getUiccSlotsInfo(): Array<Any?>?
    }

    /** Android 10-11 (API 29-30): by slot, with the subscription calls still there. */
    interface Phone29 {
        fun iccOpenLogicalChannelBySlot(slotIndex: Int, callingPackage: String?, aid: String?, p2: Int): Response?
        fun iccTransmitApduLogicalChannelBySlot(slotIndex: Int, channel: Int, cla: Int, ins: Int, p1: Int, p2: Int, p3: Int, data: String?): String?
        fun iccCloseLogicalChannelBySlot(slotIndex: Int, channel: Int): Boolean
        fun iccOpenLogicalChannel(subId: Int, callingPackage: String?, aid: String?, p2: Int): Response?
        fun iccTransmitApduLogicalChannel(subId: Int, channel: Int, cla: Int, ins: Int, p1: Int, p2: Int, p3: Int, data: String?): String?
        fun iccCloseLogicalChannel(subId: Int, channel: Int): Boolean
        fun setUserDataEnabled(subId: Int, enable: Boolean)
        fun setDataRoamingEnabled(subId: Int, isEnabled: Boolean)
        fun getUiccSlotsInfo(): Array<Any?>?
    }

    /** Android 12-12L (API 31-32). */
    interface Phone31 {
        fun iccOpenLogicalChannelBySlot(slotIndex: Int, callingPackage: String?, aid: String?, p2: Int): Response?
        fun iccTransmitApduLogicalChannelBySlot(slotIndex: Int, channel: Int, cla: Int, ins: Int, p1: Int, p2: Int, p3: Int, data: String?): String?
        fun iccCloseLogicalChannelBySlot(slotIndex: Int, channel: Int): Boolean
        fun setDataEnabledForReason(subId: Int, reason: Int, enable: Boolean)
        fun setDataRoamingEnabled(subId: Int, isEnabled: Boolean)
        fun getUiccSlotsInfo(): Array<Any?>?
    }

    /** Android 13-17 (API 33-37): a request object, and ports. */
    interface Phone33 {
        fun iccOpenLogicalChannel(request: Request?): Response?
        fun iccCloseLogicalChannel(request: Request?): Boolean
        fun iccTransmitApduLogicalChannelByPort(slotIndex: Int, portIndex: Int, channel: Int, cla: Int, ins: Int, p1: Int, p2: Int, p3: Int, data: String?): String?
        fun iccTransmitApduLogicalChannel(subId: Int, channel: Int, cla: Int, ins: Int, p1: Int, p2: Int, p3: Int, data: String?): String?
        fun setDataEnabledForReason(subId: Int, reason: Int, enable: Boolean, callingPackage: String?)
        fun setDataRoamingEnabled(subId: Int, isEnabled: Boolean)
        fun getUiccSlotsInfo(callingPackage: String?): Array<Any?>?
    }

    /** Android 13+ that also kept Android 6's one-parameter calls: as many parameters as the request ones. */
    interface PhoneOem33 : Phone33 {
        fun iccOpenLogicalChannel(aid: String?): Response?
        fun iccCloseLogicalChannel(channel: Int): Boolean
    }

    /** Android 7 with a phone maker's own calls of the same names and parameter counts, declared first. */
    interface PhoneOem24 {
        fun iccOpenLogicalChannel(aid: String?, p2: Int): Response?
        fun iccCloseLogicalChannel(slot: Int, port: Long): Boolean
        fun setDataEnabled(phoneId: Int, reason: Int)
        fun iccOpenLogicalChannel(subId: Int, aid: String?): Response?
        fun iccTransmitApduLogicalChannel(subId: Int, channel: Int, cla: Int, ins: Int, p1: Int, p2: Int, p3: Int, data: String?): String?
        fun iccCloseLogicalChannel(subId: Int, channel: Int): Boolean
        fun setDataEnabled(subId: Int, enable: Boolean)
    }

    /** None of the channel calls the helper knows. */
    interface PhoneUnknown { fun iccOpenLogicalChannel(slot: Long): Response? }

    /** ISub on Android 6-10 (API 23-29). */
    interface Sub23 {
        fun getActiveSubscriptionInfoForSimSlotIndex(slotIndex: Int, callingPackage: String?): SubInfo?
        fun getSubId(slotIndex: Int): IntArray?
        fun getActiveSubInfoCountMax(): Int
        fun setDataRoaming(roaming: Int, subId: Int): Int
        fun setDefaultDataSubId(subId: Int)
    }

    /** ISub on Android 11-17 (API 30-37). */
    interface Sub30 {
        fun getActiveSubscriptionInfoForSimSlotIndex(slotIndex: Int, callingPackage: String?, callingFeatureId: String?): SubInfo?
        fun getSubId(slotIndex: Int): IntArray?
        fun getActiveSubInfoCountMax(): Int
        fun setDataRoaming(roaming: Int, subId: Int): Int
        fun setDefaultDataSubId(subId: Int)
    }
}
