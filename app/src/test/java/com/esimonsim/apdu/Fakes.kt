package com.esimonsim.apdu

import android.content.SharedPreferences
import com.esimonsim.lpa.Hex
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.NoSuchElementException

/** What a fake SIM reader holds. */
enum class FakeCard { OPEN, LOCKED, ORDINARY }

/** OMAPI readers by slot. An OPEN card answers each APDU with [reply] (hex in, hex out). */
class FakeReaders(private val cards: Map<Int, FakeCard>, var aid: String? = null,
                  private val reply: (String) -> String = { "9000" }) : OmapiApduBackend.Readers {
    val opened = ArrayList<String>()
    val sent = ArrayList<String>()
    var openChannels = 0
    var closed = false
    override val isConnected get() = !closed
    override val slots get() = cards.keys.sorted()

    override fun openChannel(slot: Int, aid: ByteArray): OmapiApduBackend.SeChannel {
        opened += Hex.encode(aid)
        return when (cards[slot]) {
            FakeCard.LOCKED -> throw SecurityException("SecureElement-AccessControlEnforcer no APDU access allowed")
            FakeCard.ORDINARY -> throw NoSuchElementException("applet not found")
            null -> throw IOException("no card")
            FakeCard.OPEN -> {
                // A maker's own eSIM manager ID instead of the standard one.
                if (this.aid != null && Hex.encode(aid) != this.aid) throw NoSuchElementException("applet not found")
                openChannels++
                object : OmapiApduBackend.SeChannel {
                    override val selectResponse = Hex.decode("6F0084109000")
                    override fun transmit(command: ByteArray) = Hex.decode(reply(Hex.encode(command).also { sent += it }))
                    override fun close() { openChannels-- }
                }
            }
        }
    }

    var atrReads = 0
    /** Cards of a kind share an ATR. */
    override fun atr(slot: Int): ByteArray? = cards[slot]?.let { atrReads++; byteArrayOf(0x3B, it.ordinal.toByte()) }

    override fun close() { closed = true }
}

/** su as the JVM sees it: [helper] answers each JSON request; null ends the process, as su does when root is
 *  turned down. [exitAtOnce]: the process ends before reading anything. [serverGone]: a process in Shizuku's server
 *  after that died, whose calls throw. */
class FakeSu(private val exitAtOnce: Boolean = false, private val serverGone: Boolean = false,
             private val helper: (JSONObject) -> JSONObject?) : Process() {
    private val appWrites = PipedOutputStream()
    private val helperReads = PipedInputStream(appWrites, 65536)
    private val helperWrites = PipedOutputStream()
    private val appReads = PipedInputStream(helperWrites, 65536)
    @Volatile var destroyed = false

    init {
        Thread {
            try {
                if (!exitAtOnce) helperReads.bufferedReader().use { input ->
                    while (true) {
                        val line = input.readLine() ?: break
                        val answer = helper(JSONObject(line)) ?: break
                        helperWrites.write("EOS:$answer\n".toByteArray()); helperWrites.flush()
                    }
                }
            } catch (_: IOException) { }
            runCatching { helperWrites.close() }
        }.apply { isDaemon = true }.start()
    }

    override fun getOutputStream(): OutputStream = appWrites
    override fun getInputStream(): InputStream = appReads
    override fun getErrorStream(): InputStream {
        if (serverGone) throw RuntimeException("DeadObjectException")
        return ByteArrayInputStream(if (exitAtOnce) "Permission denied\n".toByteArray() else ByteArray(0))
    }
    override fun waitFor() = 0
    override fun exitValue() = 0
    override fun destroy() {
        destroyed = true
        runCatching { helperWrites.close() }
        if (serverGone) throw RuntimeException("DeadObjectException")
    }
}

/** Shizuku as this app sees it. [answer] is what people say when asked, null for no answer. [arrives]: its connection
 *  only comes while something waits for it, as just after the app starts. */
