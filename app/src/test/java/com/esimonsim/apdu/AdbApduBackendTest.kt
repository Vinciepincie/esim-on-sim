package com.esimonsim.apdu

import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/** Whether a background helper started earlier is used, kept for next time or forgotten, against a scripted helper on
 *  loopback. Losing one that still runs costs card access without Wi-Fi until Wireless debugging is back. Connecting
 *  any other way needs a phone: on the JVM it fails just after, so these look at what that left behind. */
class AdbApduBackendTest {
    private val key = HelperLink.newKey()
    private val prefs = MemoryPrefs().apply { values["key"] = HelperLink.hex(key) }
    private val server = ServerSocket(0, 4, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    private val ops = CopyOnWriteArrayList<String>()

    private class FakeContext(val shared: SharedPreferences, apk: String) : ContextWrapper(null) {
        private val info = ApplicationInfo().apply { sourceDir = apk }
        override fun getApplicationContext() = this
        override fun getSharedPreferences(name: String?, mode: Int) = shared
        override fun getApplicationInfo() = info
    }

    private fun backend(): AdbApduBackend {
        prefs.values["port"] = server.localPort
        return AdbApduBackend(FakeContext(prefs, APK))
    }

    @After fun stop() = server.close()

    private val shell = { _: JSONObject -> JSONObject().put("ok", true).put("uid", 2000) }

    /** Goes through the handshake as the helper, on every connection. [respond]: what a request gets; null hangs up. */
    private fun helper(apk: String = APK, withKey: ByteArray = key, respond: (JSONObject) -> JSONObject? = shell) {
        thread(isDaemon = true) {
            runCatching {
                while (true) {
                    val s = server.accept()
                    thread(isDaemon = true) {
                        runCatching {
                            s.use {
                                val out = s.getOutputStream()
                                val input = s.getInputStream().bufferedReader()
                                val helperNonce = "1".repeat(32)
                                out.write("EOS:{\"ok\":true,\"hello\":2,\"nonce\":\"$helperNonce\"}\n".toByteArray())
                                out.flush()
                                val appNonce = JSONObject(input.readLine()).getString("nonce")
                                val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(withKey, "HmacSHA256")) }
                                    .doFinal("helper:$appNonce:$helperNonce".toByteArray())
                                val welcome = JSONObject().put("ok", true).put("mac", HelperLink.hex(mac)).put("apk", apk)
                                out.write("EOS:$welcome\n".toByteArray())
                                out.flush()
                                while (true) {
                                    val q = JSONObject(input.readLine() ?: break)
                                    ops += q.getString("op")
                                    out.write("EOS:${respond(q) ?: break}\n".toByteArray())
                                    out.flush()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** What answers on the port says [line] on each connection, and goes. */
    private fun answers(line: String) {
        thread(isDaemon = true) {
            runCatching {
                while (true) server.accept().use { s -> s.getOutputStream().apply { write(line.toByteArray()); flush() } }
            }
        }
    }

    private fun AdbApduBackend.tryConnect() = runCatching { reading { connect() } }

    private fun kept() {
        assertEquals(server.localPort, prefs.values["port"])
        assertEquals(HelperLink.hex(key), prefs.values["key"])
    }

    private fun forgotten() {
        assertNull(prefs.values["port"])
        assertNull(prefs.values["key"])
    }

    @Test fun aLiveHelperIsUsed() {
        helper()
        val b = backend()
        val connected = b.tryConnect()
        assertTrue(connected.toString(), connected.isSuccess)
        assertTrue(b.isReady())
        assertTrue(b.inBackground)
        kept()
        b.close()
    }

    @Test fun aSilentPortKeepsTheHelper() {
        // A helper too busy to answer, say: it comes back, and the key is still good then.
        thread(isDaemon = true) { runCatching { server.accept().use { Thread.sleep(8000) } } }
        val b = backend()
        assertTrue(b.tryConnect().isFailure)
        assertFalse(b.isReady())
        kept()
    }

    @Test fun aHangUpKeepsTheHelper() {
        // What a helper with all its connections taken does. Forgetting it here would leave it running, out of reach.
        thread(isDaemon = true) { runCatching { while (true) server.accept().close() } }
        val b = backend()
        assertTrue(b.tryConnect().isFailure)
        assertFalse(b.isReady())
        kept()
    }

    @Test fun aHelperThatProvedTheKeyIsKeptWhateverHappensNext() {
        // Its card session may not have opened, say: this is the right helper, and it's still there.
        helper { null }
        val b = backend()
        assertTrue(b.tryConnect().isFailure)
        assertEquals(listOf("ping"), ops.toList())
        assertFalse(b.isReady())
        kept()
    }

    @Test fun aClosedPortForgetsTheHelper() {
        val b = backend()
        server.close()
        b.tryConnect()
        forgotten()
    }

    @Test fun aHelperThatCannotProveTheKeyIsForgotten() {
        helper(withKey = HelperLink.newKey())
        backend().tryConnect()
        forgotten()
    }

    @Test fun aHelperThatRefusesTheAppIsForgotten() {
        // Started with another key, so this app can't use it, and it says so.
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { s ->
                    val out = s.getOutputStream()
                    out.write("EOS:{\"ok\":true,\"hello\":2,\"nonce\":\"${"1".repeat(32)}\"}\n".toByteArray())
                    out.flush()
                    s.getInputStream().bufferedReader().readLine()
                    out.write("EOS:{\"ok\":false,\"error\":\"SecurityException: Not allowed\"}\n".toByteArray())
                    out.flush()
                }
            }
        }
        backend().tryConnect()
        forgotten()
    }

    @Test fun somethingElseOnThePortIsForgotten() {
        answers("HTTP/1.1 400 Bad Request\r\n\r\n")
        backend().tryConnect()
        forgotten()
    }

    @Test fun anOlderProtocolIsForgotten() {
        answers("EOS:{\"ok\":true,\"hello\":1,\"nonce\":\"${"1".repeat(32)}\"}\n")
        backend().tryConnect()
        forgotten()
    }

    @Test fun aHelperOfAnOlderApkIsToldToExitThenForgotten() {
        helper(apk = "/data/app/old/base.apk")
        val b = backend()
        b.tryConnect()
        assertEquals(listOf("exit"), ops.toList())
        assertFalse(b.isReady())
        forgotten()
    }

    @Test fun aHelperThatIsNotTheShellIsForgotten() {
        helper { JSONObject().put("ok", true).put("uid", 10123) }
        val b = backend()
        b.tryConnect()
        assertFalse(b.isReady())
        forgotten()
    }

    @Test fun helperErrorsAreForPeopleToRead() {
        helper { q ->
            if (q.getString("op") == "open") JSONObject().put("ok", false).put("error", "IllegalStateException: The card gave no answer")
            else shell(q)
        }
        val b = backend()
        b.tryConnect()
        val e = assertThrows(ApduException::class.java) { b.openChannel(1, 0, "A0000005591010FFFFFFFF89000001") }
        assertEquals("The card didn't answer. Check that it's in the phone, then try again.", e.message)
        assertTrue(b.isReady()) // a request failed, not the connection
        b.close()
    }

    @Test fun anAnswerJustPastTheLimitOfTheStreamStillCounts() {
        // A process Android froze: the limit has gone by when it wakes (0 here), with the answer about to be read.
        val answer = FutureTask { "answer" }
        thread { Thread.sleep(100); answer.run() }
        assertEquals("answer", with(AdbApduBackend) { answer.getWithGrace(0) })
    }

    @Test fun aSilentStreamStillTimesOutAfterTheGrace() {
        val silent = FutureTask { "never run" }
        val started = System.nanoTime()
        assertThrows(TimeoutException::class.java) { with(AdbApduBackend) { silent.getWithGrace(0) } }
        assertTrue(System.nanoTime() - started >= TimeUnit.SECONDS.toNanos(HelperLink.GRACE_SECONDS))
    }

    private companion object { const val APK = "/data/app/new/base.apk" }
}
