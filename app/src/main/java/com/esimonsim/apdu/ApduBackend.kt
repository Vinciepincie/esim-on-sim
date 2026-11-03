package com.esimonsim.apdu

import org.json.JSONArray

/** Low-level APDU access to a UICC slot. Implemented over Android's OMAPI, or a helper started
 *  through su, Shizuku or the built-in ADB client. */
interface ApduBackend {
    fun isReady(): Boolean
    /** Open a logical channel to [aidHex] on the given slot/port. */
    fun openChannel(slot: Int, port: Int, aidHex: String): OpenResult
    /** Transmit one APDU on an open logical channel; returns response hex incl. SW. */
    fun transmit(slot: Int, port: Int, channel: Int, cla: Int, ins: Int, p1: Int, p2: Int, p3: Int, dataHex: String): String
    fun closeChannel(slot: Int, port: Int, channel: Int)
    /** Physical slots in order: whether each holds an eUICC, and whether Android counts it removable.
     *  Empty when unknown. */
    fun slots(): List<SlotInfo> = emptyList()
    /** Whether a card is in [slot], as Android says without touching it. Null when it doesn't say. */
    fun present(slot: Int): Boolean? = slots().getOrNull(slot)?.present
}

/** A physical slot as Android sees it, without touching the card. [removable] is set per tray by the phone's maker, so it
 *  says nothing about the card in it (a POCO calls a tray fixed). Null fields: Android didn't say, which isn't a no.
 *  [active]: the phone is using the slot; opening one it isn't using throws. [logical]: Android's SIM number for it.
 *  [eid]: Android knows the card's. [token]: changes with what Android sees in the slot, so an unchanged one saves a look;
 *  it never proves the same card, and isn't an ICCID. */
class SlotInfo(val euicc: Boolean, val removable: Boolean, val present: Boolean? = null, val active: Boolean? = null,
               val logical: Int? = null, val eid: String? = null, val token: String? = null) {
    internal companion object {
        /** From the helper's list, whose slots can be empty objects. */
        fun list(all: JSONArray) = List(all.length()) { i -> all.getJSONObject(i).let { o ->
            SlotInfo(o.optBoolean("euicc"), o.optBoolean("removable", true),
                if (o.has("present")) o.getBoolean("present") else null,
                if (o.has("active")) o.getBoolean("active") else null,
                if (o.has("logical")) o.getInt("logical") else null,
                o.optString("eid").ifEmpty { null }, o.optString("token").ifEmpty { null })
        } }
    }
}

class OpenResult(val channel: Int, val status: Int, val selectResponse: ByteArray?) {
    companion object {
        /** Direct card access only: refused, and the card's ATR says it's an eSIM card. The helper never sends it:
         *  telephony's statuses go up to 4. */
        const val LOCKED = 100
    }
}

/** [cardThere]: a card is in that slot, and this is what went wrong with it, which says more than "not found". */
class ApduException(message: String, cause: Throwable? = null, val cardThere: Boolean = false) : Exception(message, cause) {
    internal companion object {
        /** The helper's "ClassName: message" as something to show people; only the log gets the original. */
        fun fromHelper(raw: String): ApduException {
            android.util.Log.w("CardAccess", "Card request failed: ${raw.take(200)}")
            return ApduException(when {
                raw.startsWith("SecurityException") -> "Your phone refused this app's request to the card. Restart your phone, then try again."
                "no known way to reach the card" in raw -> "This version of Android has no way for this app to reach the card."
                else -> "The card didn't answer. Check that it's in the phone, then try again."
            })
        }
    }
}

/** This phone no longer trusts the app's ADB key, so it has to pair again. */
class PairingRequiredException(cause: Throwable? = null) :
    java.io.IOException("This phone no longer recognises the app's pairing. Pair again to continue.", cause)
