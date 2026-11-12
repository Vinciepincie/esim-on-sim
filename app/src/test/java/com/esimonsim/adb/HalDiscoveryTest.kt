package com.esimonsim.adb

import android.os.IBinder
import com.esimonsim.apdu.AdbApduBackend
import com.esimonsim.apdu.ApduBackend
import com.esimonsim.apdu.ApduException
import com.esimonsim.apdu.OpenResult
import com.esimonsim.apdu.SlotInfo
import com.esimonsim.lpa.Lpa
import com.esimonsim.lpa.SlotLook
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Callable

/**
 * Issue #1: with root on a Pixel 9 Pro XL, 0.5 showed a 5ber card as empty. The real [Lpa] (discover, then getProfiles,
 * as the app does) over the real [CardShell], with stand-ins for a rooted Android 13+ phone:
 *  - slot 0, the tray: a 5ber (two eSIMs) whose eSIM manager answers the standard ID, by prefix too, and 5ber's own;
 *  - slot 1: the phone's built-in eSIM chip (no eSIMs), reported as built in, with its EID.
 * Telephony may refuse or miss the prefix ([Tel]); the Secure Element HAL's readers may follow the phone's SIM numbers
 * rather than its trays ([Hal]). 0.5 asked the HAL on each telephony miss and read SIM1, the built-in chip, as the card.
 */
class HalDiscoveryTest {
    private companion object {
        const val EID_5BER = "89044045000000000000000000000001"
        const val EID_CHIP = "89049032000000000000000000000002"
        const val ISD_R_STD = "A0000005591010FFFFFFFF8900000100"
        const val ISD_R_5BER = "A0000005591010FFFFFFFF8900050500"
        const val EID_OTHER = "89033023000000000000000000000003"
        const val LOST = "Card connection lost or timed out. Reconnect and refresh before trying again."
    }

    /** An eUICC: answers GET EID and the profile list. [partial]: selects by the 15-byte prefix too. */
    class Euicc(val name: String, val eid: String, private val profiles: Int, private val applets: List<String>,
                private val partial: Boolean = true) {
        fun selects(aidHex: String) = aidHex.uppercase().let { want ->
            applets.any { if (partial) it.startsWith(want) else it == want }
        }
        fun answer(ins: Int, data: String): String = when {
            ins != 0xE2 -> "6D00"
            data.uppercase().startsWith("BF3E") -> tlv("BF3E", tlv("5A", eid)) + "9000"
            data.uppercase().startsWith("BF2D") -> tlv("BF2D", tlv("A0", (1..profiles).joinToString("") { profile(it) })) + "9000"
            else -> "6A88"
        }
        private fun profile(i: Int) = tlv("E3", tlv("5A", "98440000000000000${i}F0") +
            tlv("4F", "A0000005591010FFFFFFFF890000110$i") + tlv("9F70", if (i == 1) "01" else "00") + tlv("95", "02"))
    }

    // Android 13+ hidden telephony, as much as the helper calls.
    class Response(val channel: Int, val status: Int, val selectResponse: ByteArray?)
    class SubInfo(val subscriptionId: Int, val iccId: String)
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
    class Slot(private val euicc: Boolean, private val removable: Boolean, private val ports: List<Port>,
               private val cardId: String, private val state: Int = 2) {
        fun getIsEuicc() = euicc
        fun isRemovable() = removable
        fun getCardStateInfo() = state // 2: CARD_STATE_INFO_PRESENT, 1: ABSENT
        fun getPorts() = ports
        fun getCardId() = cardId
    }
    class Port(private val logical: Int) {
        fun isActive() = true
        fun getLogicalSlotIndex() = logical
    }
    class CardInfo(private val slot: Int, private val eid: String?) {
        fun getSlotIndex() = slot
        fun getEid() = eid
    }
    interface Phone33 {
        fun iccOpenLogicalChannel(request: Request?): Response?
        fun iccCloseLogicalChannel(request: Request?): Boolean
        fun iccTransmitApduLogicalChannelByPort(slotIndex: Int, portIndex: Int, channel: Int, cla: Int, ins: Int, p1: Int,
                                                p2: Int, p3: Int, data: String?): String?
        fun getUiccSlotsInfo(callingPackage: String?): Array<Any?>?
        fun getUiccCardsInfo(callingPackage: String?): List<Any?>?
    }
    interface Sub30 {
        fun getActiveSubscriptionInfoForSimSlotIndex(slotIndex: Int, callingPackage: String?, callingFeatureId: String?): SubInfo?
        fun getSubId(slotIndex: Int): IntArray?
        fun getActiveSubInfoCountMax(): Int
    }
    /** android.hardware.secure_element.ISecureElement, the methods the helper calls. */
    interface SecureElementApi {
        fun isCardPresent(): Boolean
        fun openLogicalChannel(aid: ByteArray?, p2: Byte): SeResponse?
        fun transmit(apdu: ByteArray?): ByteArray?
        fun closeChannel(channel: Byte)
        fun asBinder(): IBinder?
    }
    class SeResponse(@JvmField val channelNumber: Byte, @JvmField val selectResponse: ByteArray?)

