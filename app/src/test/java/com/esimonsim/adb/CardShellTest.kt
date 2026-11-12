package com.esimonsim.adb

import android.os.DeadObjectException
import android.os.IBinder
import com.esimonsim.apdu.ApduException
import com.esimonsim.lpa.Hex
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
    private val eid = "89049032000000000000000000012345"

    /** One call into a stand-in: the picked overload's name and parameter types, and the arguments. */
    data class Call(val method: String, val args: List<Any?>)

    /** Answers like the phone process: channels numbered from 1, [reply] to each APDU. */
    private inner class FakePhone : InvocationHandler {
        val calls = ArrayList<Call>()
        var reply: String? = "9000"
        var slots: Array<Any?>? = null
        var cards: List<Any?>? = null
        /** What every call throws while set. */
        var failure: Exception? = null
        /** Slots whose Android 13+ open throws, as Android does for a card manager ID it keeps for its own eSIM app. */
        val refused = HashSet<Int>()
        private var next = 1
        fun reset() { calls.clear(); next = 1 }
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            calls += Call(signature(method), args?.toList().orEmpty())
            failure?.let { throw it }
            (args?.singleOrNull() as? Request)?.let { r ->
                if (method.name == "iccOpenLogicalChannel" && r.slotIndex in refused)
                    throw SecurityException("The calling package is not allowed to access ISD-R.")
            }
            return when {
                method.name.startsWith("iccOpenLogicalChannel") -> Response(next++, 1, byteArrayOf(0x90.toByte(), 0))
                method.name.startsWith("iccTransmitApdu") -> reply
                method.name.startsWith("iccCloseLogicalChannel") -> true
                method.name == "getUiccSlotsInfo" -> slots ?: throw SecurityException("READ_PRIVILEGED_PHONE_STATE")
                method.name == "getUiccCardsInfo" -> cards
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
                "getAllSubInfoList" -> records
                else -> null
            }
        }
        /** Android's records of every SIM profile it has seen, as getAllSubInfoList gives them. */
        val records = ArrayList<Any>()
    }

    /** One of Android's SubscriptionInfo records, with the getters the helper reads. [source]: Android 13+'s getter. */
    class SubRecord(private val card: String?, private val iccid: String, private val name: CharSequence,
                    private val id: Int, private val source: Int, private val slot: Int = -1) {
        fun getCardString() = card
        fun getIccId() = iccid
        fun getDisplayName() = name
        fun getSubscriptionId() = id
        fun getDisplayNameSource() = source
        fun getSimSlotIndex() = slot
    }

    /** As [SubRecord] before Android 13, whose name source getter was the hidden getNameSource. */
    class OldSubRecord(private val card: String?, private val iccid: String, private val name: CharSequence,
                       private val id: Int, private val source: Int) {
        fun getCardString() = card
        fun getIccId() = iccid
        fun getDisplayName() = name
        fun getSubscriptionId() = id
        fun getNameSource() = source
        fun getSimSlotIndex() = -1
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

    /** The helper's clock, in milliseconds: moved by hand. */
    private var now = 0L
    /** How often the helper asked for the Secure Element HAL's interface, and for a slot's reader. */
    private var seApiLookups = 0
    private var readerLookups = 0

    private fun shell(api: Class<*>, subApi: Class<*> = Sub23::class.java, request: Class<*>? = null,
                      seApi: Class<*>? = null, se: CardShell.SecureElement = CardShell.SecureElement { _ -> null }) = CardShell(
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
        { ms, times, check -> watches += Watch(ms, times, check) },
        Callable<Class<*>?> { seApiLookups++; seApi }, se, CardShell.Clock { now },
    )

    /** A stand-in for the Secure Element HAL: one card, answering channels from [channelNumber]. */
    private inner class FakeSe : InvocationHandler {
        val calls = ArrayList<Call>()
        var present = true
        var channelNumber: Byte = 1
        var selectResponse: ByteArray? = byteArrayOf(0x90.toByte(), 0)
        var transmitReply: ByteArray? = byteArrayOf(0x90.toByte(), 0)
        var openFailure: Exception? = null
        /** What every call throws while set: a DeadObjectException is also how SELinux refusing the call comes back. */
        var failure: Exception? = null
        /** Whether the HAL's binder is alive: false once its process went away, true when SELinux refused a call. */
        var alive = true
        private val binder = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(IBinder::class.java)) { _, m, _ ->
            if (m.name == "isBinderAlive") alive else null
        }
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (method.name == "asBinder") return binder // not a call into the HAL
            calls += Call(signature(method), args?.toList().orEmpty())
            failure?.let { throw it }
            return when (method.name) {
                "isCardPresent" -> present
                "openLogicalChannel" -> { openFailure?.let { throw it }; SeResponse(channelNumber, selectResponse) }
                "transmit" -> transmitReply
                else -> null // closeChannel
            }
        }
    }

    private val secure = FakeSe()

    /** A reader that hands [secure] out for the given slots and nothing for others. */
    private fun seReaderFor(vararg slots: Int) = CardShell.SecureElement { slot ->
        readerLookups++
        if (slot in slots) Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SecureElementApi::class.java), secure)
        else null
    }

    private fun android13() = shell(Phone33::class.java, Sub30::class.java, Request::class.java)

    private fun CardShell.open(slot: Int, port: Int = 0, aid: String = isdR) =
        handle(JSONObject().put("op", "open").put("slot", slot).put("port", port).put("aid", aid))

    /** As the app asks the Secure Element HAL: its [reader], the channel filed under [slot]. */
    private fun CardShell.openHal(slot: Int, reader: Int = slot, port: Int = 0, aid: String = isdR) =
        handle(JSONObject().put("op", "open").put("slot", slot).put("port", port).put("aid", aid)
            .put("via", "hal").put("reader", reader))

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
        // The last is the whole standard ID in lower case: a fallback for a card that won't take the prefix.
        assertEquals(Lpa.ISD_R_LOWER, CardShell.ISD_R_AIDS.last())
        val shell = shell(Phone29::class.java)
        for (aid in CardShell.ISD_R_AIDS) assertTrue(aid, shell.open(0, aid = aid).getInt("channel") > 0)
        // The whole standard ID in upper case is direct access's and is reserved for the system LPA: the helper won't
        // send it, so the Android 13+ check never sees this app ask for it. The lower-case form above is sent instead.
        assertThrows(IllegalArgumentException::class.java) { shell.open(0, aid = Lpa.ISD_R) }
    }

    @Test fun theClassByteCarriesTheLogicalChannel() {
        // ISO 7816-4, as Android's own secure-element service encodes it. Channels 0-3 in the low two bits.
        assertEquals(0x80.toByte(), CardShell.classByte(0x80, 0)) // ES10 STORE DATA, channel 0
        assertEquals(0x81.toByte(), CardShell.classByte(0x80, 1))
        assertEquals(0x83.toByte(), CardShell.classByte(0x80, 3))
        assertEquals(0x01.toByte(), CardShell.classByte(0x00, 1)) // GET RESPONSE on channel 1
        // Channels 4-19: bit 7 set, (channel - 4) in the low nibble.
        assertEquals(0x40.toByte(), CardShell.classByte(0x00, 4))
        assertEquals(0x4F.toByte(), CardShell.classByte(0x00, 19))
        assertThrows(IllegalArgumentException::class.java) { CardShell.classByte(0x00, 20) }
    }

    @Test fun theSecureElementHalReachesACardTelephonyCannot() {
        // A phone with no telephony channel calls at all: the Secure Element HAL is the only way in, when the app asks.
        val shell = shell(PhoneUnknown::class.java, seApi = SecureElementApi::class.java, se = seReaderFor(1))
        val opened = shell.openHal(1)
        assertEquals(1, opened.getInt("channel"))
        assertEquals(0, opened.getInt("status"))
        assertEquals("9000", opened.getString("select"))
        assertEquals(listOf("isCardPresent()", "openLogicalChannel(byte[],byte)"), secure.calls.map { it.method })
        // The AID went in as bytes; the prefix is selected with P2 = first occurrence.
        assertEquals(Lpa.ISD_R_OPEN, Hex.encode(secure.calls[1].args[0] as ByteArray))
        assertEquals(0.toByte(), secure.calls[1].args[1])
        // The card's bytes come back as hex (byte-level APDU shape is checked in the next test).
        assertEquals("9000", shell.transmit(1, 1).getString("response"))
    }

    @Test fun aSecureElementChannelTransmitsThroughTheClassByteAndCloses() {
        val shell = shell(PhoneUnknown::class.java, seApi = SecureElementApi::class.java, se = seReaderFor(1))
        shell.openHal(1)
        shell.transmit(1, 1) // GetEID: cla 0x80, ins 0xE2, p1 0x91, p2 0, p3 6, data BF3E035C015A
        val sent = secure.calls.single { it.method.startsWith("transmit") }.args.single() as ByteArray
        assertEquals("81E2910006BF3E035C015A", Hex.encode(sent)) // class byte 0x80 | channel 1 = 0x81, then the APDU
        secure.calls.clear()
        shell.close(1, 1)
        assertEquals(listOf("closeChannel(byte)"), secure.calls.map { it.method })
        assertEquals(1.toByte(), secure.calls.single().args.single())
    }

    @Test fun anOrdinaryOpenNeverAsksTheHal() {
        // Issue #1: asked on each telephony miss, a HAL reader got in before the card's own ID had been tried, and on a
        // Pixel it reached the phone's built-in chip. Telephony's answer now stands, whatever it is.
        val shell = shell(Phone29::class.java, seApi = SecureElementApi::class.java, se = seReaderFor(0, 1))
        assertEquals(1, shell.open(1).getInt("channel"))
        val missed = shell(Phone27::class.java, seApi = SecureElementApi::class.java, se = seReaderFor(1)).open(1)
        assertEquals(0, missed.getInt("channel")) // Android 8.1-9: no subscription for that slot
        assertEquals(2, missed.getInt("status"))
        assertThrows(UnsupportedOperationException::class.java) {
            shell(PhoneUnknown::class.java, seApi = SecureElementApi::class.java, se = seReaderFor(1)).open(1)
        }
        assertTrue(secure.calls.isEmpty())
        assertEquals(0, seApiLookups)
        assertEquals(0, readerLookups)
    }

    @Test fun aRefusalIsTelephonysAnswerAndTheHalIsAskedOnlyWhenTheAppDoes() {
        // Android refusing the helper outright, as for an ID it keeps for its own eSIM app: the app gets that refusal,
        // and asks the HAL itself once no ID worked anywhere.
        phone.refused += 1
        val shell = android13WithHal(1)
        val error = HelperServer.error(assertThrows(Exception::class.java) { shell.open(1) })
        assertEquals("SecurityException: The calling package is not allowed to access ISD-R.", error.getString("error"))
        assertEquals(0, readerLookups)
        assertEquals(1, shell.openHal(1).getInt("channel"))
        phone.calls.clear()
        assertEquals("9000", shell.transmit(1, 1).getString("response"))
        assertTrue(phone.calls.isEmpty()) // the channel is the HAL's: telephony never hears of it
    }

    @Test fun theAppNamesTheReaderAndTheChannelIsFiledUnderItsSlot() {
        // A Pixel numbers its readers by SIM, not by tray: the app asks for reader 0 (SIM1) for slot 1's card.
        val shell = shell(PhoneUnknown::class.java, seApi = SecureElementApi::class.java, se = seReaderFor(0))
        assertEquals(2, shell.openHal(1).getInt("status")) // slot 1's own reader number: none
        assertEquals(1, shell.openHal(1, reader = 0).getInt("channel"))
        assertEquals("9000", shell.transmit(1, 1).getString("response"))
        assertThrows(IllegalArgumentException::class.java) { shell.transmit(0, 1) } // not slot 0's channel
        secure.calls.clear()
        shell.close(1, 1)
        assertEquals(listOf("closeChannel(byte)"), secure.calls.map { it.method })
    }

    @Test fun aHalChannelCantTakeTheNumberOfATelephonyChannel() {
        val shell = android13WithHal(1)
        assertEquals(1, shell.open(1).getInt("channel")) // telephony's channel 1 on slot 1
        assertThrows(IllegalStateException::class.java) { shell.openHal(1) } // the HAL's channel 1 too
        assertEquals("closeChannel(byte)", secure.calls.last().method) // given back
        phone.calls.clear()
        assertEquals("9000", shell.transmit(1, 1).getString("response"))
        assertTrue(phone.calls.isNotEmpty()) // still telephony's
    }

    @Test fun anUnknownRouteIsRefused() {
        val shell = android13WithHal(1)
        assertThrows(IllegalArgumentException::class.java) {
            shell.handle(JSONObject().put("op", "open").put("slot", 1).put("port", 0).put("aid", isdR).put("via", "omapi"))
        }
    }

    @Test fun aSecureElementHalWithNoCardReportsAnEmptySlot() {
        secure.present = false
        val shell = shell(PhoneUnknown::class.java, seApi = SecureElementApi::class.java, se = seReaderFor(1))
        val opened = shell.openHal(1)
        assertEquals(0, opened.getInt("channel"))
        assertEquals(2, opened.getInt("status"))
        assertTrue(secure.calls.none { it.method.startsWith("openLogicalChannel") }) // isCardPresent said no first
    }

    @Test fun aHalWithoutTheAppletSaysSoSoTheAppTriesItsNextId() {
        secure.openFailure = IllegalStateException("NO_SUCH_ELEMENT")
        val shell = shell(PhoneUnknown::class.java, seApi = SecureElementApi::class.java, se = seReaderFor(1))
        assertEquals(3, shell.openHal(1).getInt("status"))
        secure.openFailure = null
        assertEquals(1, shell.openHal(1, aid = CardShell.ISD_R_AIDS[2]).getInt("channel")) // the reader is still asked
    }

    @Test fun aReaderThatIsntThereIsAskedForAgainOnlyAfterAMinute() {
        val shell = shell(PhoneUnknown::class.java, seApi = SecureElementApi::class.java, se = seReaderFor(1))
        // Reader 0 isn't there: no card, and the reader isn't looked for at each open.
        assertEquals(2, shell.openHal(0).getInt("status"))
        assertEquals(2, shell.openHal(0).getInt("status"))
        assertEquals(1, readerLookups)
        now += CardShell.HAL_RETRY_MS - 1
        shell.openHal(0)
        assertEquals(1, readerLookups)
        now += 1
        shell.openHal(0)
        assertEquals(2, readerLookups)
        assertTrue(secure.calls.isEmpty())
    }

    @Test fun endingASessionClosesSecureElementChannels() {
        val shell = shell(PhoneUnknown::class.java, seApi = SecureElementApi::class.java, se = seReaderFor(1))
        shell.openHal(1)
        secure.calls.clear()
        shell.close()
        assertEquals(listOf("closeChannel(byte)"), secure.calls.map { it.method })
        assertEquals(1.toByte(), secure.calls.single().args.single())
    }

    private fun android13WithHal(vararg readers: Int) = shell(Phone33::class.java, Sub30::class.java, Request::class.java,
        seApi = SecureElementApi::class.java, se = seReaderFor(*readers))

    @Test fun thePhoneProcessDyingIsNotTakenToTheHal() {
        val shell = android13WithHal(1)
        phone.failure = DeadObjectException()
        assertThrows(Exception::class.java) { shell.open(1) }
        assertEquals(0, seApiLookups) // handle() starts telephony over instead
        phone.failure = null
        assertEquals(1, shell.open(1).getInt("channel"))
        assertEquals(2, phoneFetches)
    }

    @Test fun aHalThatRefusesTheHelperIsLeftAloneForAMinute() {
        secure.failure = DeadObjectException() // how SELinux keeping ADB shell from calling the HAL comes back
        val shell = shell(PhoneUnknown::class.java, seApi = SecureElementApi::class.java, se = seReaderFor(1))
        assertEquals(3, shell.openHal(1).getInt("status"))
        assertEquals(listOf("isCardPresent()"), secure.calls.map { it.method })
        // Discovery tries each card manager ID in turn: the HAL isn't asked again each time, and the reader counts as
        // having no card, which ends the app's look there.
        assertEquals(2, shell.openHal(1).getInt("status"))
        assertEquals(1, secure.calls.size)
        assertEquals(1, readerLookups)
        // A minute later it's looked up afresh, as it may have come up meanwhile.
        secure.failure = null
        now += CardShell.HAL_RETRY_MS
        assertEquals(1, shell.openHal(1).getInt("channel"))
        assertEquals(2, readerLookups)
    }

    @Test fun aMissingHalInterfaceIsLookedForAgainAfterAMinute() {
        // Android 14+ without its secure-element app, or before it's up.
        val shell = shell(PhoneUnknown::class.java, seApi = null, se = seReaderFor(1))
        repeat(3) { assertEquals(2, shell.openHal(1).getInt("status")) }
        assertEquals(1, seApiLookups)
        now += CardShell.HAL_RETRY_MS
        assertEquals(2, shell.openHal(1).getInt("status"))
        assertEquals(2, seApiLookups)
        assertEquals(0, readerLookups)
    }

    @Test fun aHalThatWentAwayTakesItsChannelAndLeavesTelephonyAlone() {
        val shell = android13WithHal(1)
        assertEquals(1, shell.open(0).getInt("channel")) // telephony's
        assertEquals(1, shell.openHal(1).getInt("channel")) // the HAL's
        secure.failure = DeadObjectException()
        secure.alive = false // its process restarted
        val error = HelperServer.error(assertThrows(Exception::class.java) { shell.transmit(1, 1) }).getString("error")
        // Not "DeadObjectException": the app would take that for the phone process, and telephony's channels with it.
        assertFalse(error, error.startsWith("DeadObjectException"))
        assertEquals(1, phoneFetches)
        assertEquals("9000", shell.transmit(0, 1).getString("response"))
        // The HAL may give that number to someone else now: never closed from here.
        secure.failure = null
        secure.alive = true
        secure.calls.clear()
        assertThrows(IllegalArgumentException::class.java) { shell.close(1, 1) }
        shell.close()
        assertTrue(secure.calls.isEmpty())
        // And the next open looks the HAL up afresh, at once, with no minute's wait: the new one is usually up in seconds.
        assertEquals(1, readerLookups)
        assertEquals(1, shell.openHal(1).getInt("channel"))
        assertEquals(2, readerLookups)
    }

    @Test fun aReaderWhoseProcessRestartedIsLookedUpAgainAtOnce() {
        // The reader kept from earlier belongs to a HAL process that has since restarted: the open looks it up afresh
        // and asks the new one, rather than failing this once.
        val dead = FakeSe().apply { failure = DeadObjectException(); alive = false }
        val readers = ArrayDeque(listOf(dead, secure))
        val shell = shell(PhoneUnknown::class.java, seApi = SecureElementApi::class.java, se = CardShell.SecureElement { _ ->
            readerLookups++
            readers.removeFirstOrNull()?.let { Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SecureElementApi::class.java), it) }
        })
        assertEquals(1, shell.openHal(1).getInt("channel"))
        assertEquals(2, readerLookups)
        assertEquals("9000", shell.transmit(1, 1).getString("response"))
    }

    @Test fun anyOtherFailedHalTransmitKeepsTheChannelForTheAppToClose() {
        // As Android's own secure-element app does: an I/O error doesn't close a channel, so leaving it would leak it.
        val shell = shell(PhoneUnknown::class.java, seApi = SecureElementApi::class.java, se = seReaderFor(1))
        shell.openHal(1)
        secure.failure = IllegalStateException("IOERROR")
        assertThrows(IllegalStateException::class.java) { shell.transmit(1, 1) }
        secure.failure = null
        secure.calls.clear()
        shell.close(1, 1)
        assertEquals(listOf("closeChannel(byte)"), secure.calls.map { it.method })
    }

    @Test fun aChannelTheCardSaysIsntOpenIsForgotten() {
        // 6881, as after the card restarted: the number may be someone else's by the time the app closes it.
        val shell = shell(PhoneUnknown::class.java, seApi = SecureElementApi::class.java, se = seReaderFor(1))
        shell.openHal(1)
        secure.transmitReply = byteArrayOf(0x68, 0x81.toByte())
        assertEquals("6881", shell.transmit(1, 1).getString("response")) // the app still gets the card's answer
        secure.calls.clear()
        assertThrows(IllegalArgumentException::class.java) { shell.close(1, 1) }
        assertTrue(secure.calls.isEmpty())
    }

    @Test fun aHalChannelPastNineteenIsGivenBack() {
        // The HAL promises 1-19, the channels a class byte can name.
        secure.channelNumber = 20
        val shell = shell(PhoneUnknown::class.java, seApi = SecureElementApi::class.java, se = seReaderFor(1))
        val opened = shell.openHal(1)
        assertEquals(0, opened.getInt("channel"))
        assertEquals(4, opened.getInt("status"))
        assertEquals("closeChannel(byte)", secure.calls.last().method)
        assertEquals(20.toByte(), secure.calls.last().args.single())
    }

    @Test fun androidsRecordsOfACardGoToTheAppAndNoOthers() {
        val eid = "89086030000000000000000000000017"
        subs.records += SubRecord(eid, "8900000000000000011", "5ber", 3, 1)
        subs.records += SubRecord(eid, "8900000000000000029", "Carrier B", 13, 1, slot = 1)
        subs.records += SubRecord("8900000000000000037", "8900000000000000037", "Carrier C", 2, 1, slot = 0) // another SIM
        subs.records += SubRecord(null, "8900000000000000045", "Carrier D", 1, 1)
        val shell = android13()
        val got = shell.handle(JSONObject().put("op", "cardRecords").put("eid", eid)).getJSONArray("records")
        assertEquals(2, got.length())
        val first = got.getJSONObject(0)
        assertEquals("8900000000000000011", first.getString("iccid"))
        assertEquals("5ber", first.getString("name"))
        assertEquals(3, first.getInt("id"))
        assertFalse(first.getBoolean("inUse"))
        assertEquals(1, first.getInt("source"))
        assertTrue(got.getJSONObject(1).getBoolean("inUse"))
        assertEquals("getAllSubInfoList(String,String)", subs.calls.single().method)
        // Not an EID: refused before Android is asked.
        assertThrows(IllegalArgumentException::class.java) {
            shell.handle(JSONObject().put("op", "cardRecords").put("eid", "8900000000000000037"))
        }
    }

    @Test fun androidsRecordsBeforeAndroidElevenAndThirteen() {
        val eid = "89086030000000000000000000000017"
        subs.records += OldSubRecord(eid, "8900000000000000011", "5ber", 3, 1)
        val got = shell(Phone29::class.java).handle(JSONObject().put("op", "cardRecords").put("eid", eid))
            .getJSONArray("records").getJSONObject(0)
        assertEquals(1, got.getInt("source")) // from the hidden getNameSource
        assertEquals("getAllSubInfoList(String)", subs.calls.single().method)
    }

    @Test fun androidSeventeenQpr2NamesTheCallerWhenTransmitting() {
        val calls = shell(Phone37q2::class.java, Sub30::class.java, Request::class.java).roundTrip(1)
        assertEquals("iccTransmitApduLogicalChannelByPort(int,int,String,int,int,int,int,int,int,String)", calls[1].method)
        assertEquals(listOf(1, 0, "com.android.shell", 1) + apdu, calls[1].args)
        assertEquals("iccCloseLogicalChannel(Request)", calls[2].method)
    }

    @Test fun codeIsOnlyLoadedFromAndroidsReadOnlyPartitions() {
        for (path in listOf("/system/app/SecureElement/SecureElement.apk", "/system_ext/priv-app/SE/SE.apk",
                "/product/app/SecureElement/SecureElement.apk", "/apex/com.android.se/app/SecureElement@1/SecureElement.apk"))
            assertTrue(path, CardShell.onSystemPartition(path))
        for (path in listOf("/data/app/~~a==/com.android.se-1/base.apk", "/system/app/../../data/local/tmp/x.apk",
                "/system/./app/x.apk", "/system/app/SecureElement/SecureElement.apk.bak", "system/app/x.apk",
                "/system/app/Secure Element.apk", "/sdcard/x.apk", "/system/x.dex"))
            assertFalse(path, CardShell.onSystemPartition(path))
        assertEquals(null, CardShell.systemApk(null))
        val apk = "/system/app/SecureElement/SecureElement.apk"
        assertEquals(apk, CardShell.acceptedApk(apk, true))
        assertEquals(null, CardShell.acceptedApk(apk, false)) // not there
        assertEquals(null, CardShell.acceptedApk("/data/app/x/base.apk", true))
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
        assertEquals(listOf("getUiccSlotsInfo(String)" to listOf("com.android.shell"),
            "getUiccCardsInfo(String)" to listOf("com.android.shell")), phone.calls.map { it.method to it.args })
    }

    @Test fun slotsSayWhatAndroidKnowsWithoutTouchingACard() {
        // Android 12: the slot's own getters. A card Android has the EID of, and an ordinary SIM in use.
        phone.slots = arrayOf(Slot12(false, true, 2, true, 0, iccid), Slot12(true, false, 2, true, 1, ""),
            Slot12(true, true, 1, false, -1, ""))
        phone.cards = listOf(CardInfo(1, eid))
        val listed = shell(Phone31::class.java).slots()
        assertEquals(mapOf("euicc" to false, "removable" to true, "present" to true, "active" to true, "logical" to 0),
            listed[0] - "token")
        assertEquals(mapOf("euicc" to true, "removable" to false, "present" to true, "active" to true, "logical" to 1, "eid" to eid),
            listed[1])
        assertEquals(mapOf("euicc" to true, "removable" to true, "present" to false, "active" to false), listed[2])
        // Something to notice a change by, but never the ICCID itself.
        val token = listed[0].getValue("token") as String
        assertEquals(16, token.length)
        assertFalse(iccid in token)
    }

    @Test fun androidThirteenSaysThroughPorts() {
        // Its slot getters for these throw; one getter failing leaves the rest of the list.
        phone.slots = arrayOf(Slot13(true, true, 2, listOf(Port(false, -1), Port(true, 0)), eid),
            Slot13(true, false, 2, listOf(Port(false, -1)), "8944478600000000037F"), BrokenSlot())
        val listed = android13().slots()
        assertEquals(mapOf("euicc" to true, "removable" to true, "present" to true, "active" to true, "logical" to 0, "eid" to eid),
            listed[0] - "token")
        // A card Android has no EID for gives its ICCID as the card ID: that's no EID.
        assertEquals(mapOf("euicc" to true, "removable" to false, "present" to true, "active" to false), listed[1] - "token")
        assertEquals(mapOf("euicc" to true), listed[2])
    }

    @Test fun withoutCardInfoTheSlotsStillList() {
        phone.slots = arrayOf(Slot12(true, true, 2, true, 0, ""))
        phone.cards = listOf("not a card", null)
        assertEquals(true, shell(Phone31::class.java).slots().single()["active"])
    }

    // Stand-ins for the hidden telephony interfaces, with the methods each Android version has (ITelephony.aidl
    // and ISub.aidl at the AOSP release tags). Public, as the helper calls them by reflection.

    /** IccOpenLogicalChannelResponse. */
    class Response(val channel: Int, val status: Int, val selectResponse: ByteArray?)

    /** SubscriptionInfo. */
    class SubInfo(val subscriptionId: Int, val iccId: String)

    /** android.hardware.secure_element.ISecureElement: the few methods the helper's fallback route uses. byte and
     *  byte[] match the AIDL HAL, so the helper's reflection picks them the same way it does on a real phone. */
    interface SecureElementApi {
        fun isCardPresent(): Boolean
        fun openLogicalChannel(aid: ByteArray?, p2: Byte): SeResponse?
        fun transmit(apdu: ByteArray?): ByteArray?
        fun closeChannel(channel: Byte)
        fun asBinder(): IBinder? // IInterface's
    }

    /** android.hardware.secure_element.LogicalChannelResponse: public fields the helper reads by name. */
    class SeResponse(@JvmField val channelNumber: Byte, @JvmField val selectResponse: ByteArray?)

    /** UiccSlotInfo on Android 9: no isRemovable() yet. */
    class Slot9(private val euicc: Boolean) { fun getIsEuicc() = euicc }

    /** UiccSlotInfo on Android 10+. */
    class Slot10(private val euicc: Boolean, private val removable: Boolean) {
        fun getIsEuicc() = euicc
        fun isRemovable() = removable
    }

    /** UiccSlotInfo on Android 10-12L, with what describes the slot. */
    class Slot12(private val euicc: Boolean, private val removable: Boolean, private val state: Int, private val active: Boolean,
                 private val logical: Int, private val cardId: String) {
        fun getIsEuicc() = euicc
        fun isRemovable() = removable
        fun getCardStateInfo() = state
        fun getIsActive() = active
        fun getLogicalSlotIdx() = logical
        fun getCardId() = cardId
    }

    /** UiccSlotInfo on Android 13+: by port, and the slot's own getters throw. */
    class Slot13(private val euicc: Boolean, private val removable: Boolean, private val state: Int,
                 private val ports: List<Port>, private val cardId: String) {
        fun getIsEuicc() = euicc
        fun isRemovable() = removable
        fun getCardStateInfo() = state
        fun getPorts() = ports
        fun getCardId() = cardId
        fun getIsActive(): Boolean = throw UnsupportedOperationException("getIsActive() is not supported by UiccSlotInfo")
        fun getLogicalSlotIdx(): Int = throw UnsupportedOperationException("getLogicalSlotIdx() is not supported")
    }

    /** UiccPortInfo. */
    class Port(private val active: Boolean, private val logical: Int) {
        fun isActive() = active
        fun getLogicalSlotIndex() = logical
    }

    /** A slot whose getters beyond the first all fail, as on some phone. */
    class BrokenSlot {
        fun getIsEuicc() = true
        fun getCardStateInfo(): Int = throw IllegalStateException("no")
        fun getPorts(): List<Port> = throw IllegalStateException("no")
        fun getCardId(): String = throw IllegalStateException("no")
    }

    /** UiccCardInfo. */
    class CardInfo(private val slot: Int, private val eid: String?) {
        fun getSlotIndex() = slot
        fun getEid() = eid
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
        fun getUiccCardsInfo(callingPackage: String?): List<Any?>?
    }

    /** Android 12-12L (API 31-32). */
    interface Phone31 {
        fun iccOpenLogicalChannelBySlot(slotIndex: Int, callingPackage: String?, aid: String?, p2: Int): Response?
        fun iccTransmitApduLogicalChannelBySlot(slotIndex: Int, channel: Int, cla: Int, ins: Int, p1: Int, p2: Int, p3: Int, data: String?): String?
        fun iccCloseLogicalChannelBySlot(slotIndex: Int, channel: Int): Boolean
        fun setDataEnabledForReason(subId: Int, reason: Int, enable: Boolean)
        fun setDataRoamingEnabled(subId: Int, isEnabled: Boolean)
        fun getUiccSlotsInfo(): Array<Any?>?
        fun getUiccCardsInfo(callingPackage: String?): List<Any?>?
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
        fun getUiccCardsInfo(callingPackage: String?): List<Any?>?
    }

    /** Android 17 QPR2 (as on its emulator image, CP41): transmit by port also takes the calling package. */
    interface Phone37q2 {
        fun iccOpenLogicalChannel(request: Request?): Response?
        fun iccCloseLogicalChannel(request: Request?): Boolean
        fun iccTransmitApduLogicalChannelByPort(slotIndex: Int, portIndex: Int, callingPackage: String?, channel: Int, cla: Int,
                                                ins: Int, p1: Int, p2: Int, p3: Int, data: String?): String?
        fun iccTransmitApduLogicalChannel(subId: Int, callingPackage: String?, channel: Int, cla: Int, ins: Int, p1: Int,
                                          p2: Int, p3: Int, data: String?): String?
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
        fun getAllSubInfoList(callingPackage: String?): List<Any?>?
        fun getActiveSubscriptionInfoForSimSlotIndex(slotIndex: Int, callingPackage: String?): SubInfo?
        fun getSubId(slotIndex: Int): IntArray?
        fun getActiveSubInfoCountMax(): Int
        fun setDataRoaming(roaming: Int, subId: Int): Int
        fun setDefaultDataSubId(subId: Int)
    }

    /** ISub on Android 11-17 (API 30-37). */
    interface Sub30 {
        fun getAllSubInfoList(callingPackage: String?, callingFeatureId: String?): List<Any?>?
        fun getActiveSubscriptionInfoForSimSlotIndex(slotIndex: Int, callingPackage: String?, callingFeatureId: String?): SubInfo?
        fun getSubId(slotIndex: Int): IntArray?
        fun getActiveSubInfoCountMax(): Int
        fun setDataRoaming(roaming: Int, subId: Int): Int
        fun setDefaultDataSubId(subId: Int)
    }
}
