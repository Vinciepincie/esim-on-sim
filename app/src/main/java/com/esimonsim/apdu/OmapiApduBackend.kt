package com.esimonsim.apdu

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.se.omapi.Channel
import android.se.omapi.SEService
import android.se.omapi.Session
import android.util.Log
import androidx.annotation.RequiresApi
import com.esimonsim.lpa.Hex
import com.esimonsim.adb.CardShell
import com.esimonsim.lpa.Lpa
import java.io.IOException
import java.util.NoSuchElementException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Card access through Android's own secure element API (OMAPI), which needs no setup at all. The card's
 *  access rules decide who may use it: many adapter cards only let their maker's app in, but some let any
 *  app in. Slot numbers are OMAPI's SIM readers, from 0: "SIM1" is slot 0. Phones have no ports here. */
class OmapiApduBackend internal constructor(private val open: () -> Readers) : ApduBackend, AutoCloseable {
    // android.se.omapi is Android 9+. Older phones' own SIM readers (SIMalliance) aren't used.
    constructor(context: Context) : this({
        if (Build.VERSION.SDK_INT >= 28) ServiceReaders.connect(context.applicationContext)
        else throw IOException("This phone has no SIM readers for apps")
    })

    /** The phone's SIM readers, by slot. */
    interface Readers : AutoCloseable {
        val isConnected: Boolean
        val slots: List<Int>
        /** Opens [aid] on the card in [slot]; throws as [android.se.omapi.Session.openLogicalChannel] does. */
        fun openChannel(slot: Int, aid: ByteArray): SeChannel
        /** The card's answer to reset, which says which kind of card it is; any app may read it. Null if unknown. */
        fun atr(slot: Int): ByteArray?
    }

    /** No card in that slot right now: taken out, or restarting after a switch. */
    class NoCardException : IOException("No card")

    interface SeChannel {
        val selectResponse: ByteArray?
        fun transmit(command: ByteArray): ByteArray
        fun close()
    }

    /** No card in this phone lets this app open its eSIM manager. [locked]: a card refused this app (most
     *  adapter cards only let their maker's app in), rather than no card answering at all. [refused]: the cards,
     *  by ATR, that refused every eSIM manager ID asked for. */
    class UnavailableException(val locked: Boolean, cause: Throwable? = null, val refused: Set<String> = emptySet()) :
        IOException(if (locked) "The card doesn't let this app in directly." else "No eSIM card answered directly.", cause) {
        /** Android's secure element service didn't connect in time, as can happen while the phone is busy. */
        val timedOut get() = cause is BindTimeoutException
    }

    internal class BindTimeoutException : IOException("Secure element service didn't connect in time")

    @Volatile private var readers: Readers? = null
    /** The eSIM manager's ID that opened, by slot: some makers' cards use their own (see [AIDS]). */
    @Volatile private var aids = emptyMap<Int, ByteArray>()
    private val channels = HashMap<Int, SeChannel>()
    private var nextChannel = 1

    override fun isReady(): Boolean = readers?.isConnected == true

