package com.esimonsim.lpa

import android.util.Log
import com.esimonsim.adb.CardShell
import com.esimonsim.apdu.ApduBackend
import com.esimonsim.apdu.ApduException
import com.esimonsim.es9.Es9Client
import com.esimonsim.es9.Es9Client.Companion.b64
import com.esimonsim.es9.Es9Client.Companion.unb64
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.security.MessageDigest

/** Which SIM slots the card can be reached in. Below Android 10 the card helper (adb/CardShell) reaches a card
 *  only through Android's own SIM for that slot. */
enum class SlotReach {
    /** Any slot: Android 10+, or direct card access. */
    ALL,
    /** Android 7-9 through the helper: SIM 1 always, another slot only while one of the card's eSIMs is on. */
    FIRST_OR_ACTIVE,
    /** Android 6 through the helper: SIM 1 only. */
    FIRST_ONLY,
}

/**
 * Local Profile Assistant: SGP.22 ES10 + ES9+ over an [ApduBackend].
 * Management (list/enable/disable/delete) and full profile download.
 * [slotReach] is asked each time it matters, as the way to the card can change while the app runs. So is
 * [accessRulesApply]: the way in is one where the card's own access rules decide what opens (direct card access),
 * which keeps the phone's built-in chip shut with no list of slots to go by.
 */
