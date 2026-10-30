package com.esimonsim.apdu

import android.util.Log
import com.esimonsim.lpa.Hex
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Card access through the helper (adb/CardShell) running in a process whose input and output only this app holds:
 *  the helper su starts ([RootApduBackend]) and the one Shizuku starts ([ShizukuApduBackend]). It ends when its input
 *  does, so with this app, and nothing keeps running. [uids]: who the helper may run as. */
abstract class PipeApduBackend internal constructor(private val uids: Set<Int>) : ApduBackend, AutoCloseable {
    @Volatile private var pipe: Pipe? = null

    override fun isReady(): Boolean = pipe?.isOpen == true

    /** Takes over the helper in [process], which gets [seconds] to answer. Throws when the right helper doesn't, and
     *  leaves nothing running. */
    protected fun attach(process: Process, seconds: Long) {
        val started = Pipe(process)
        try {
            val hello = started.call(JSONObject().put("op", "ping"), seconds)
            if (hello.optInt("uid", -1) !in uids) throw IOException("Unexpected card helper")
        } catch (e: Exception) {
            started.close()
            // What was said on the way, e.g. su's "Permission denied": no card data passes through there.
            Log.i(TAG, "Card helper didn't start: ${e.message}; ${started.lastError}")
            throw e
        }
        pipe = started
    }

    /** How long one request may take: as over Wireless debugging, 45 s for changes, 15 s inside [reading]. */
    private var timeoutSeconds = 45L

    fun <T> reading(block: () -> T): T {
        val before = timeoutSeconds
        timeoutSeconds = 15
        try { return block() } finally { timeoutSeconds = before }
    }

    @Synchronized
    private fun rpc(request: JSONObject): JSONObject {
        val open = pipe ?: throw ApduException("Card access disconnected. Reconnect first.")
        val response = try { open.call(request, timeoutSeconds) } catch (e: Exception) {
            disconnect()
            // Never retry an APDU automatically: it may already have changed the card.
            throw ApduException("Card connection lost or timed out. Reconnect and refresh before trying again.", e)
        }
        if (!response.getBoolean("ok")) throw AdbApduBackend.helperError(response.optString("error"))
        return response
    }

    /** As [AdbApduBackend.useSim]. */
    fun useSim(slot: Int, iccid: String, data: Boolean, roaming: Boolean): SimSettingsResult =
        SimSettingsResult.of(rpc(SimSettingsResult.request(slot, iccid, data, roaming)))

    override fun slots(): List<SlotInfo> = rpc(JSONObject().put("op", "slots")).getJSONArray("slots").let { all ->
        List(all.length()) { i -> all.getJSONObject(i).let { SlotInfo(it.optBoolean("euicc"), it.optBoolean("removable", true)) } }
    }

    private fun request(op: String, slot: Int, port: Int, channel: Int = 0) = JSONObject()
        .put("op", op).put("slot", slot).put("port", port).put("channel", channel)

    override fun openChannel(slot: Int, port: Int, aidHex: String): OpenResult {
        val response = rpc(request("open", slot, port).put("aid", aidHex))
        return OpenResult(response.getInt("channel"), response.getInt("status"),
            response.getString("select").takeIf { it.isNotEmpty() }?.let(Hex::decode))
    }

    override fun transmit(slot: Int, port: Int, channel: Int, cla: Int, ins: Int, p1: Int, p2: Int, p3: Int, dataHex: String): String =
        rpc(request("transmit", slot, port, channel).put("cla", cla).put("ins", ins)
            .put("p1", p1).put("p2", p2).put("p3", p3).put("data", dataHex)).getString("response")

    override fun closeChannel(slot: Int, port: Int, channel: Int) {
        if (!isReady()) return
        // A switch restarts the card, which drops its channels: no reason to end card access.
        try { rpc(request("close", slot, port, channel)) } catch (_: Exception) { }
    }

    /** Ends the helper: its input closes, and it exits. */
    fun disconnect() {
        pipe?.close()
        pipe = null
    }

    override fun close() = disconnect()

    /** JSON lines to the helper over its process, answered by "EOS:" lines. A reader thread notices at once when the
     *  process ends (root declined, the helper stopped). [process]'s calls may fail: Shizuku's go to another process. */
    private class Pipe(private val process: Process) {
        private val lines = LinkedBlockingQueue<String>()
        @Volatile var isOpen = true; private set
        /** The last line su or the helper wrote to its error output, for the log. Never card data. */
        @Volatile var lastError: String? = null; private set

        init {
            daemon("card-helper-out") {
                try { process.inputStream.bufferedReader().use { r -> while (true) lines.put(r.readLine() ?: break) } }
                catch (_: Exception) { }
                isOpen = false
                lines.put(GONE)
            }
            daemon("card-helper-err") { drainErrors() }
        }

        private fun drainErrors() = try {
            process.errorStream.bufferedReader().use { r -> while (true) lastError = (r.readLine() ?: break).take(200) }
        } catch (_: Exception) { }

        fun call(request: JSONObject, timeoutSeconds: Long): JSONObject {
            if (!isOpen) throw IOException("Card helper exited")
            try {
                process.outputStream.apply { write("$request\n".toByteArray(Charsets.UTF_8)); flush() }
                while (true) {
                    // The grace is as in HelperLink.call: a process Android froze wakes before its reader does.
                    val line = lines.poll(timeoutSeconds, TimeUnit.SECONDS) ?: lines.poll(HelperLink.GRACE_SECONDS, TimeUnit.SECONDS)
                        ?: throw IOException("Card helper timed out")
                    if (line === GONE) throw IOException("Card helper exited")
                    if (line.startsWith("EOS:")) return JSONObject(line.substring(4))
                }
            } catch (e: Exception) {
                // A late answer would be taken for the next request.
                close()
                throw e as? IOException ?: IOException(e)
            }
        }

        fun close() {
            isOpen = false
            runCatching { process.outputStream.close() }
            runCatching { process.destroy() }
        }

        private fun daemon(name: String, body: () -> Unit) = Thread(body, name).apply { isDaemon = true }.start()
    }

    private companion object {
        const val TAG = "CardAccess"
        val GONE = String(charArrayOf('\u0000'))
    }
}