    /** Connects, then checks that some card lets this app open its eSIM manager. Throws [UnavailableException]
     *  when none does: then this app needs another way in. Cards in [refused] (ATRs) aren't asked: each ID asked
     *  for makes Android check the card's access rules on the card itself, ~50 ms a time on a real phone. */
    fun connect(refused: Set<String> = emptySet()) {
        if (isReady()) return
        disconnect()
        val found = try { open() } catch (e: Exception) { throw UnavailableException(false, e) }
        var locked = false
        var last: Throwable? = null
        val refusedNow = HashSet<String>()
        for (slot in found.slots) {
            val atr = runCatching { found.atr(slot) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let(Hex::encode)
            if (atr != null && atr in refused) { locked = true; refusedNow += atr; continue }
            var allRefused = true
            for (aid in AIDS) {
                try {
                    found.openChannel(slot, aid).close()
                    aids = mapOf(slot to aid)
                    readers = found
                    return
                } catch (e: SecurityException) { locked = true; last = e } // the card's access rules
                catch (e: Exception) { allRefused = false; last = e } // no such eSIM manager (an ordinary SIM), no card
            }
            if (allRefused && atr != null) refusedNow += atr
        }
        runCatching { found.close() }
        Log.i(TAG, "No direct card access: ${last?.javaClass?.simpleName ?: "refused before"}")
        throw UnavailableException(locked, last, refusedNow)
    }

    override fun openChannel(slot: Int, port: Int, aidHex: String): OpenResult {
        val all = readers ?: throw ApduException("Card access disconnected. Reconnect first.")
        if (port != 0) throw ApduException("This phone has no SIM ports.")
        if (slot !in all.slots) return OpenResult(0, STATUS_MISSING, null) // no reader there, so no card either
        // The makers' IDs are for the helper: here the card's own ID comes from [AIDS], below and in [connect].
        if (!aidHex.equals(Lpa.ISD_R_OPEN, ignoreCase = true) && !aidHex.equals(Lpa.ISD_R, ignoreCase = true))
            return OpenResult(0, STATUS_MISSING, null)
        // Access rules name the eSIM manager's full ID, so the shorter one the helper selects with won't match them.
        val aid = aids[slot] ?: ISD_R
        val channel = try { all.openChannel(slot, aid) } catch (e: NoSuchElementException) {
            otherId(all, slot, aid) ?: return OpenResult(0, STATUS_MISSING, null) // no such applet: an ordinary SIM
        } catch (e: NoCardException) {
            return OpenResult(0, STATUS_MISSING, null)
        } catch (e: SecurityException) {
            // Another SIM, like the phone's own, refuses this app as most do; it holds no eSIMs anyway.
            if (slot !in aids) return OpenResult(0, STATUS_MISSING, null)
            // The card that let this app in was swapped for one that doesn't: card access starts over.
            disconnect()
            throw ApduException("The card in SIM ${slot + 1} doesn't let this app in.", e)
        } catch (e: Exception) {
            // Also while the card restarts after a switch, which ends its earlier sessions.
            throw ApduException("Couldn't open the card in SIM ${slot + 1}. Try again.${Lpa.CARD_BUSY_TIP}", e, cardThere = true)
        }
        val number = synchronized(channels) { nextChannel++.also { channels[it] = channel } }
        return OpenResult(number, 0, channel.selectResponse)
    }

    /** [tried] isn't on the card in [slot]: it may be another make's, or have moved from another slot, and [connect]
     *  doesn't run again while card access lasts. Any refusal only rules out that ID, as access rules can differ
     *  from one to the next. The one that opens is kept for this slot. */
    private fun otherId(all: Readers, slot: Int, tried: ByteArray): SeChannel? {
        for (aid in AIDS) {
            if (aid.contentEquals(tried)) continue
            try { return all.openChannel(slot, aid).also { aids = aids + (slot to aid) } } catch (_: Exception) { }
        }
        return null
    }

    /** Android adds the channel number to the class byte, and fetches long responses (61xx) by itself. */
    override fun transmit(slot: Int, port: Int, channel: Int, cla: Int, ins: Int, p1: Int, p2: Int, p3: Int, dataHex: String): String {
        val open = synchronized(channels) { channels[channel] } ?: throw ApduException("Card channel closed. Try again.")
        return try { Hex.encode(open.transmit(command(cla, ins, p1, p2, p3, dataHex))) } catch (e: Exception) {
            throw ApduException("Card connection lost. Reconnect and refresh before trying again.", e)
        }
    }

    override fun closeChannel(slot: Int, port: Int, channel: Int) {
        val open = synchronized(channels) { channels.remove(channel) } ?: return
        runCatching { open.close() } // fails after a card restart, which already closed it
    }

    fun disconnect() {
        val all = synchronized(channels) { channels.values.toList().also { channels.clear() } }
        all.forEach { runCatching { it.close() } }
        readers?.let { runCatching { it.close() } }
        readers = null
        aids = emptyMap()
    }

    override fun close() = disconnect()

    /** OMAPI over [SEService]: one session per reader, opened again when the card restarted. */
    @RequiresApi(28)
    private class ServiceReaders(private val service: SEService) : Readers {
        private val readers = service.readers.mapNotNull { r -> slotOf(r.name)?.let { it to r } }.toMap()
        private val sessions = HashMap<Int, Session>()
        /** Android restarted its secure element service: these readers no longer reach it. */
        @Volatile private var dead = false
        override val isConnected get() = service.isConnected && !dead
        override val slots = readers.keys.sorted()

        override fun atr(slot: Int): ByteArray? {
            val reader = readers[slot] ?: return null
            return if (alive { reader.isSecureElementPresent }) alive { session(slot, false).atr } else null
        }

        override fun openChannel(slot: Int, aid: ByteArray): SeChannel {
            val reader = readers[slot] ?: throw IOException("No reader for slot $slot")
            if (!alive { reader.isSecureElementPresent }) throw NoCardException()
            val channel = try { session(slot, false).openLogicalChannel(aid, 0) } catch (e: IOException) {
                session(slot, true).openLogicalChannel(aid, 0) // the session ended with a card restart
            } catch (e: IllegalStateException) { alive { session(slot, true).openLogicalChannel(aid, 0) } }
            // Null when the card has no logical channel left.
            return Wrapped(channel ?: throw IOException("No logical channel available"))
        }

        /** Reader calls throw IllegalStateException once the service they came from is gone. */
        private fun <T> alive(block: () -> T): T = try { block() } catch (e: IllegalStateException) {
            dead = true
            throw IOException("Secure element service restarted", e)
        }

        private fun session(slot: Int, fresh: Boolean): Session = synchronized(sessions) {
            val old = sessions[slot]
            if (old != null && !fresh && !old.isClosed) return old
            runCatching { old?.close() }
            readers.getValue(slot).openSession().also { sessions[slot] = it }
        }

        override fun close() {
            synchronized(sessions) { sessions.values.forEach { runCatching { it.close() } }; sessions.clear() }
            runCatching { service.shutdown() }
        }

        private class Wrapped(private val channel: Channel) : SeChannel {
            override val selectResponse: ByteArray? get() = channel.selectResponse
            override fun transmit(command: ByteArray): ByteArray = channel.transmit(command)
            override fun close() = channel.close()
        }

        companion object {
            /** Binds Android's secure element service; phones without one, or that never answer, have none.
             *  Not on the main thread: the service connects there. */
            fun connect(context: Context): Readers {
                // Phones updated to Android 10 may have SIM readers without declaring them: there it's enough that the
                // service exists (no need to wait for a bind that never comes).
                val pm = context.packageManager
                if (if (Build.VERSION.SDK_INT >= 30) !pm.hasSystemFeature(PackageManager.FEATURE_SE_OMAPI_UICC)
                    else pm.resolveService(Intent().setClassName("com.android.se", SE_SERVICE), 0) == null)
                    throw IOException("This phone has no SIM readers for apps")
                val ready = CountDownLatch(1)
                val service = SEService(context, { it.run() }) { ready.countDown() }
                // Usually well under a second, but at app start the main thread, which delivers it, is busy too.
                if (!ready.await(5, TimeUnit.SECONDS)) {
                    runCatching { service.shutdown() }
                    throw BindTimeoutException()
                }
                if (!service.isConnected) {
                    runCatching { service.shutdown() }
                    throw IOException("Secure element service unavailable")
                }
                return try { ServiceReaders(service) } catch (e: Exception) {
                    runCatching { service.shutdown() }
                    throw IOException("Secure element service unavailable", e)
                }
            }
        }
    }

    companion object {
        private const val TAG = "CardAccess"
        /** The service [SEService] binds to, in package com.android.se. */
        private const val SE_SERVICE = "com.android.se.SecureElementService"
        private val ISD_R = Hex.decode(Lpa.ISD_R)
        /** The standard eSIM manager first, then the IDs some adapter cards use instead, as OpenEUICC tries them
         *  (eSIM.me, 5ber, XeSIM, LinksField, ESTKme). Access rules can let apps into one and not another. */
        private val AIDS = (listOf(Lpa.ISD_R) + CardShell.ISD_R_AIDS.drop(1)).map(Hex::decode)
        /** As telephony reports an applet that isn't there (IccOpenLogicalChannelResponse.STATUS_NO_SUCH_ELEMENT). */
        private const val STATUS_MISSING = 3

        /** "SIM1" is slot 0; eSE and SD readers aren't SIM cards. Single-SIM phones may say just "SIM". */
        internal fun slotOf(reader: String): Int? {
            if (!reader.startsWith("SIM", ignoreCase = true)) return null
            val n = reader.substring(3)
            return if (n.isEmpty()) 0 else n.toIntOrNull()?.takeIf { it in 1..8 }?.minus(1)
        }

        /** The APDU as telephony builds it from the same fields: case 2 (Le = [p3]) without data, else case 3. */
        internal fun command(cla: Int, ins: Int, p1: Int, p2: Int, p3: Int, dataHex: String): ByteArray {
            val data = Hex.decode(dataHex)
            require(data.isEmpty() || data.size == p3) { "APDU length mismatch" }
            return byteArrayOf(cla.toByte(), ins.toByte(), p1.toByte(), p2.toByte(), p3.toByte()) + data
        }
    }
}
