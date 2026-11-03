package com.esimonsim.apdu

import android.content.Context
import com.esimonsim.adb.CardShell
import com.esimonsim.adb.FakeCardShell
import com.esimonsim.lpa.Hex
import com.esimonsim.lpa.Lpa
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.NoSuchElementException

/** Debug builds only: SIM readers for direct card access, simulated as the "omapi" entry of
 *  /data/local/tmp/fakecard.json says, so an emulator (which has none) can run it. Re-read on every call, so it can
 *  change mid-test like the rest of that file (see adb/FakeCardShell):
 *  ```
 *  "omapi": [{"slot": 0, "atr": "3B9F96803FC7828031E073FE211B57AA8660F001001EE1"}, {"slot": 1, "refuse": true}]
 *  ```
 *  A reader for each entry, "SIM1" for slot 0. What's in it: the card FakeCardShell has in that slot, which lets this
 *  app in; with "refuse", one whose access rules don't, as most adapter cards' and ordinary SIMs'; with "present": false,
 *  none. "atr": the card's answer to reset in hex; none without it, as some phones give. An empty list is a phone with
 *  readers, but none for SIM cards. Apps may read that folder but not write there, so FakeCardShell keeps its cards'
 *  state for this in [home]: they start as the helper left them, and changes made here stay here. */
internal class FakeOmapi(private val dir: File, home: File) : OmapiApduBackend.Readers {
    private val card = FakeCardShell(dir, home)
    /** By slot, as a session keeps it: a card swapped meanwhile only shows in a fresh read. */
    private val sessionAtrs = HashMap<Int, ByteArray?>()
    @Volatile private var closed = false
    override val isConnected get() = !closed
    override val slots = readers().map { it.optInt("slot") }.sorted()

    private fun readers(): List<JSONObject> =
        config(dir)?.optJSONArray("omapi")?.let { all -> List(all.length()) { all.getJSONObject(it) } }.orEmpty()

    private fun reader(slot: Int) = readers().firstOrNull { it.optInt("slot") == slot }

    override fun openChannel(slot: Int, aid: ByteArray): OmapiApduBackend.SeChannel = synchronized(card) {
        val reader = reader(slot) ?: throw IOException("No reader for slot $slot")
        if (!reader.optBoolean("present", true)) throw OmapiApduBackend.NoCardException()
        // As Android's AccessControlException: checked before anything is opened.
        if (reader.optBoolean("refuse")) throw SecurityException("Access denied by the card's access rules")
        // The helper's list has the usual ID by its start, as the helper selects with that.
        val id = Hex.encode(aid).let { if (it == Lpa.ISD_R) CardShell.ISD_R_AIDS[0] else it }
        if (id !in CardShell.ISD_R_AIDS) throw NoSuchElementException("Applet not found")
        val opened = ask(JSONObject().put("op", "open").put("slot", slot).put("aid", id))
        val channel = opened.getInt("channel")
        if (channel < 0) throw if (opened.optInt("status") == 3) NoSuchElementException("Applet not found")
            else IOException("The card is restarting")
        Channel(channel, opened.optString("select").ifEmpty { null }?.let(Hex::decode))
    }

    override fun present(slot: Int) = reader(slot)?.optBoolean("present", true) == true

    override fun atr(slot: Int, fresh: Boolean): ByteArray? {
        val reader = reader(slot)?.takeIf { it.optBoolean("present", true) } ?: return null
        val now = reader.optString("atr").ifEmpty { null }?.let(Hex::decode)
        return if (fresh) now else synchronized(sessionAtrs) { sessionAtrs.getOrPut(slot) { now } }
    }

    override fun close() {
        closed = true
        synchronized(card) { card.close() }
    }

    private fun ask(request: JSONObject): JSONObject = try { card.handle(request) } catch (e: Exception) {
        throw IOException(e.message, e)
    }

    /** Fields as telephony hands them to the helper. Long answers come back as 61xx, which Android would fetch itself;
     *  Lpa does that here instead. */
    private inner class Channel(private val number: Int, override val selectResponse: ByteArray?) : OmapiApduBackend.SeChannel {
        override fun transmit(command: ByteArray): ByteArray = synchronized(card) {
            fun at(i: Int) = command.getOrNull(i)?.toInt()?.and(0xFF) ?: 0
            Hex.decode(ask(JSONObject().put("op", "transmit").put("channel", number).put("cla", at(0)).put("ins", at(1))
                .put("p1", at(2)).put("p2", at(3)).put("p3", at(4))
                .put("data", Hex.encode(command.copyOfRange(minOf(5, command.size), command.size)))).getString("response"))
        }

        override fun close() { synchronized(card) { ask(JSONObject().put("op", "close").put("channel", number)) } }
    }

    companion object {
        private val TMP = File("/data/local/tmp")

        private fun config(dir: File): JSONObject? = runCatching { JSONObject(File(dir, "fakecard.json").readText()) }.getOrNull()

        /** Readers as fakecard.json says while it has an "omapi" key; null otherwise. */
        fun of(context: Context): FakeOmapi? =
            if (config(TMP)?.has("omapi") == true) FakeOmapi(TMP, context.applicationContext.filesDir) else null
    }
}
