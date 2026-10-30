package com.esimonsim.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.esimonsim.apdu.AccessMethod
import com.esimonsim.lpa.ActivationCode
import com.esimonsim.lpa.Hex
import org.json.JSONObject
import java.security.MessageDigest

/** What only this phone knows about the card's eSIMs: notes, and which activation codes were installed.
 *  Names live on the card itself (SetNickname); this is app-private and never backed up. */
class ProfileStore internal constructor(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("profiles", Context.MODE_PRIVATE))

    /** An activation code this app installed before. [iccid] is null when the provider didn't report it. */
    data class Install(val iccid: String?, val name: String, val at: Long)

    fun notes(): Map<String, String> = prefs.all.mapNotNull { (key, value) ->
        if (key.startsWith(NOTE) && value is String) key.removePrefix(NOTE) to value else null
    }.toMap()

    fun setNote(iccid: String, text: String) {
        val note = text.trim()
        prefs.edit { if (note.isEmpty()) remove(NOTE + iccid) else putString(NOTE + iccid, note) }
    }

    /** eSIMs hidden from the list, by ICCID. Only this app hides them: the card and other apps still list them. */
    fun hidden(): Set<String> = prefs.getStringSet(HIDDEN, null)?.toSet() ?: emptySet()

    fun setHidden(iccid: String, hide: Boolean) =
        prefs.edit { putStringSet(HIDDEN, if (hide) hidden() + iccid else hidden() - iccid) }

    /** What the phone does after a switch, as last chosen: checked items are changed, others left alone. */
    data class SwitchSetup(val mobileData: Boolean = true, val roaming: Boolean = true) : java.io.Serializable

    fun switchSetup() = SwitchSetup(prefs.getBoolean(SWITCH_DATA, true), prefs.getBoolean(SWITCH_ROAMING, true))

    fun setSwitchSetup(s: SwitchSetup) = prefs.edit { putBoolean(SWITCH_DATA, s.mobileData); putBoolean(SWITCH_ROAMING, s.roaming) }

    /** The slot the card was last found in, by each way of reaching it: they number slots differently. */
    fun lastSlot(how: AccessMethod?) = prefs.getInt(LAST_SLOT + how, -1)

    fun setLastSlot(how: AccessMethod?, slot: Int) = prefs.edit { putInt(LAST_SLOT + how, slot) }

    /** People stopped card access, and haven't connected since. */
    fun stopped() = prefs.getBoolean(STOPPED, false)

    fun setStopped(stopped: Boolean) = prefs.edit { putBoolean(STOPPED, stopped) }

    /** EIDs people said are their removable card, though the phone reports its slot as built in. */
    fun ownCards(): Set<String> = prefs.getStringSet(OWN_CARDS, null)?.toSet() ?: emptySet()

    fun addOwnCard(eid: String) = prefs.edit { putStringSet(OWN_CARDS, ownCards() + eid) }

    /** Same-name warnings the user dismissed, each as its eSIMs' sorted ICCIDs. */
    fun dismissedSameName(): Set<String> = prefs.getStringSet(SAME_NAME, null)?.toSet() ?: emptySet()

    fun dismissSameName(key: String) = prefs.edit { putStringSet(SAME_NAME, dismissedSameName() + key) }

    /** The advice to turn on USB debugging before travelling was dismissed; it doesn't come back. */
    fun usbTipDismissed() = prefs.getBoolean(USB_TIP, false)

    fun dismissUsbTip() = prefs.edit { putBoolean(USB_TIP, true) }

    /** Codes without a matching ID (orders bound to the card's EID) look the same for every eSIM from
     *  that provider, so they are never matched. */
    fun installOf(ac: ActivationCode): Install? = if (ac.matchingId.isEmpty()) null else
        prefs.getString(INSTALL + fingerprint(ac), null)?.let {
        runCatching {
            val o = JSONObject(it)
            Install(o.optString("iccid").ifEmpty { null }, o.getString("name"), o.getLong("at"))
        }.getOrNull()
    }

    fun recordInstall(ac: ActivationCode, iccid: String?, name: String, at: Long = System.currentTimeMillis()) {
        if (ac.matchingId.isEmpty()) return
        val o = JSONObject().put("iccid", iccid.orEmpty()).put("name", name).put("at", at)
        prefs.edit { putString(INSTALL + fingerprint(ac), o.toString()) }
    }

    companion object {
        private const val NOTE = "note:"
        private const val INSTALL = "install:"
        private const val SAME_NAME = "same-name-dismissed"
        private const val USB_TIP = "usb-tip-dismissed"
        private const val HIDDEN = "hidden"
        private const val SWITCH_DATA = "switch-mobile-data"
        private const val SWITCH_ROAMING = "switch-roaming"
        private const val OWN_CARDS = "own-cards"
        private const val STOPPED = "card-access-stopped"
        private const val LAST_SLOT = "last-slot:"

        /** Activation codes are stored only as a hash: enough to recognise one, useless to anyone reading it. */
        internal fun fingerprint(ac: ActivationCode): String {
            val key = ac.smdp.lowercase() + "$" + ac.matchingId.uppercase()
            return Hex.encode(MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)))
        }
    }
}