class FakeShizuku(override var installed: Boolean = true, override var running: Boolean = true,
                  var mayRun: Boolean = false, var answer: Boolean? = true, var arrives: Boolean = false,
                  var process: () -> Process = { FakeSu(helper = helperAs(2000)) }) : ShizukuApduBackend.Gate {
    var asks = 0
    var starts = 0
    var waits = 0
    var cancels = 0
    var command = ""
    override val allowed get() = running && mayRun
    override val asking = false
    override val changes = MutableStateFlow(0)
    override fun awaitRunning(ms: Long) { waits++; if (arrives) running = true }
    override fun ask(seconds: Long): Boolean? { asks++; if (answer == true) mayRun = true; return answer }
    override fun cancelAsk() { cancels++ }
    override fun start(command: String): Process { starts++; this.command = command; return process() }
}

/** A helper running as [uid] that answers every request with ok. */
fun helperAs(uid: Int) = { q: JSONObject ->
    when (q.getString("op")) {
        "ping" -> JSONObject().put("ok", true).put("version", 1).put("uid", uid)
        "useSim" -> JSONObject().put("ok", true).put("applied", true)
        else -> JSONObject().put("ok", true)
    }
}

/** ADB that connects once paired (or with a helper, or a port). [tcpOpen]: like Android 10 and older after
 *  "adb tcpip", where the first connection the phone allows counts as the pairing; [allows] whether it does. */
class FakeAdb(var paired: Boolean = false, var helper: Boolean = false) : CardAccess.Adb {
    val connects = ArrayList<Int?>()
    /** [connect]'s authorize, call by call. */
    val authorized = ArrayList<Boolean>()
    var stops = 0
    var ready = false
    var tcpOpen = false
    var allows = true
    /** What useSim answers: as the helper on a single-SIM Android 8.1 or 9 when true. */
    var roamingMayReset = false
    override val wasPaired get() = paired
    override val hasHelper get() = helper
    override val helperUnavailable = false
    override val inBackground get() = ready
    override fun connect(port: Int?, authorize: Boolean) {
        connects += port
        authorized += authorize
        if (tcpOpen) {
            if (!paired && !authorize) throw AllowNeededException(asked = false)
            if (!allows) throw AllowNeededException(asked = true)
            paired = true
        }
        if (!paired && !helper && port == null) throw IOException("Couldn't connect to Wireless debugging.")
        ready = true
    }
    override fun useSim(slot: Int, iccid: String, data: Boolean, roaming: Boolean) =
        SimSettingsResult(applied = true, roamingMayReset = roaming && roamingMayReset)
    override fun <T> reading(block: () -> T) = block()
    override fun stop() { stops++; helper = false; ready = false }
    override fun disconnect() { ready = false }
    override fun close() { ready = false }
    override fun isReady() = ready
    override fun openChannel(slot: Int, port: Int, aidHex: String) = OpenResult(1, 0, null)
    override fun transmit(slot: Int, port: Int, channel: Int, cla: Int, ins: Int, p1: Int, p2: Int, p3: Int, dataHex: String) = "9000"
    override fun closeChannel(slot: Int, port: Int, channel: Int) {}
}

/** SharedPreferences in memory: Android's are stubs on the JVM. */
class MemoryPrefs : SharedPreferences {
    val values = HashMap<String, Any?>()
    override fun getAll(): Map<String, *> = values
    override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
    override fun getStringSet(key: String, defValues: Set<String>?) = @Suppress("UNCHECKED_CAST") (values[key] as Set<String>? ?: defValues)
    override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as Boolean? ?: defValue
    override fun contains(key: String) = key in values
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        val changes = HashMap<String, Any?>()
        var clear = false
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { changes[key] = values }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { changes[key] = REMOVED }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) values.clear()
            changes.forEach { (k, v) -> if (v === REMOVED) values.remove(k) else values[k] = v }
        }
    }

    private companion object { val REMOVED = Any() }
}
