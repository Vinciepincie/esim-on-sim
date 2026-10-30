package com.esimonsim.apdu

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
}

class SlotInfo(val euicc: Boolean, val removable: Boolean)

class OpenResult(val channel: Int, val status: Int, val selectResponse: ByteArray?)

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