    /** What telephony does with the ISD-R IDs, on every slot. */
    enum class Tel {
        /** Opens the prefix. */
        OPENS,
        /** Refuses the prefix and the lower-case ID (SecurityException), lets the makers' IDs through. */
        REFUSES_STANDARD,
        /** The card answers the prefix with "no such applet", as later 5ber batches are reported to. */
        NO_SUCH_APPLET,
        /** Refuses the helper everything, as some makers' builds do: the HAL is the only way in. */
        REFUSES_ALL,
        /** Answers every open with "no such applet", throwing nothing. */
        MISSES_ALL,
        /** Refuses the helper the tray (slot 0) only; the built-in chip in slot 1 opens. */
        REFUSES_TRAY,
        /** The tray's card is busy, as while it restarts: no free channel there (status 2); the chip in slot 1 opens. */
        TRAY_BUSY,
    }

    /** Which card each Secure Element HAL reader reaches. */
    enum class Hal { NONE, BY_TRAY, BY_SIM_NUMBER }

    /** One phone. [swapped]: the phone numbers its SIMs against its trays (the tray is SIM 2), as a Pixel may.
     *  [reportsNumbers]: Android says which SIM number each slot has. [reportsEids]: Android gives the cards' EIDs.
     *  [trayRestarting]: the 5ber answers nothing for now, as while it restarts. [telephonyMute]: telephony opens channels
     *  but every command on them fails. [chipAbsent]: Android calls the built-in
     *  chip's slot empty. [secondCard]: slot 1 holds a second removable card instead of the built-in chip.
     *  [trayOrdinary]: the tray holds an ordinary SIM. [trayReaderMute]: commands to the tray's card through a reader
     *  fail too. [trayEmpty]: Android calls the tray empty (settable mid-test). */
    inner class Phone(val tel: Tel, var hal: Hal, swapped: Boolean = hal == Hal.BY_SIM_NUMBER,
                      private val reportsNumbers: Boolean = true, private val reportsEids: Boolean = true,
                      trayRestarting: Boolean = false, private val chipAbsent: Boolean = false,
                      private val secondCard: Boolean = false, private val telephonyMute: Boolean = false,
                      private val trayOrdinary: Boolean = false, private val trayReaderMute: Boolean = false,
                      var trayEmpty: Boolean = false) {
        /** The links made by [lpa], each counting the Secure Element requests the app sent. */
        private val backends = ArrayList<Backend>()
        /** Every Secure Element HAL request the app sent the helper, a reader there or not. */
        val halRequests get() = backends.sumOf { it.halRequests }
        val fiveBer = if (trayOrdinary) Euicc("sim", "", 0, emptyList())
            else Euicc("5ber", EID_5BER, 2, if (trayRestarting) emptyList() else listOf(ISD_R_STD, ISD_R_5BER))
        /** Every reader open asked for, answered or not. */
        var readerOpens = 0
        val chip = if (secondCard) Euicc("other", EID_OTHER, 1, listOf(ISD_R_STD)) else Euicc("chip", EID_CHIP, 0, listOf(ISD_R_STD))
        private val bySlot = mapOf(0 to fiveBer, 1 to chip)
        private val sim = if (swapped) mapOf(0 to 1, 1 to 0) else mapOf(0 to 0, 1 to 1)
        /** Opens that gave a channel, as "tel slot ID -> card" or "hal SIMn ID -> card". */
        val opened = ArrayList<String>()
        /** Every telephony open asked for, answered or not. */
        var telephonyOpens = 0

        private val telephony = object : InvocationHandler {
            private var next = 1
            private val open = HashMap<Int, Euicc>()
            override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
                "iccOpenLogicalChannel" -> {
                    val r = args!![0] as Request
                    val aid = r.aid!!
                    telephonyOpens++
                    val standard = aid.uppercase().startsWith(ISD_R_STD.take(30))
                    if (tel == Tel.REFUSES_ALL || (tel == Tel.REFUSES_STANDARD && standard) ||
                        (tel == Tel.REFUSES_TRAY && r.slotIndex == 0))
                        throw SecurityException("The calling package is not allowed to access ISD-R.")
                    val card = bySlot[r.slotIndex]
                    when {
                        card == null -> Response(-1, 2, null)
                        tel == Tel.TRAY_BUSY && r.slotIndex == 0 -> Response(-1, 2, null)
                        tel == Tel.MISSES_ALL -> Response(-1, 3, null)
                        tel == Tel.NO_SUCH_APPLET && card === fiveBer && standard -> Response(-1, 3, null)
                        !card.selects(aid) -> Response(-1, 3, null)
                        else -> {
                            val ch = next++
                            open[ch] = card
                            opened += "tel ${r.slotIndex} $aid -> ${card.name}"
                            Response(ch, 1, byteArrayOf(0x90.toByte(), 0))
                        }
                    }
                }
                "iccTransmitApduLogicalChannelByPort" -> if (telephonyMute) "6F00"
                    else open[args!![2] as Int]?.answer(args[4] as Int, args[8] as String) ?: "6881"
                "iccCloseLogicalChannel" -> { open.remove((args!![0] as Request).channel); true }
                // Without numbers: each slot still in use, with no SIM number to go by.
                "getUiccSlotsInfo" -> arrayOf<Any?>(
                    Slot(!trayOrdinary, true, listOf(Port(if (reportsNumbers) sim.getValue(0) else -1)),
                        if (reportsEids && !trayOrdinary) EID_5BER else if (trayOrdinary) "8931080000000000001" else "",
                        if (trayEmpty) 1 else 2),
                    Slot(true, secondCard, listOf(Port(if (reportsNumbers) sim.getValue(1) else -1)),
                        if (reportsEids) chip.eid else "", if (chipAbsent) 1 else 2))
                "getUiccCardsInfo" -> if (reportsEids) listOf(CardInfo(0, fiveBer.eid.ifEmpty { null }), CardInfo(1, chip.eid))
                    else listOf(CardInfo(0, null), CardInfo(1, null))
                "getActiveSubInfoCountMax" -> 2
                else -> if (method.returnType == Boolean::class.javaPrimitiveType) false else null
            }
        }

