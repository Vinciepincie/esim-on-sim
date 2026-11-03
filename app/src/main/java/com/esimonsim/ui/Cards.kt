package com.esimonsim.ui

import com.esimonsim.apdu.AccessMethod
import com.esimonsim.apdu.ShizukuStatus
import com.esimonsim.lpa.EuiccProfile
import com.esimonsim.lpa.SlotLook

/** A line of the card switcher, for a slot: a card, or with no [eid] one the phone isn't using, which can't be opened.
 *  [sim]: the number the phone goes by, from 0. A slot it isn't using has none, so that one isn't called by a number,
 *  which would be another card's. [on]: the active eSIM's name, if [listed]. [locked]: an eSIM card that refuses direct
 *  access, which another way in opens; in a slot the phone uses, so it has its number. */
data class CardRow(val slot: Int, val sim: Int, val eid: String? = null, val on: String? = null, val listed: Boolean = false,
                   val locked: Boolean = false) {
    val title get() = if (eid == null && !locked) "Card not in use" else "Card in SIM ${sim + 1}"
    val note get() = when {
        locked -> "Doesn't let this app in directly. Change card access"
        eid == null -> "Your phone isn't using its SIM slot. Open SIM settings"
        on != null -> "$on is on"
        listed -> "No eSIM on"
        else -> null
    }
}

internal fun activeName(profiles: List<EuiccProfile>) = profiles.firstOrNull { it.enabled }?.displayName

/** What a look through the slots leaves of [old]. The slot in view it reports as unchanged, so that row stays. */
internal fun rowsAfter(old: List<CardRow>, looks: List<SlotLook>): List<CardRow> {
    val rows = old.associateBy { it.slot }.toMutableMap()
    for (l in looks) {
        val sim = l.logical ?: rows[l.slot]?.sim ?: l.slot
        when (l.what) {
            SlotLook.What.EMPTY -> rows -= l.slot
            SlotLook.What.IDLE -> rows[l.slot] = CardRow(l.slot, sim)
            SlotLook.What.CARD -> rows[l.slot] = CardRow(l.slot, sim, l.card?.eid, l.profiles?.let(::activeName), l.profiles != null)
            SlotLook.What.LOCKED -> rows[l.slot] = CardRow(l.slot, sim, locked = true)
            else -> rows[l.slot]?.let { rows[l.slot] = it.copy(sim = sim) }
        }
    }
    return rows.values.sortedBy { it.sim }
}

/** The tokens a look can count on next time: what it just saw in each slot. A slot it couldn't tell about, or saw with
 *  no token, is looked at again, or a card taken out and put back with the same token would stay missing. */
internal fun seenAfter(seen: Map<Int, String>, looks: List<SlotLook>): Map<Int, String> {
    val next = seen.toMutableMap()
    for (l in looks) when {
        l.what == SlotLook.What.SAME -> {}
        l.token == null || l.what == SlotLook.What.AGAIN || l.what == SlotLook.What.HUNG -> next -= l.slot
        else -> next[l.slot] = l.token
    }
    return next
}

/** [rows] with the card in view going by what's on screen. Once only: one that moved leaves its old slot's row behind. */
internal fun withView(rows: List<CardRow>, slot: Int, eid: String?, profiles: List<EuiccProfile>, sim: Int = -1): List<CardRow> {
    if (eid == null) return rows
    val view = (rows.firstOrNull { it.slot == slot } ?: CardRow(slot, if (sim >= 0) sim else slot))
        .copy(eid = eid, on = activeName(profiles), listed = true)
    return (rows.filterNot { it.slot == slot || it.eid == eid } + view).sortedBy { it.sim }
}

/** Whether a refresh stays on the card in view, at its address: someone chose it from several. Everyone else's refresh
 *  looks for the card as ever, as with one card there's nothing to stay on, and a phone's own chip nobody chose loses
 *  to a card in the tray. */
internal fun refreshStays(viewing: String?, chosen: String?, cards: Int) = viewing != null && viewing == chosen && cards >= 2

/** The ways in the Card access setting offers besides Automatic, in Automatic's order: root where su is there, direct
 *  access where the phone has SIM readers for apps (Android 9 and newer), Shizuku where it's installed, and always ADB,
 *  which is Wireless debugging, or below Android 11 USB debugging. The way chosen or in use stays, so it can be shown. */
internal fun accessWays(sdk: Int, directAvailable: Boolean, rootFound: Boolean, shizuku: ShizukuStatus,
                        method: AccessMethod?, preferred: AccessMethod?): List<AccessMethod> {
    fun mine(w: AccessMethod) = method == w || preferred == w
    return listOfNotNull(
        AccessMethod.ROOT.takeIf { rootFound || mine(it) },
        AccessMethod.DIRECT.takeIf { (sdk >= 28 && directAvailable) || mine(it) },
        AccessMethod.SHIZUKU.takeIf { shizuku != ShizukuStatus.ABSENT || mine(it) },
        AccessMethod.ADB,
    )
}

/** A way in by its name in the Card access setting; null is the automatic order. */
internal fun wayLabel(m: AccessMethod?, sdk: Int) = when (m) {
    null -> "Automatic"
    AccessMethod.ROOT -> "Root"
    AccessMethod.DIRECT -> "Direct"
    AccessMethod.SHIZUKU -> "Shizuku"
    AccessMethod.ADB -> if (sdk >= 30) "Wireless debugging" else "USB debugging"
}

/** A way in as a sentence names it: "Now using direct access." [background]: ADB, through the background helper. */
internal fun wayName(m: AccessMethod, sdk: Int, background: Boolean) = when (m) {
    AccessMethod.ROOT -> "root"
    AccessMethod.DIRECT -> "direct access"
    AccessMethod.SHIZUKU -> "Shizuku"
    AccessMethod.ADB -> if (background) "the background helper" else if (sdk >= 30) "Wireless debugging" else "USB debugging"
}