class Lpa(private val backend: ApduBackend, private val phoneHasEsim: Boolean = false,
          private val slotReach: () -> SlotReach = { SlotReach.ALL },
          private val transport: Es9Client.Transport = Es9Client.Https,
          private val accessRulesApply: () -> Boolean = { false }) {

    companion object {
        const val ISD_R = "A0000005591010FFFFFFFF8900000100"
        // 15-byte prefix of the ISD-R AID. On this removable eUICC the applet is
        // selected by partial-AID match (ISO 7816-4, P2 = first occurrence), which
        // still resolves to ISD-R while differing from the full AID string.
        const val ISD_R_OPEN = "A0000005591010FFFFFFFF89000001"
        private const val MSS = 120 // ES10 STORE DATA max block payload (per lpac)
        const val MAX_NICKNAME_BYTES = 64 // profileNickname UTF8String (SIZE(0..64))
        internal const val CARD_FULL = "The card doesn't have enough free space for this eSIM. Delete an eSIM you no " +
            "longer use, then try again. The activation code often still works after a failed install; if it " +
            "doesn't, ask your provider for a new one."
        /** Android answers the same for a card with no free channel, as when another app holds them, as for no card. */
        internal const val CARD_BUSY_TIP = " If another eSIM app is open, close it. If this keeps happening, restart your phone."
        internal const val NOT_FOUND = "Couldn't find the eSIM card. Check that it's inserted, then try again." + CARD_BUSY_TIP
        internal const val NOT_FOUND_FIRST_ONLY = "Couldn't find the eSIM card. On this version of Android, the " +
            "app can only reach it in SIM 1. Check that it's inserted there, then try again." + CARD_BUSY_TIP
        internal const val NOT_FOUND_FIRST_OR_ACTIVE = "Couldn't find the eSIM card. On this version of Android, " +
            "the app can only reach it in SIM 1, or in another SIM slot while one of its eSIMs is on. Check that " +
            "it's inserted, in SIM 1 if you can, then try again." + CARD_BUSY_TIP
        /** A card outside SIM 1 that Android 7-9 no longer has a SIM for (see [SlotReach.FIRST_OR_ACTIVE]). */
        internal const val OUT_OF_REACH = "The card can't be reached right now. On this version of Android, the " +
            "app can only reach a card outside SIM 1 while one of its eSIMs is on. Wait a moment and try again, " +
            "or move the card to SIM 1."
        internal const val LAST_ESIM_ON = "Turning this eSIM off would leave none on, and on this version of " +
            "Android the app can only reach a card outside SIM 1 while one of its eSIMs is on. Switch to " +
            "another eSIM instead, or move the card to SIM 1."
        private const val UNREADABLE = "The card sent an answer this app can't read. Try again."
        private const val NO_CHALLENGE = "The card didn't respond properly. Try again."
        private const val INSTALL_UNKNOWN = "The card didn't say whether the install worked. Close this screen and " +
            "refresh your list before trying again."
        internal const val GONE = "That eSIM isn't on the card any more. Refresh the list."
        internal const val OTHER_CARD = "A different card is in the phone now. Refresh the list, then try again."
        private const val TAG = "LPA"
        /** The GSMA's root discovery server, for cards that don't name one. */
        const val GSMA_SMDS = "lpa.ds.gsma.com"
        /** Google's discovery server, which Android carriers use; HyperOS and ChromeOS ask it as well. */
        const val GOOGLE_SMDS = "lpa.live.esimdiscovery.com"
        /** How often an order may be handed on to another discovery server before the download gives up. */
        private const val MAX_HANDOVERS = 3

        /** The GSMA's test discovery server. Some cards ship naming it (the 5ber does); real eSIMs are never
         *  sent there, and it doesn't answer outside test labs. */
        private val TEST_SMDS = setOf("testrootsmds.gsma.com")

        fun isTestServer(host: String) = host.trim().lowercase() in TEST_SMDS

        /** Discovery servers to ask, the card's own first unless it's a test server; each once. */
        fun discoveryServers(rootDs: String?): List<String> =
            listOfNotNull(rootDs?.takeUnless(::isTestServer), GSMA_SMDS, GOOGLE_SMDS).distinctBy { it.lowercase() }

        /** A discovery server that doesn't answer shouldn't hold up the others for long. */
        private const val SMDS_CONNECT_TIMEOUT_MS = 10_000
        /** Nor should a provider's server that has shut down hold up the notices after it. */
        private const val NOTICE_CONNECT_TIMEOUT_MS = 10_000
        /** CancelSessionReason timeout(2): with postponed(1), the only reasons that keep the order (SGP.22 3.1.3.1,
         *  5.7.14). 3.1.3.3 asks for loadBppExecutionError(5) after a failed load, but that ends the order for good
         *  when a retry may still work: a deliberate departure, to keep the activation code alive. */
        private const val CANCEL_KEEP_ORDER: Byte = 2
        // GetProfilesInfo for ICCID, ISD-P AID, state, nickname, provider, name, class.
        private val PROFILES_REQUEST = Tlv.build(0xBF2D, Hex.decode("5C085A4F9F7090919295"))
        private val REFRESH = Hex.decode("8101FF") // refreshFlag [1] = true
        // Root certificates by key ID, per euicc-manual.osmocom.org/docs/pki/ci. Mainstream providers' eSIMs
        // are signed under the GSMA's production roots, RSP2 Root CI1 and OISITE GSMA CI G1.
        private val ROOT_NAMES = mapOf(
            "81370F5125D0B1D408D4C3B232E6D25E795BEBFB" to "GSMA",
            "4C27967AD20C14B391E9601E41E604AD57C0222F" to "GSMA",
            "D7A7D0C7C04EA76076E3F44FAEBDE8779E2948D4" to "GSMA M2M",
            "F54172BDF98A95D65CBEB88A38A1C11D800A85C3" to "GSMA test",
            "C0BC70BA36929D43B467FF57570530E57AB8FCD8" to "GSMA test",
            "34EECF13156518D48D30BDF06853404D115F955D" to "GSMA test",
            "2209F61CD9EC5C9C854E787341FF83ECF9776A5B" to "GSMA test",
            "665A1433D67C1A2C5DB8B52C967F10A057BA5CB2" to "Symantec test",
            "16704B7F351E3607F18C4B70005C3A003DFD414A" to "Entrust",
            "77F0BD69D60BDC9E31DA702B221C32808BB8E42A" to "Gemalto test",
            "CD6E60B72D7A063CBC84625B91E80FE30406E13E" to "IDEMIA",
            "066D48A537D97191C7394ADC3DEC6519D1B0BF89" to "IDEMIA test",
            "B60F0B897FD630B88CFED6161F8EA808C5382AC3" to "G+D",
            "EA53ADEF3329B9509AFD755FD448B82DC1BCECFF" to "G+D test",
            "B70BA40C0F24F277B7B2050AD1E86A7CF28C4E57" to "Truphone",
            "73FCA0559907C005424825CA06F6195CC06300E4" to "Redtea",
            "148030FC246C02FF222106125A8D6BDA990AFBE9" to "CTTL (China)",
            "16B5D16048E3EA02BD4B606E5F77A4BF20808D83" to "China Unicom",
            "7C0E5470143EA8998ED9DF300574C92E5A9ACB1F" to "China Unicom test",
            "3BD3F57B34F44436E08F028B5101C0E0A9B0986E" to "China Unicom test",
            "CDF6D1C0A7B07F98A861B6E378B82F648D99663E" to "China Mobile",
            "D3EF83FC503F9C6EC4B7F6AE055B7F1373D4AB1E" to "China Telecom",
            "4EB94EA05B451A729CC5272DDF66F481701A05C9" to "China Telecom test",
        )
    }

    /** Download stages, in order, as reported to the progress callback. */
    enum class Step(val label: String) {
        READ_CARD("Reading your card…"),
        CONNECT("Contacting your eSIM provider…"),
        VERIFY_SERVER("Checking the provider's certificate…"),
        VERIFY_CARD("Confirming your card with the provider…"),
        PREPARE("Preparing the card…"),
        DOWNLOAD("Downloading the eSIM…"),
        INSTALL("Installing on the card…"),
    }

    private enum class Op(val verb: String) { ENABLE("turn on"), DISABLE("turn off"), DELETE("delete"), RENAME("rename") }

    data class DownloadOutcome(val profileName: String, val iccid: String?)

    /** Null fields: the card didn't report them. [standard] is the SGP.22 version it follows; [roots] are
     *  the key IDs of the root certificates it accepts eSIMs under, named in [rootNames]. */
    data class CardInfo(val freeBytes: Long?, val installedApps: Int?, val standard: String? = null,
                        val firmware: String? = null, val roots: List<String> = emptyList(),
                        /** From [configuredAddresses]: the card's discovery server and default provider. */
                        val rootDs: String? = null, val defaultDp: String? = null) {
        /** Who issued each root, once each; unlisted ones by the start of their key ID. */
        val rootNames: List<String> get() = roots.map { ROOT_NAMES[it] ?: "Unknown ${it.take(8)}" }.distinct()
    }

    /** A notice the card keeps for an eSIM's provider until it is delivered (SGP.22 NotificationMetadata).
     *  [signed] is the notice as it is sent, base64; only read for the ones that are sent. */
    data class Notification(val seq: Int, val event: Event, val address: String, val iccid: String?,
                            val signed: String? = null)

    /** NotificationEvent, in its bit order. */
    enum class Event { INSTALL, ENABLE, DISABLE, DELETE }

    /** An eSIM a provider has sent to this card's EID: downloaded from [smdp] with [eventId] as its matching
     *  ID. [fromDefault]: the card's own default provider, which says whether it has one only when asked. */
    data class WaitingEsim(val smdp: String, val eventId: String, val fromDefault: Boolean = false) {
        val code get() = ActivationCode(smdp, eventId)
    }

    var slot: Int = -1; private set
    var port: Int = 0; private set
    /** Where the card was when the app last ran. Only says where [discover] looks first: unlike [slot], it says
     *  nothing about where the card is now. The app saves no slot it held back, so a built-in one is a slot people
     *  said holds their card. */
    var slotHint = -1
    /** The phone reports the slot [discover] found the card in as built in, or can't say which slots are: it may well
     *  be its own eSIM chip, not a removable card. */
    var onBuiltInSlot = false; private set
    /** The card [discover] found, which is the one on screen. */
    private var eid: String? = null
    /** The eSIM manager ID that card answered to: one of [CardShell.ISD_R_AIDS]. */
    private var isdR = ISD_R_OPEN

    // ---------------- connection / discovery ----------------

    /** Finds the card. With [phoneHasEsim], Android manages the phone's own built-in eUICC, so slots it reports
     *  as built in go last: a removable card anywhere else wins. Not skipped outright, since a phone could report
     *  the card's own slot that way too: [onBuiltInSlot] lets the app ask. It asks as well when the list can't be
     *  read (Android 9 without root, or the phone process just restarted): whatever answers then could be the chip. */
    fun discover(): String {
        val reported = if (phoneHasEsim) runCatching { backend.slots() }.getOrDefault(emptyList()) else emptyList()
        val builtIn = reported.withIndex().filter { (_, s) -> s.euicc && !s.removable }.mapTo(HashSet()) { it.index }
        val unknown = phoneHasEsim && reported.isEmpty() && !accessRulesApply()
        val errors = StringBuilder()
        var failure: Throwable? = null
        var cardFailure: Throwable? = null // from a card that's there: goes before "not found"
        var cardAnswered = false
        val lastSlot = slot
        val ids = listOf(isdR) + (CardShell.ISD_R_AIDS - isdR)
        // The slot the card was in last time goes first: each other slot tried costs a channel open through
        // the modem, and a full SELECT when it holds an ordinary SIM.
        val likely = if (slot >= 0) slot else slotHint
        val (builtInSlots, others) = intArrayOf(1, 0, 2, 3).sortedByDescending { it == likely }.partition { it in builtIn }
        // The usual ID on every slot first, so a standard card never costs an ordinary SIM the makers' IDs. Built-in
        // slots last, so a maker's card in the tray beats the phone's own eUICC: a card in one does cost them, until
        // people say it's theirs ([slotHint]). It then goes after the usual ID's round and before the makers' IDs, so a
        // standard card newly in the tray still wins, even over a chip confirmed by mistake; a maker's no longer does.
        val tries = (others.map { it to isdR } + builtInSlots.filter { it == slotHint }.map { it to isdR } +
            listOf(others, builtInSlots).flatMap { group -> ids.flatMap { id -> group.map { it to id } } }).distinct()
        val noCard = HashSet<Int>()
        for ((s, id) in tries) {
            if (s in noCard) continue
            try {
                val r = backend.openChannel(s, 0, id)
                Log.i(TAG, "slot $s openChannel -> channel=${r.channel} status=${r.status}")
                cardAnswered = true
                if (r.channel > 0) {
                    // isdR only once the EID has been read: a maker's ID may select something that's no eSIM manager.
                    try { slot = s; port = 0; return readEid(r.channel).also { eid = it; isdR = id; onBuiltInSlot = unknown || s in builtIn } }
                    catch (e: Throwable) {
                        slot = lastSlot // sendEs10 needs it set; a list still on screen belongs to the last one
                        if (cardFailure == null && s !in builtIn) cardFailure = e
                        throw e
                    } finally { backend.closeChannel(s, 0, r.channel) }
                }
                if (r.status == 2) noCard += s // no card, or a slot this Android can't reach
                errors.append("slot$s:status=${r.status} ")
            } catch (e: Throwable) {
                if (failure == null) failure = e
                if (cardFailure == null && s !in builtIn && (e as? ApduException)?.cardThere == true) cardFailure = e
                errors.append("slot$s:${e.message} ")
            }
        }
        cardFailure?.let { throw it }
        // Every slot threw, or the link went partway: the connection to the card is the problem, not a missing card.
        // Not any slot that threw: slots past the phone's last do, on Android 10+.
        if (failure != null && (!cardAnswered || !backend.isReady())) throw failure
        Log.w(TAG, "No eSIM card answered: ${errors.trim()}") // slots count from 0 here, from SIM 1 in the app
        throw ApduException(when (slotReach()) {
            SlotReach.ALL -> NOT_FOUND
            SlotReach.FIRST_OR_ACTIVE -> NOT_FOUND_FIRST_OR_ACTIVE
            SlotReach.FIRST_ONLY -> NOT_FOUND_FIRST_ONLY
        })
    }

    /** The card is outside SIM 1 where Android 7-9 reach it only through the SIM of an eSIM that's on. */
    private fun reachedOnlyWhileOn() = slot > 0 && slotReach() == SlotReach.FIRST_OR_ACTIVE

    private inline fun <T> withIsdR(block: (Int) -> T): T {
        check(slot >= 0) { "call discover() first" }
        val r = backend.openChannel(slot, port, isdR)
        if (r.channel <= 0) throw ApduException(if (r.status == 2 && reachedOnlyWhileOn()) OUT_OF_REACH
            else "Couldn't open the card (status ${r.status}). Try again.")
        try { return block(r.channel) } finally { backend.closeChannel(slot, port, r.channel) }
    }

    // ---------------- ES10 transport ----------------

    /** Send an ES10 request: STORE DATA in <=MSS blocks (P1 0x11/0x91, P2=seq),
     *  reassembling the response across 61xx GET RESPONSE. */
    internal fun sendEs10(channel: Int, payload: ByteArray): ByteArray {
        var off = 0
        var seq = 0
        var lastResp = ""
        while (off < payload.size) {
            val end = minOf(off + MSS, payload.size)
            val last = end >= payload.size
            val r = backend.transmit(slot, port, channel, 0x80, 0xE2, if (last) 0x91 else 0x11, seq,
                end - off, Hex.encode(payload.copyOfRange(off, end)))
            if (last) lastResp = r
            else if (!isSuccess(r.takeLast(4))) throw ApduException(statusMessage(r.takeLast(4), "STORE DATA block $seq"))
            off = end; seq = (seq + 1) and 0xFF
        }
        return collect(channel, lastResp)
    }

    /** SGP.22 5.7.2: 9000, or 91xx with a proactive command waiting. Android gives status words in lower case.
     *  Not any 9xxx: a 9300 ("toolkit busy") with no data would pass as an empty success. */
    private fun isSuccess(sw: String) = sw == "9000" || sw.startsWith("91")

    /** A card status word that isn't success. Most cards report a full card in the install result
     *  (reason 10, see [verifyInstall]), but some refuse the data block itself with 6A84. */
    private fun statusMessage(sw: String, what: String) = when (sw.uppercase()) {
        "6A84" -> CARD_FULL
        else -> { Log.w(TAG, "$what SW=$sw"); "The card didn't accept the request (status $sw). Try again." }
    }

    private fun collect(channel: Int, first: String): ByteArray {
        var resp = first
        val out = ByteArrayOutputStream()
        while (true) {
            if (resp.length < 4) throw ApduException(UNREADABLE)
            val swAt = resp.length - 4
            if (swAt > 0) out.write(Hex.decode(resp.substring(0, swAt)))
            val sw1 = resp.substring(swAt, swAt + 2).toInt(16)
            val sw2 = resp.substring(swAt + 2).toInt(16)
            when {
                sw1 == 0x61 -> resp = backend.transmit(slot, port, channel, 0x00, 0xC0, 0x00, 0x00, sw2, "")
                isSuccess(resp.substring(swAt)) -> return out.toByteArray()
                else -> throw ApduException(statusMessage(resp.substring(swAt), "APDU"))
            }
        }
    }

    // ---------------- management (ES10c) ----------------

    private fun readEid(channel: Int): String {
        val resp = sendEs10(channel, Hex.decode("BF3E035C015A"))
        val eid = Tlv.parse(resp)?.find(0x5A)?.value ?: throw ApduException(UNREADABLE)
        return Hex.bcd(eid, swap = false)
    }

    fun getProfiles(): List<EuiccProfile> = withIsdR { ch -> readProfiles(ch) }

    private fun readProfiles(ch: Int): List<EuiccProfile> {
        val root = Tlv.parse(sendEs10(ch, PROFILES_REQUEST)) ?: throw ApduException(UNREADABLE)
        // profileInfoListError: not an empty list. A direct child only, as tag 81 means other things further down.
        root.children.firstOrNull { it.tag == 0x81 }?.let { e ->
            throw ApduException("The card couldn't list its eSIMs (code ${e.value.firstOrNull()?.toInt()?.and(0xFF)}). Try again.")
        }
        return root.findAll(0xE3).map { e3 ->
            EuiccProfile(
                iccid = e3.find(0x5A)?.value?.let { iccid(it) } ?: "",
                isdpAid = e3.find(0x4F)?.value?.let { Hex.encode(it) } ?: "",
                enabled = (e3.find(0x9F70)?.value?.firstOrNull()?.toInt() ?: 0) == 1,
                nickname = e3.find(0x90)?.value?.let { utf8(it) },
                providerName = e3.find(0x91)?.value?.let { utf8(it) },
                profileName = e3.find(0x92)?.value?.let { utf8(it) },
                profileClass = e3.find(0x95)?.value?.firstOrNull()?.toInt() ?: -1,
            )
        }
    }

    /** GetEUICCInfo2: svn [2] and euiccFirmwareVer [3] (3-byte versions), extCardResource [4] with TLVs
     *  81 (installed applications), 82 (free non-volatile memory) and 83 (free volatile memory) in bytes,
     *  and euiccCiPKIdListForVerification [9]. */
    fun cardInfo(): CardInfo = withIsdR { ch ->
        val info2 = Tlv.parse(sendEs10(ch, Hex.decode("BF2200"))) ?: throw ApduException("The card returned no card info.")
        fun field(tag: Int) = info2.children.firstOrNull { it.tag == tag }
        fun version(tag: Int) = field(tag)?.value?.takeIf { it.size == 3 }?.joinToString(".") { (it.toInt() and 0xFF).toString() }
        val resources = field(0x84)?.value?.let { Tlv.parseList(it) }.orEmpty()
        fun number(tag: Int) = resources.firstOrNull { it.tag == tag }?.value
            ?.takeIf { it.size in 1..8 }?.fold(0L) { n, b -> (n shl 8) or (b.toLong() and 0xFF) }
        CardInfo(freeBytes = number(0x82), installedApps = number(0x81)?.toInt(), standard = version(0x82),
            firmware = version(0x83), roots = field(0xA9)?.children.orEmpty().map { Hex.encode(it.value) })
    }

    /** The list on screen can be old: another card put in, or changes made in another app. A card numbers its
     *  ISD-P AIDs by itself, so the same AID can be a different eSIM there; an ICCID can't. Asked on the channel the
     *  command then goes out on, which a swapped card would have ended. An eSIM without an ICCID goes by its AID. */
    private fun requireOnCard(ch: Int, p: EuiccProfile) {
        if (readProfiles(ch).none { it.isdpAid == p.isdpAid && it.iccid == p.iccid }) throw ApduException(GONE)
    }

    fun enable(p: EuiccProfile) = setState(p, enable = true)

    /** Refused before the card is touched where turning the eSIM off would put the card out of reach: the cards
     *  Android 7-9 work with have one eSIM on at a time (several at once needs Android 13), so it's the last one.
     *  Deleting needs no such check, as the card won't delete an eSIM that's on. */
    fun disable(p: EuiccProfile) {
        if (reachedOnlyWhileOn()) throw ApduException(LAST_ESIM_ON)
        setState(p, enable = false)
    }

    private fun setState(p: EuiccProfile, enable: Boolean) = withIsdR { ch ->
        requireOnCard(ch, p) // outside the try below: nothing has been sent yet, so this failure always counts
        val choice = Tlv.build(0xA0, Tlv.build(0x4F, Hex.decode(p.isdpAid)))
        val req = Tlv.buildSeq(if (enable) 0xBF31 else 0xBF32, choice, REFRESH)
        // Both make the card REFRESH, which can drop the reply; the caller
        // re-reads the profile list to confirm.
        val resp = try { sendEs10(ch, req) } catch (_: ApduException) { return@withIsdR }
        checkResult(resp, if (enable) Op.ENABLE else Op.DISABLE)
    }

    fun delete(p: EuiccProfile) = withIsdR { ch ->
        requireOnCard(ch, p)
        checkResult(sendEs10(ch, Tlv.build(0xBF33, Tlv.build(0x4F, Hex.decode(p.isdpAid)))), Op.DELETE)
    }

    /** ES10c SetNickname: the name is stored on the card, so other phones and apps show it too.
     *  An empty [nickname] clears it, and the provider's own name shows again. */
    fun setNickname(iccid: String, nickname: String) {
        val name = nickname.trim().toByteArray(Charsets.UTF_8)
        if (name.size > MAX_NICKNAME_BYTES) throw ApduException("That name is too long. Use a shorter one.")
        withIsdR { ch ->
            val req = Tlv.buildSeq(0xBF29, Tlv.build(0x5A, iccidBytes(iccid)), Tlv.build(0x90, name))
            checkResult(sendEs10(ch, req), Op.RENAME)
        }
    }

    /** ES10c result codes (SGP.22 5.7.16-18) as user-facing messages. */
    private fun checkResult(resp: ByteArray, op: Op) {
        val code = Tlv.parse(resp)?.find(0x80)?.value?.firstOrNull()?.toInt() ?: return
        if (code == 0) return
        throw ApduException(when (code) {
            1 -> GONE
            2 -> when (op) {
                Op.ENABLE -> "That eSIM is already on."
                Op.DISABLE -> "That eSIM is already off."
                Op.DELETE -> "Turn the eSIM off before deleting it."
                Op.RENAME -> "The card refused to rename the eSIM (code 2)."
            }
            3 -> "This eSIM's own rules don't allow this."
            5 -> "The card is busy. Try again in a moment."
            else -> "The card refused to ${op.verb} the eSIM (code $code)."
        })
    }

    // ---------------- provider notices (ES10b) ----------------

    /** Card side of telling providers about installs, switches and deletes, oldest first, ready to send.
     *  A deliberate bound on switches: a provider whose server has shut down never takes its notices, and each
     *  switch adds to the card's list, so for each ICCID only the newest switch notice is kept and older ones are
     *  cleared unsent, since only the latest state matters. A card may leave the ICCID out: then nothing ties a notice
     *  to one eSIM, so the newest of each kind for each provider is kept, which still holds the last turn on and turn
     *  off between two of its eSIMs. Install and delete notices are all kept. */
    fun takeNotifications(): List<Notification> = withIsdR { ch ->
        val notices = listNotifications(ch).sortedBy { it.seq }
        val switches = notices.filter { it.event == Event.ENABLE || it.event == Event.DISABLE }
        // The last of each wins.
        val stale = switches - switches.associateBy { n -> n.iccid?.takeIf { it.isNotEmpty() } ?: (n.address to n.event) }.values
        stale.forEach { removeNotification(ch, it.seq) }
        (notices - stale).mapNotNull { n -> retrieveNotification(ch, n.seq)?.let { n.copy(signed = b64(it)) } }
    }

    /** Clear notices from the card once their providers have them. */
    fun removeNotifications(seqs: Collection<Int>) = withIsdR { ch -> seqs.forEach { removeNotification(ch, it) } }

    /** Send [n] to its provider. Doesn't use the card. True when it needn't be sent again. */
    fun deliver(n: Notification): Boolean = n.signed == null ||
        Es9Client(n.address, connectTimeoutMs = NOTICE_CONNECT_TIMEOUT_MS, transport = transport).handleNotification(n.signed)

    /** ListNotification: BF28 { A0 { BF2F { 80 seq, 81 event bits, 0C address, 5A ICCID }… } }. */
    private fun listNotifications(ch: Int): List<Notification> {
        val list = Tlv.parse(sendEs10(ch, Hex.decode("BF2800")))?.children?.firstOrNull { it.tag == 0xA0 }
        return list?.children.orEmpty().filter { it.tag == 0xBF2F }.mapNotNull { m ->
            val bits = m.find(0x81)?.value?.takeIf { it.size >= 2 } ?: return@mapNotNull null
            Notification(
                seq = m.find(0x80)?.value?.let { BigInteger(it).toInt() } ?: return@mapNotNull null,
                event = Event.entries.firstOrNull { bits[1].toInt() and (0x80 ushr it.ordinal) != 0 } ?: return@mapNotNull null,
                address = m.find(0x0C)?.value?.let { utf8(it) } ?: return@mapNotNull null,
                iccid = m.find(0x5A)?.value?.let { iccid(it) },
            )
        }
    }

    /** RetrieveNotificationsList by sequence number: the signed notice exactly as the card stored it
     *  (an install result BF37, or a signed notice 30). Null when the card has none by that number. */
    private fun retrieveNotification(ch: Int, seq: Int): ByteArray? {
        val resp = sendEs10(ch, Tlv.build(0xBF2B, Tlv.build(0xA0, Tlv.build(0x80, BigInteger.valueOf(seq.toLong()).toByteArray()))))
        val list = Tlv.parse(resp)?.children?.firstOrNull { it.tag == 0xA0 } ?: return null
        return RawTlv.read(list.value, 0, list.value.size).firstOrNull()?.let { list.value.copyOfRange(it.start, it.end) }
    }

    /** RemoveNotificationFromList. A refusal leaves the notice for the next run. */
    private fun removeNotification(ch: Int, seq: Int) {
        sendEs10(ch, Tlv.build(0xBF30, Tlv.build(0x80, BigInteger.valueOf(seq.toLong()).toByteArray())))
    }

    // ---------------- eSIMs sent to this card (ES10a + ES11) ----------------

    /** GetEuiccConfiguredAddresses: the card's default SM-DP+ (most have none) and its root SM-DS. */
    fun configuredAddresses(): Pair<String?, String?> = withIsdR { ch ->
        val r = Tlv.parse(sendEs10(ch, Hex.decode("BF3C00")))
        fun address(tag: Int) = r?.find(tag)?.value?.let { utf8(it).trim() }?.takeIf { '.' in it }
        address(0x80) to address(0x81)
    }

    /** Event-based discovery: the card authenticates to the SM-DS, which answers with the eSIMs waiting for
     *  its EID. Over the ES9+ paths, as lpac does, which the SM-DSs in use accept. Only reads the card, so
     *  [cancel] can stop it at any server request: the card is left as it was. */
    fun waitingEsims(smds: String, cancel: Es9Client.Cancel? = null): List<WaitingEsim> = withIsdR { ch ->
        val info1 = sendEs10(ch, Hex.decode("BF2000"))
        val challenge = Tlv.parse(sendEs10(ch, Hex.decode("BF2E00")))?.find(0x80)?.value
            ?: throw ApduException(NO_CHALLENGE)
        val ds = Es9Client(smds, connectTimeoutMs = SMDS_CONNECT_TIMEOUT_MS, cancel = cancel, transport = transport)
        val init = ds.initiateAuthentication(b64(challenge), b64(info1))
        // The server signs the address it answers for (serverSigned1 serverAddress [3]): the one asked (SGP.22 3.1.2).
        val signedFor = Tlv.parse(unb64(init.serverSigned1))?.find(0x83)?.value?.let { utf8(it) }
        if (signedFor != null && !signedFor.equals(smds, ignoreCase = true))
            throw ApduException("The eSIM discovery server $smds answered as $signedFor, so its answer wasn't used.")
        val auth = authenticateServer(ch, init, "")
        ds.discoveryEvents(init.transactionId, b64(auth)).map { (id, smdp) -> WaitingEsim(smdp, id) }
    }

    // ---------------- download (ES10b + ES9+) ----------------

    /** Full SGP.22 download. [progress] gets each [Step] and, for [Step.INSTALL], a 0..1 fraction.
     *  Stops before downloading when the provider's eSIM has one of the [installed] ICCIDs. */
    fun downloadProfile(ac: ActivationCode, confirmationCode: String?, installed: Set<String> = emptySet(),
                        progress: (Step, Float) -> Unit): DownloadOutcome {
        // Fail before contacting the server: many activation codes work only once.
        if (ac.ccRequired && confirmationCode.isNullOrEmpty())
            throw ConfirmationCodeNeededException()
        var code = ac
        // A hand-over starts the steps again at another server; the progress shown never goes back.
        var reached = -1
        val forward: (Step, Float) -> Unit = { step, f ->
            if (step.ordinal >= reached) { reached = step.ordinal; progress(step, f) }
        }
        repeat(MAX_HANDOVERS) {
            try { return downloadFrom(code, confirmationCode, installed, forward) }
            catch (handover: Es9Client.Handover) {
                // An eSIM sent to this card can wait at a second discovery server, which names the provider's
                // server and the event to ask it for (SGP.22 3.6.2): download again from there.
                val (eventId, server) = handover.events.firstOrNull()
                    ?: throw ApduException("This eSIM isn't waiting for this card any more.")
                code = ActivationCode(server, eventId)
            }
        }
        throw ApduException("The eSIM's servers kept passing the download on. Try again later.")
    }

    private fun downloadFrom(ac: ActivationCode, confirmationCode: String?, installed: Set<String>,
                             progress: (Step, Float) -> Unit): DownloadOutcome {
        return withIsdR { ch ->
            progress(Step.READ_CARD, 0f)
            if (readEid(ch) != eid) throw ApduException(OTHER_CARD) // before the provider: the code stays unused
            val info1 = sendEs10(ch, Hex.decode("BF2000"))
            val challenge = Tlv.parse(sendEs10(ch, Hex.decode("BF2E00")))?.find(0x80)?.value
                ?: throw ApduException(NO_CHALLENGE)

            progress(Step.CONNECT, 0f)
            val es9 = Es9Client(ac.smdp, transport = transport)
            val init = es9.initiateAuthentication(b64(challenge), b64(info1))

            progress(Step.VERIFY_SERVER, 0f)
            val authServerResp = authenticateServer(ch, init, ac.matchingId)

            progress(Step.VERIFY_CARD, 0f)
            val authClient = es9.authenticateClient(init.transactionId, b64(authServerResp))
            val result = try {
                // The provider names the eSIM's ICCID here, before anything is downloaded.
                parseMetadata(authClient.profileMetadata).iccid?.takeIf { it in installed }?.let { throw AlreadyOnCardException(it) }

                progress(Step.PREPARE, 0f)
                val prep = prepareDownload(ch, authClient, confirmationCode)

                progress(Step.DOWNLOAD, 0f)
                val bpp = unb64(es9.getBoundProfilePackage(init.transactionId, b64(prep)))

                progress(Step.INSTALL, 0f)
                loadBpp(ch, bpp) { progress(Step.INSTALL, it) }
            } catch (e: Exception) {
                // A server refusal already ended the procedure, and without a network the notice can't go.
                if (e !is Es9Client.Es9Exception && e !is IOException) cancelSession(ch, es9, init.transactionId)
                throw e
            }

            // Report the outcome to the SM-DP+, then clear the card's copy of it. If it couldn't be
            // sent, the copy stays and is sent later with the other notices.
            if (es9.handleNotification(b64(result))) Tlv.parse(result)?.find(0xBF2F)?.find(0x80)?.value?.let { seq ->
                runCatching { removeNotification(ch, BigInteger(seq).toInt()) }
            }
            verifyInstall(result)
            parseMetadata(authClient.profileMetadata)
        }
    }

    /** Best effort; never replaces the failure being reported. No 5F37 means the card refused: its session is
     *  over, perhaps because the install finished, and a notice covers that. */
    private fun cancelSession(ch: Int, es9: Es9Client, transactionId: String) {
        runCatching {
            val signed = sendEs10(ch, Tlv.buildSeq(0xBF41, Tlv.build(0x80, Hex.decode(transactionId)),
                Tlv.build(0x81, byteArrayOf(CANCEL_KEEP_ORDER))))
            if (Tlv.parse(signed)?.find(0x5F37) != null) es9.cancelSession(transactionId, b64(signed))
        }
    }

    /** AuthenticateServerResponse's error alternative, [1]: the card didn't accept the server. A direct child only:
     *  an accepted one echoes ctxParams1, whose deviceInfo is an A1 too. */
    internal fun cardRefusedServer(resp: ByteArray) =
        Tlv.parse(resp)?.takeIf { it.tag == 0xBF38 }?.children?.firstOrNull()?.tag == 0xA1

    private fun authenticateServer(ch: Int, init: Es9Client.InitiateResult, matchingId: String): ByteArray {
        val tac = Hex.decode("35290611")
        val deviceInfo = Tlv.build(0xA1, Tlv.build(0x80, tac) + Tlv.build(0xA1, ByteArray(0)))
        val mid = if (matchingId.isNotEmpty()) Tlv.build(0x80, matchingId.toByteArray(Charsets.UTF_8)) else ByteArray(0)
        val ctxParams1 = Tlv.build(0xA0, mid + deviceInfo)
        val value = unb64(init.serverSigned1) + unb64(init.serverSignature1) +
                unb64(init.euiccCiPKIdToBeUsed) + unb64(init.serverCertificate) + ctxParams1
        val resp = sendEs10(ch, Tlv.build(0xBF38, value))
        // The procedure stops here (SGP.22 3.0.1): a server the card turned down doesn't get to steer the rest.
        if (cardRefusedServer(resp))
            throw Es9Client.Es9Exception("Your card didn't accept this provider's certificate, so nothing was installed.")
        return resp
    }

    private fun prepareDownload(ch: Int, ac: Es9Client.AuthClientResult, confirmationCode: String?): ByteArray {
        val s2 = unb64(ac.smdpSigned2)
        val s2node = Tlv.parse(s2)
        val ccRequired = (s2node?.find(0x01)?.value?.firstOrNull()?.toInt() ?: 0) != 0
        var value = s2 + unb64(ac.smdpSignature2)
        if (ccRequired) {
            if (confirmationCode.isNullOrEmpty()) throw ConfirmationCodeNeededException()
            val txId = s2node?.find(0x80)?.value ?: throw unreadableEsim("no transactionId in smdpSigned2")
            val sha = MessageDigest.getInstance("SHA-256")
            val h1 = sha.digest(confirmationCode.toByteArray(Charsets.UTF_8))
            sha.update(h1); sha.update(txId)
            value += Tlv.build(0x04, sha.digest())
        }
        value += unb64(ac.smdpCertificate)
        return sendEs10(ch, Tlv.build(0xBF21, value))
    }

    /** Segment the BoundProfilePackage per SGP.22 / lpac and stream it to the card. */
    internal fun loadBpp(ch: Int, bpp: ByteArray, progress: (Float) -> Unit = {}): ByteArray {
        val bf36 = RawTlv.find(bpp, 0, bpp.size, 0xBF36) ?: throw unreadableEsim("no BoundProfilePackage (BF36)")
        val kids = RawTlv.read(bpp, bf36.valueStart, bf36.end)
        fun part(tag: Int) = kids.firstOrNull { it.tag == tag } ?: throw unreadableEsim("BPP is missing ${tag.toString(16)}")

        val segs = ArrayList<Pair<Int, Int>>()
        fun whole(t: RawTlv) { segs += t.start to t.end }
        fun split(t: RawTlv) { // header first, then each child on its own
            segs += t.start to t.valueStart
            RawTlv.read(bpp, t.valueStart, t.end).forEach(::whole)
        }
        segs += bf36.start to part(0xBF23).end           // BF36 header + initialiseSecureChannel
        whole(part(0xA0))                                 // configureISDP
        split(part(0xA1))                                 // storeMetadata
        kids.firstOrNull { it.tag == 0xA2 }?.let(::whole) // replaceSessionKeys (optional)
        split(part(0xA3))                                 // profile elements

        segs.forEachIndexed { i, (s, e) ->
            val resp = sendEs10(ch, bpp.copyOfRange(s, e))
            if (resp.isNotEmpty()) return resp // ProfileInstallationResult ends the flow
            progress((i + 1f) / segs.size)
        }
        throw ApduException(INSTALL_UNKNOWN)
    }

    /** ProfileInstallationResult -> success, or a user-facing reason (SGP.22 ErrorReason). */
    internal fun verifyInstall(result: ByteArray) {
        val root = Tlv.parse(result) ?: throw ApduException(INSTALL_UNKNOWN)
        val node = (root.find(0xBF27) ?: root).find(0xA2)?.children?.firstOrNull()
        if (node == null) { Log.w(TAG, "install result unparsed: ${result.size} bytes"); return } // it can hold the ICCID
        if (node.tag == 0xA0) return // successResult
        val reason = node.find(0x81)?.value?.firstOrNull()?.toInt()
        throw ApduException(when (reason) {
            9 -> "This eSIM is already on the card."
            10 -> CARD_FULL
            11 -> "The install was interrupted. Try again."
            else -> "The card couldn't install this eSIM (error ${reason ?: "unknown"}). The activation code often " +
                "still works after a failed install, so try again. If it fails again, ask your provider for help and " +
                "quote that number."
        })
    }

    /** The provider's data is missing [what]: for the log. On screen, what can be done about it. */
    private fun unreadableEsim(what: String): ApduException {
        Log.w(TAG, what)
        return ApduException("The provider sent an eSIM this app can't read. Ask your provider for help.")
    }

    private fun parseMetadata(b64Meta: String?): DownloadOutcome {
        val meta = b64Meta?.let { runCatching { Tlv.parse(unb64(it)) }.getOrNull() }
        val name = (meta?.find(0x92) ?: meta?.find(0x91))?.value?.let { utf8(it) }
        return DownloadOutcome(name ?: "eSIM", meta?.find(0x5A)?.value?.let { iccid(it) })
    }

    private fun utf8(b: ByteArray) = String(b, Charsets.UTF_8)
    private fun iccid(b: ByteArray) = Hex.bcd(b, swap = true).trimEnd('F')
    /** Inverse of [iccid]: 10 bytes, nibble-swapped, padded with the F filler. */
    private fun iccidBytes(iccid: String): ByteArray {
        val digits = iccid.padEnd(20, 'F')
        return Hex.decode(digits.chunked(2).joinToString("") { it.reversed() })
    }
}