        /** HAL reader [n] (0 for SIM1). The card it reaches is looked up at each call, so a test can renumber the SIMs
         *  under a reader the helper has kept. */
        private inner class Reader(private val n: Int) : InvocationHandler {
            private var next = 1
            private val card get() = cardAt(n) ?: throw IllegalStateException("IOERROR")
            override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
                "isCardPresent" -> cardAt(n) != null
                "openLogicalChannel" -> {
                    readerOpens++
                    val aid = hex(args!![0] as ByteArray)
                    if (!card.selects(aid)) throw IllegalStateException("NO_SUCH_ELEMENT")
                    opened += "hal SIM${n + 1} $aid -> ${card.name}"
                    SeResponse((next++).toByte(), byteArrayOf(0x90.toByte(), 0))
                }
                "transmit" -> if (trayReaderMute && card === fiveBer) unhex("6F00")
                    else (args!![0] as ByteArray).let { unhex(card.answer(it[1].toInt() and 0xFF, hex(it.copyOfRange(5, it.size)))) }
                else -> null // closeChannel, asBinder
            }
        }

        private fun cardAt(n: Int) = when (hal) {
            Hal.NONE -> null
            Hal.BY_TRAY -> bySlot[n]
            Hal.BY_SIM_NUMBER -> bySlot[sim.entries.firstOrNull { it.value == n }?.key]
        }

        private fun reader(n: Int): Any? = if (hal == Hal.NONE || n !in 0..1) null
            else Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SecureElementApi::class.java), Reader(n))

        val shell = CardShell(Phone33::class.java,
            { Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Phone33::class.java), telephony) },
            Request::class.java, Sub30::class.java,
            { Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Sub30::class.java), telephony) },
            { _ -> "" }, 36, { _, _, _ -> },
            Callable<Class<*>?> { SecureElementApi::class.java }, CardShell.SecureElement { n -> reader(n) },
            CardShell.Clock { 0L })

        fun lpa(dropsOnHal: Boolean = false, failsHalOnce: Boolean = false) =
            Lpa(Backend(shell, dropsOnHal, failsHalOnce).also { backends += it }, phoneHasEsim = true)
    }

    /** The app's side of the helper: requests as JSON, errors worded as the app words them. */
    /** [dropsOnHal]: the link to the helper goes the first time the HAL is asked. [failsHalOnce]: the first HAL request
     *  fails, as a passing helper error would. */
    class Backend(private val shell: CardShell, private val dropsOnHal: Boolean = false,
                  private var failsHalOnce: Boolean = false) : ApduBackend {
        private var ready = true
        var halRequests = 0
        private fun rpc(q: JSONObject): JSONObject = try { shell.handle(q) } catch (e: Exception) {
            throw AdbApduBackend.helperError(HelperServer.error(e).getString("error"))
        }
        private fun request(op: String, slot: Int, port: Int, channel: Int = 0) =
            JSONObject().put("op", op).put("slot", slot).put("port", port).put("channel", channel)
        private fun opened(r: JSONObject) =
            OpenResult(r.getInt("channel"), r.getInt("status"), r.getString("select").takeIf { it.isNotEmpty() }?.let(::unhex))
        override fun isReady() = ready
        override fun slots(): List<SlotInfo> = SlotInfo.list(rpc(JSONObject().put("op", "slots")).getJSONArray("slots"))
        override fun openChannel(slot: Int, port: Int, aidHex: String) = opened(rpc(request("open", slot, port).put("aid", aidHex)))
        override fun openChannelViaSecureElement(slot: Int, port: Int, reader: Int, aidHex: String): OpenResult {
            halRequests++
            if (dropsOnHal) { ready = false; throw ApduException(LOST) }
            if (failsHalOnce) { failsHalOnce = false; throw ApduException("The card didn't answer.") }
            return opened(rpc(request("open", slot, port).put("aid", aidHex).put("via", "hal").put("reader", reader)))
        }
        override fun transmit(slot: Int, port: Int, channel: Int, cla: Int, ins: Int, p1: Int, p2: Int, p3: Int, dataHex: String): String =
            rpc(request("transmit", slot, port, channel).put("cla", cla).put("ins", ins).put("p1", p1).put("p2", p2)
                .put("p3", p3).put("data", dataHex)).getString("response")
        override fun closeChannel(slot: Int, port: Int, channel: Int) { runCatching { rpc(request("close", slot, port, channel)) } }
    }

    @Test fun theIssueOnePixelShowsTheFiveBerAgainThroughTelephony() {
        // 0.5 showed the built-in chip, empty, as the tray's card in each of these.
        for (tel in listOf(Tel.REFUSES_STANDARD, Tel.NO_SUCH_APPLET)) for (upgrade in listOf(false, true)) {
            val phone = Phone(tel, Hal.BY_SIM_NUMBER, reportsNumbers = false) // the reader numbers can't be told
            val lpa = phone.lpa().also { if (upgrade) it.slotHint = 0 }
            assertEquals("$tel", EID_5BER, lpa.discover())
            assertEquals("$tel", 2, lpa.getProfiles().size)
            assertEquals("$tel", listOf("tel 0 $ISD_R_5BER -> 5ber"), phone.opened.distinct())
            assertEquals(-1, lpa.card!!.reader)
        }
    }

    @Test fun aCardTelephonyOpensNeverReachesTheHal() {
        for (hal in Hal.values()) {
            val phone = Phone(Tel.OPENS, hal)
            val lpa = phone.lpa()
            assertEquals(EID_5BER, lpa.discover())
            assertEquals(2, lpa.getProfiles().size)
            assertTrue("$hal: ${phone.opened}", phone.opened.all { it.startsWith("tel 0 ") })
        }
    }

    @Test fun whereTelephonyRefusesEverythingTheHalFindsTheCardByItsSimNumber() {
        // The case the HAL is for. The tray is SIM 2 here: its reader is SIM2, not SIM1 as 0.5 assumed.
        for (hal in listOf(Hal.BY_TRAY, Hal.BY_SIM_NUMBER)) {
            val phone = Phone(Tel.REFUSES_ALL, hal)
            val lpa = phone.lpa()
            assertEquals("$hal", EID_5BER, lpa.discover())
            assertFalse(lpa.onBuiltInSlot)
            assertEquals(2, lpa.getProfiles().size)
            assertTrue("$hal: ${phone.opened}", phone.opened.all { it.startsWith("hal ") && it.endsWith("-> 5ber") })
            assertEquals(if (hal == Hal.BY_SIM_NUMBER) 1 else 0, lpa.card!!.reader)
        }
    }

    @Test fun aHalReaderThatReachesAnotherSlotsCardIsNotTakenForTheTraysCard() {
        // SIM numbers swapped but not reported: the tray's reader by its own number, SIM1, reaches the built-in chip,
        // whose EID Android gives for slot 1. That chip must never be shown as the tray's card, empty or not.
        val phone = Phone(Tel.REFUSES_ALL, Hal.BY_SIM_NUMBER, reportsNumbers = false)
        val lpa = phone.lpa()
        val found = runCatching { lpa.discover() }
        if (found.isSuccess) {
            // Only as itself: in its own slot, where the app asks whether it's people's removable card.
            assertEquals(EID_CHIP, found.getOrThrow())
            assertEquals(1, lpa.slot)
            assertTrue(lpa.onBuiltInSlot)
        } else assertTrue(found.exceptionOrNull() is ApduException)
        assertNotEquals(0 to EID_CHIP, lpa.slot to found.getOrNull())
        assertTrue(phone.opened.toString(), phone.opened.any { it.startsWith("hal SIM1") && it.endsWith("-> chip") })
    }

    @Test fun aHalCardIsCheckedEachTimeItsOpenedAgain() {
        // Found through SIM2; then the phone numbers its SIMs anew, and SIM2 reaches the built-in chip.
        val phone = Phone(Tel.REFUSES_ALL, Hal.BY_SIM_NUMBER)
        val lpa = phone.lpa()
        assertEquals(EID_5BER, lpa.discover())
        phone.hal = Hal.BY_TRAY // reader 1 is now slot 1's: the chip
        val e = assertThrows(ApduException::class.java) { lpa.getProfiles() }
        assertEquals(Lpa.OTHER_CARD, e.message)
        assertTrue(phone.opened.last().endsWith("-> chip")) // opened, asked for its EID, and left alone
    }

    @Test fun withNoEidsFromAndroidACardThatMayBeTheChipIsAskedAbout() {
        // Android gives no EIDs, so nothing tells the tray's card from the chip. A reader whose number is also the
        // built-in chip's slot may be reaching the chip: whatever it finds is marked so, and the app asks people.
        for (hal in listOf(Hal.BY_TRAY, Hal.BY_SIM_NUMBER)) {
            val phone = Phone(Tel.REFUSES_ALL, hal, swapped = true, reportsEids = false)
            val lpa = phone.lpa()
            val found = lpa.discover()
            assertTrue("$hal found $found on slot ${lpa.slot}", lpa.onBuiltInSlot)
        }
    }

    @Test fun aGuessedReaderDoesntCountWhileAnotherSimIsInUse() {
        // Android gives no SIM numbers and no EIDs, so every reader is a guess by slot number, and both slots are in
        // use. Each reader reaches the other slot's card here: both are opened, and both turned away.
        val phone = Phone(Tel.REFUSES_ALL, Hal.BY_SIM_NUMBER, swapped = true, reportsNumbers = false, reportsEids = false)
        assertThrows(ApduException::class.java) { phone.lpa().discover() }
        assertTrue(phone.opened.toString(), phone.opened.any { it.startsWith("hal SIM1 ") && it.endsWith("-> chip") })
        assertTrue(phone.opened.toString(), phone.opened.any { it.startsWith("hal SIM2 ") && it.endsWith("-> 5ber") })
    }

    @Test fun aTrayCardRestartingIsNeverReplacedByTheChip() {
        // The tray's card is restarting, and Android gives no EIDs. The tray's reader by its own number, a guess, is
        // the built-in chip's here: never taken for the tray's card. The chip may still turn up as itself.
        val phone = Phone(Tel.MISSES_ALL, Hal.BY_SIM_NUMBER, reportsEids = false, trayRestarting = true)
        val lpa = phone.lpa()
        val found = runCatching { lpa.discover() }
        if (found.isSuccess) {
            assertEquals(1, lpa.slot)
            assertTrue(lpa.onBuiltInSlot)
        } else assertTrue(found.exceptionOrNull() is ApduException)
        assertNotEquals(0 to EID_CHIP, lpa.slot to found.getOrNull())
    }

    @Test fun aTrayCardOnlyTheHalReachesGoesBeforeThePhonesOwnChip() {
        // Telephony refuses the helper the tray but opens the built-in chip: the tray's card, through its reader, wins.
        for (hal in listOf(Hal.BY_TRAY, Hal.BY_SIM_NUMBER)) {
            val phone = Phone(Tel.REFUSES_TRAY, hal)
            val lpa = phone.lpa()
            assertEquals("$hal", EID_5BER, lpa.discover())
            assertEquals(0, lpa.slot)
            assertFalse(lpa.onBuiltInSlot)
            assertEquals(2, lpa.getProfiles().size)
        }
        // No HAL: the chip, as before, with the question whether it's people's removable card.
        val lpa = Phone(Tel.REFUSES_TRAY, Hal.NONE).lpa()
        assertEquals(EID_CHIP, lpa.discover())
        assertTrue(lpa.onBuiltInSlot)
    }

    @Test fun anOrdinarySimInTheTrayIsntAskedThroughItsReaderAtEachRefresh() {
        // The tray holds an ordinary SIM (Android says so), the app shows the phone's own chip: no reader requests.
        val phone = Phone(Tel.OPENS, Hal.BY_TRAY, trayOrdinary = true)
        val lpa = phone.lpa()
        assertEquals(EID_CHIP, lpa.discover())
        assertTrue(lpa.onBuiltInSlot)
        assertTrue(phone.opened.toString(), phone.opened.none { it.startsWith("hal ") })
        assertEquals(0, phone.readerOpens)
        assertEquals(0, phone.halRequests) // not even for SIM3 or SIM4, which a two-slot phone hasn't got
    }

    @Test fun theChipTelephonyFoundIsNeverTakenForTheTraysCardThroughAReader() {
        // The tray's reader by SIM number reaches the chip (readers by tray, SIM numbers swapped), and Android gives no
        // EIDs: the chip's EID, read through telephony, still rules it out. Its own slot's reader guess is turned away
        // too, as the chip's slot is in use: the chip it is, held back, as telephony found it.
        val phone = Phone(Tel.REFUSES_TRAY, Hal.BY_TRAY, swapped = true, reportsEids = false)
        val lpa = phone.lpa()
        assertEquals(EID_CHIP, lpa.discover())
        assertEquals(1, lpa.slot)
        assertTrue(lpa.onBuiltInSlot)
        assertEquals(-1, lpa.card!!.reader)
        assertTrue(phone.opened.toString(), phone.opened.any { it.startsWith("hal SIM2 ") && it.endsWith("-> chip") })
    }

    @Test fun aChipPeopleSaidIsTheirsIsntPassedOverForACardOnlyAReaderReaches() {
        val phone = Phone(Tel.REFUSES_TRAY, Hal.BY_TRAY)
        val lpa = phone.lpa().also { it.slotHint = 1 }
        assertEquals(EID_CHIP, lpa.discover())
        assertEquals(1, lpa.slot)
        assertEquals(0, phone.halRequests)
    }

    @Test fun withTheTraysCardBusyTheHeldChipSaysSo() {
        // The tray's eSIM card is busy, the chip answers: held back as always, with the tray named as not answering.
        val busy = Phone(Tel.TRAY_BUSY, Hal.NONE).lpa()
        assertEquals(EID_CHIP, busy.discover())
        assertTrue(busy.onBuiltInSlot)
        assertEquals(0, busy.quietSlot)
        // Refused, not busy: trying again wouldn't change that, so not said.
        assertEquals(-1, Phone(Tel.REFUSES_TRAY, Hal.NONE).lpa().also { it.discover() }.quietSlot)
        // An ordinary SIM in the tray: nothing to wait for.
        assertEquals(-1, Phone(Tel.OPENS, Hal.NONE, trayOrdinary = true).lpa().also { it.discover() }.quietSlot)
        // A card found: not said either.
        assertEquals(-1, Phone(Tel.OPENS, Hal.NONE).lpa().also { it.discover() }.quietSlot)
    }

    @Test fun aLinkLostLookingPastTheChipIsSaidAsSuch() {
        val e = assertThrows(ApduException::class.java) { Phone(Tel.REFUSES_TRAY, Hal.BY_TRAY).lpa(dropsOnHal = true).discover() }
        assertEquals(LOST, e.message)
    }

    @Test fun aChipInASlotAndroidCallsEmptyStillCountsAsMaybeTheChip() {
        // The tray's reader by SIM number reaches the chip; Android calls the chip's slot empty and gives no EIDs.
        val phone = Phone(Tel.REFUSES_ALL, Hal.BY_TRAY, swapped = true, reportsEids = false, chipAbsent = true)
        val lpa = phone.lpa()
        lpa.discover()
        assertTrue(lpa.onBuiltInSlot)
    }

    @Test fun onePassingReaderErrorDoesntEndTheSearch() {
        val lpa = Phone(Tel.REFUSES_ALL, Hal.BY_SIM_NUMBER).lpa(failsHalOnce = true)
        assertEquals(EID_5BER, lpa.discover())
    }

    @Test fun aRefreshAsksTheReaderTheCardWasFoundOnFirst() {
        val phone = Phone(Tel.REFUSES_ALL, Hal.BY_SIM_NUMBER)
        val lpa = phone.lpa()
        lpa.discover()
        phone.telephonyOpens = 0
        phone.opened.clear()
        val again = lpa.again()
        assertEquals(EID_5BER, again.discover())
        assertEquals(0, phone.telephonyOpens)
        assertEquals(1, phone.opened.size)
        assertEquals(1, again.card!!.reader)
        // Another card there now: the usual search, which finds no card through telephony.
        phone.hal = Hal.BY_TRAY // reader 1 reaches the chip now, reader 0 the 5ber
        assertEquals(EID_5BER, lpa.again().discover())
        assertTrue(phone.telephonyOpens > 0)
    }

    @Test fun aSecondCardOnlyTheHalReachesIsFoundByTheLookForOtherCards() {
        // Two removable cards, the tray's SIM numbers swapped (slot 0 is SIM 2): discovery finds one, the look the other.
        for (tel in listOf(Tel.REFUSES_ALL, Tel.MISSES_ALL)) {
            val phone = Phone(tel, Hal.BY_SIM_NUMBER, secondCard = true)
            val lpa = phone.lpa()
            val first = lpa.discover()
            val (otherEid, otherSlot) = if (first == EID_OTHER) EID_5BER to 0 else EID_OTHER to 1
            val other = lpa.scan(lpa.slot).single { it.slot == otherSlot }
            assertEquals("$tel", SlotLook.What.CARD, other.what)
            assertEquals(otherEid, other.card!!.eid)
            assertEquals(1 - otherSlot, other.card!!.reader) // its reader by SIM number
            // And it's reached the same way when picked.
            val count = if (otherEid == EID_5BER) 2 else 1
            assertEquals(count, other.profiles!!.size)
            assertEquals(count, lpa.at(other.card!!).getProfiles().size)
        }
    }

    @Test fun theLookForOtherCardsNeverListsTheCardInViewAgain() {
        // No EIDs from Android, the tray's 5ber restarting: discovery finds the second card. Then the readers come to
        // follow SIM numbers, so slot 1's reader by its SIM number reaches that card, the one in view, which only its
        // being in view tells apart (the reader isn't a guess, and there are no EIDs to compare).
        val phone = Phone(Tel.REFUSES_ALL, Hal.BY_TRAY, swapped = true, secondCard = true, reportsEids = false,
            trayRestarting = true)
        val lpa = phone.lpa()
        val inView = lpa.discover()
        assertEquals(EID_OTHER, inView)
        phone.hal = Hal.BY_SIM_NUMBER
        phone.opened.clear()
        val looks = lpa.scan(lpa.slot)
        assertTrue(looks.toString(), looks.none { it.slot != lpa.slot && it.card?.eid == inView })
        assertTrue(phone.opened.toString(), phone.opened.any { it.startsWith("hal SIM1 ") && it.endsWith("-> other") })
    }

    @Test fun theLookNeverListsThePhonesOwnChipThroughAReader() {
        // The tray's reader by SIM number reaches the chip, and Android gives no EIDs: held back, never listed.
        val phone = Phone(Tel.REFUSES_ALL, Hal.BY_TRAY, swapped = true, reportsEids = false)
        val looks = phone.lpa().scan(-1)
        assertTrue(looks.toString(), looks.none { it.card?.eid == EID_CHIP })
        assertTrue(phone.opened.toString(), phone.opened.any { it.startsWith("hal SIM2 ") && it.endsWith("-> chip") })
        // With EIDs, the chip is told apart, and the tray's own card found by its slot number, which Android confirms.
        val withEids = Phone(Tel.REFUSES_ALL, Hal.BY_TRAY, swapped = true)
        val tray = withEids.lpa().scan(-1).single { it.slot == 0 }
        assertEquals(SlotLook.What.CARD, tray.what)
        assertEquals(EID_5BER, tray.card!!.eid)
        assertEquals(0, tray.card!!.reader)
    }

    @Test fun theLookAsksReadersOnlyOnceTelephonyHasBeenThroughEverySlot() {
        // Slot 0's reader by SIM number reaches slot 1's card, which telephony finds in slot 1: listed once, in slot 1.
        val phone = Phone(Tel.REFUSES_TRAY, Hal.BY_TRAY, swapped = true, secondCard = true, reportsEids = false)
        val looks = phone.lpa().scan(-1)
        assertEquals(looks.toString(), listOf(1), looks.filter { it.card?.eid == EID_OTHER }.map { it.slot })
    }

    @Test fun theLookPassesOverARefusedIdAsDiscoveryDoes() {
        // Telephony refuses the standard ID but opens 5ber's own: the 5ber shows, with no reader at all.
        val look = Phone(Tel.REFUSES_STANDARD, Hal.NONE).lpa().scan(-1).single { it.slot == 0 }
        assertEquals(SlotLook.What.CARD, look.what)
        assertEquals(EID_5BER, look.card!!.eid)
        assertEquals(-1, look.card!!.reader)
    }

    @Test fun anOrdinarySimTheReadersReachIsSettledOnce() {
        // Telephony refuses everything; the second card is found through a reader; the tray's ordinary SIM answers its
        // reader by SIM number "no such applet" to every ID: settled, marked, and not looked at again.
        val phone = Phone(Tel.REFUSES_ALL, Hal.BY_SIM_NUMBER, secondCard = true, trayOrdinary = true)
        val lpa = phone.lpa()
        assertEquals(EID_OTHER, lpa.discover())
        val before = phone.readerOpens
        val look = lpa.scan(lpa.slot).single { it.slot == 0 }
        assertEquals(SlotLook.What.EMPTY, look.what)
        assertTrue(look.noManager)
        assertEquals(CardShell.ISD_R_AIDS.size, phone.readerOpens - before) // its reader by SIM number only
        val marked = look.token!!
        val again = phone.readerOpens
        assertEquals(SlotLook.What.EMPTY, lpa.scan(lpa.slot, ordinary = { s, t -> s == 0 && t == marked }).single { it.slot == 0 }.what)
        assertEquals(again, phone.readerOpens)
    }

    @Test fun onePassingReaderErrorDoesntEndTheLookEither() {
        val looks = Phone(Tel.REFUSES_ALL, Hal.BY_SIM_NUMBER, secondCard = true).lpa(failsHalOnce = true).scan(1)
        assertEquals(EID_5BER, looks.single { it.slot == 0 }.card?.eid)
    }

    @Test fun aCardFoundThroughAReaderStaysMaybeTheChipOnARefresh() {
        val phone = Phone(Tel.REFUSES_ALL, Hal.BY_SIM_NUMBER, swapped = true, reportsEids = false)
        val lpa = phone.lpa()
        assertEquals(EID_5BER, lpa.discover())
        assertTrue(lpa.onBuiltInSlot)
        val again = lpa.again()
        assertEquals(EID_5BER, again.discover())
        assertTrue(again.onBuiltInSlot)
    }

    @Test fun aLookAnewDoesTheWholeSearch() {
        val phone = Phone(Tel.REFUSES_ALL, Hal.BY_SIM_NUMBER)
        val lpa = phone.lpa()
        lpa.discover()
        phone.telephonyOpens = 0
        assertEquals(EID_5BER, lpa.again(sameSlot = false).discover())
        assertTrue(phone.telephonyOpens > 0)
    }

    @Test fun aRefreshDoesntKeepACardWhereAndroidSaysTheSlotIsEmptyNow() {
        val phone = Phone(Tel.REFUSES_ALL, Hal.BY_SIM_NUMBER)
        val lpa = phone.lpa()
        assertEquals(EID_5BER, lpa.discover())
        phone.trayEmpty = true // the reader still answers, as a stale one might
        val again = lpa.again()
        val found = runCatching { again.discover() }
        assertNotEquals(0 to EID_5BER, again.slot to found.getOrNull())
    }

    @Test fun aFailingTrayCardIsNeverSwappedForTheChipBehindAnotherReader() {
        // The tray's card opens through telephony but fails every command, through its reader too; the chip's reader
        // works. The card's own error, not the chip.
        val phone = Phone(Tel.OPENS, Hal.BY_TRAY, telephonyMute = true, trayReaderMute = true)
        val e = assertThrows(ApduException::class.java) { phone.lpa().discover() }
        assertNotEquals(Lpa.NOT_FOUND, e.message)
    }

    @Test fun aCardWhoseTelephonyReadFailsIsTriedThroughItsReader() {
        // Telephony opens the tray's card but can't talk to it (its answers come back empty): the reader can.
        val phone = Phone(Tel.OPENS, Hal.BY_TRAY, telephonyMute = true)
        val lpa = phone.lpa()
        assertEquals(EID_5BER, lpa.discover())
        assertEquals(0, lpa.card!!.reader)
        assertEquals(2, lpa.getProfiles().size)
        // No reader: the card's own error, not "not found".
        val e = assertThrows(ApduException::class.java) { Phone(Tel.OPENS, Hal.NONE, telephonyMute = true).lpa().discover() }
        assertNotEquals(Lpa.NOT_FOUND, e.message)
    }

    @Test fun losingTheHelperDuringTheHalSearchIsSaidAsSuch() {
        // Telephony answers every open with a status, so nothing failed before the HAL search; then the link goes.
        val phone = Phone(Tel.MISSES_ALL, Hal.BY_SIM_NUMBER)
        val e = assertThrows(ApduException::class.java) { phone.lpa(dropsOnHal = true).discover() }
        assertEquals(LOST, e.message)
    }

    @Test fun theRouteStaysWithTheCard() {
        val phone = Phone(Tel.REFUSES_ALL, Hal.BY_SIM_NUMBER)
        val found = phone.lpa().also { it.discover() }.card!!
        assertEquals(1, found.reader)
        phone.opened.clear()
        assertEquals(2, phone.lpa().at(found).getProfiles().size)
        assertEquals(listOf("hal SIM2 ${found.isdR} -> 5ber"), phone.opened)
    }
}

private fun tlv(tag: String, value: String): String {
    val n = value.length / 2
    val len = if (n < 0x80) "%02X".format(n) else if (n < 0x100) "81%02X".format(n) else "82%04X".format(n)
    return tag + len + value
}
private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }
private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
