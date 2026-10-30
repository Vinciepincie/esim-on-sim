package com.esimonsim.adb

import com.esimonsim.apdu.HelperLink
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.IOException
import java.io.OutputStream
import java.io.StringReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/** The background helper's protocol, both ends for real over loopback: HelperServer (the helper) and
 *  HelperLink (the app). Only the card behind it is fake. */
class HelperServerTest {
    private val key = HelperLink.newKey()
    private val server = ServerSocket(0, 4, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    private val closed = CountDownLatch(1)
    private val exits = CountDownLatch(1)
    private val cardStuck = CountDownLatch(1)
    private val cardFreed = CountDownLatch(1)
    private var peerUid = APP_UID
    private val classPath = System.getProperty("java.class.path")

    /** A card session that echoes the request's op, and counts down [closed] when its connection ends. */
    private inner class Echo : HelperServer.Session {
        override fun handle(request: JSONObject): JSONObject {
            if (request.optString("op") == "fail") throw IllegalStateException("card said no")
            if (request.optString("op") == "stick") { cardStuck.countDown(); cardFreed.await(30, TimeUnit.SECONDS) }
            return JSONObject().put("echo", request.getString("op"))
        }
        override fun close() = closed.countDown()
    }

    private fun start(serverKey: ByteArray = key, sessions: HelperServer.Sessions = HelperServer.Sessions { Echo() }) {
        thread(isDaemon = true) {
            runCatching { HelperServer.serve(server, serverKey, APP_UID, sessions, { peerUid }) { exits.countDown() } }
        }
    }

    /** The helper tells the app where it runs from. On a phone that's one APK; here it would be every library's jar. */
    @Before fun runFromAnApk() { System.setProperty("java.class.path", LONGEST_APK) }

    @After fun stop() {
        cardFreed.countDown() // the card lock is shared by every helper in this JVM
        server.close()
        System.setProperty("java.class.path", classPath)
    }

    @Test fun appWithTheKeyGetsAnswers() {
        start()
        val link = HelperLink.open(server.localPort, key)
        assertEquals("ping", link.call(JSONObject().put("op", "ping"), 5).getString("echo"))
        val failed = link.call(JSONObject().put("op", "fail"), 5)
        assertFalse(failed.getBoolean("ok"))
        assertEquals("IllegalStateException: card said no", failed.getString("error"))
        link.close()
    }

    @Test fun wrongKeyIsRefused() {
        start()
        assertThrows(HelperLink.RefusedException::class.java) { HelperLink.open(server.localPort, HelperLink.newKey()) }
    }

    @Test fun otherAppsAreTurnedAwayEvenWithTheKey() {
        peerUid = APP_UID + 1
        start()
        // Closed at once, before the helper even says hello.
        val e = assertThrows(IOException::class.java) { HelperLink.open(server.localPort, key) }
        assertEquals("Not a card helper", e.message)
    }

    @Test fun unlistedConnectionIsTurnedAway() {
        peerUid = -2 // /proc/net/tcp readable, but no such connection in it
        start()
        assertThrows(IOException::class.java) { HelperLink.open(server.localPort, key) }
    }

    @Test fun unknownUidStillNeedsTheKey() {
        peerUid = -1 // Android didn't say: the key decides
        start()
        assertThrows(HelperLink.RefusedException::class.java) { HelperLink.open(server.localPort, HelperLink.newKey()) }
        HelperLink.open(server.localPort, key).close()
    }

    @Test fun helperWithAnotherKeyIsNotTrusted() {
        start(serverKey = HelperLink.newKey())
        // It refuses the app; and the app, not knowing its key, would not believe it either.
        assertThrows(IOException::class.java) { HelperLink.open(server.localPort, key) }
    }

    @Test fun impostorThatSaysYesIsNotBelieved() {
        thread(isDaemon = true) {
            server.accept().use { s ->
                val out = s.getOutputStream()
                out.write("EOS:{\"ok\":true,\"hello\":2,\"nonce\":\"${"0".repeat(32)}\"}\n".toByteArray())
                s.getInputStream().bufferedReader().readLine()
                out.write("EOS:{\"ok\":true,\"mac\":\"${"0".repeat(64)}\"}\n".toByteArray())
                out.flush()
                Thread.sleep(500)
            }
        }
        val e = assertThrows(IOException::class.java) { HelperLink.open(server.localPort, key) }
        assertEquals("Not a card helper", e.message)
    }

    private fun hello(version: Int, nonce: String) = "EOS:{\"ok\":true,\"hello\":$version,\"nonce\":\"$nonce\"}\n".toByteArray()

    /** Says hello as [bytes], then stays a moment. */
    private fun greets(bytes: ByteArray) {
        thread(isDaemon = true) {
            runCatching { server.accept().use { s -> s.getOutputStream().apply { write(bytes); flush() }; Thread.sleep(500) } }
        }
    }

    /** A helper that completes the handshake, then runs [after]. */
    private fun helper(after: (Socket, BufferedReader, OutputStream) -> Unit) {
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { s ->
                    val out = s.getOutputStream()
                    val input = s.getInputStream().bufferedReader()
                    val helperNonce = "1".repeat(32)
                    out.write(hello(2, helperNonce))
                    out.flush()
                    val appNonce = JSONObject(input.readLine()).getString("nonce")
                    val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
                        .doFinal("helper:$appNonce:$helperNonce".toByteArray())
                    out.write("EOS:{\"ok\":true,\"mac\":\"${HelperLink.hex(mac)}\",\"apk\":\"$LONGEST_APK\"}\n".toByteArray())
                    out.flush()
                    after(s, input, out)
                }
            }
        }
    }

    @Test fun anOlderProtocolIsNotAHelper() {
        greets(hello(1, "0".repeat(32)))
        assertEquals("Not a card helper", assertThrows(IOException::class.java) { HelperLink.open(server.localPort, key) }.message)
    }

    @Test fun aShortNonceIsNotAHelper() {
        greets(hello(2, "0".repeat(31)))
        assertEquals("Not a card helper", assertThrows(IOException::class.java) { HelperLink.open(server.localPort, key) }.message)
    }

    @Test fun aSilentServerTimesOutAsATimeOut() {
        // Not a plain IOException: the app keeps the helper's key after a time-out, and only then.
        thread(isDaemon = true) { runCatching { server.accept().use { Thread.sleep(8000) } } }
        val began = System.currentTimeMillis()
        assertThrows(SocketTimeoutException::class.java) { HelperLink.open(server.localPort, key) }
        assertTrue(System.currentTimeMillis() - began in 4000..9000)
    }

    @Test fun aLateAnswerIsNeverTakenForTheNextRequest() {
        helper { _, input, out ->
            input.readLine()
            Thread.sleep((1 + HelperLink.GRACE_SECONDS) * 1000 + 500)
            out.write("EOS:{\"ok\":true,\"late\":true}\n".toByteArray())
            out.flush()
            Thread.sleep(500)
        }
        val link = HelperLink.open(server.localPort, key)
        assertThrows(SocketTimeoutException::class.java) { link.call(JSONObject().put("op", "ping"), 1) }
        assertFalse(link.isOpen)
        assertThrows(IOException::class.java) { link.call(JSONObject().put("op", "ping"), 1) }
    }

    @Test fun anAnswerJustPastTheLimitStillCounts() {
        // A process Android froze wakes after its limit, with the answer about to be read: nothing is sent again.
        helper { _, input, out ->
            input.readLine()
            Thread.sleep(1500)
            out.write("EOS:{\"ok\":true,\"n\":7}\n".toByteArray())
            out.flush()
            Thread.sleep(500)
        }
        val link = HelperLink.open(server.localPort, key)
        assertEquals(7, link.call(JSONObject().put("op", "ping"), 1).getInt("n"))
        assertTrue(link.isOpen)
        link.close()
    }

    @Test fun linesBeforeTheAnswerAreSkipped() {
        helper { _, input, out ->
            input.readLine()
            out.write("warning: something\nmore noise\nEOS:{\"ok\":true,\"n\":7}\n".toByteArray())
            out.flush()
            Thread.sleep(500)
        }
        val link = HelperLink.open(server.localPort, key)
        assertEquals(7, link.call(JSONObject().put("op", "ping"), 5).getInt("n"))
        link.close()
    }

    @Test fun anAnswerOver64KbEndsTheLink() {
        helper { _, input, out ->
            input.readLine()
            out.write(ByteArray(70_000) { 'x'.code.toByte() })
            out.flush()
            Thread.sleep(1000)
        }
        val link = HelperLink.open(server.localPort, key)
        assertThrows(IOException::class.java) { link.call(JSONObject().put("op", "ping"), 5) }
        assertFalse(link.isOpen)
    }

    @Test fun theApkComesWithTheWelcome() {
        start()
        val link = HelperLink.open(server.localPort, key)
        assertEquals(LONGEST_APK, link.apk) // what tells a helper from before an update
        link.close()
    }

    @Test fun anOversizedRequestEndsTheConnection() {
        start()
        val link = HelperLink.open(server.localPort, key)
        assertThrows(IOException::class.java) { link.call(JSONObject().put("op", "x".repeat(9000)), 5) }
        assertTrue(closed.await(5, TimeUnit.SECONDS)) // its channels are released all the same
    }

    @Test fun aFifthConnectionIsTurnedAwayUntilASlotFrees() {
        start()
        val links = List(4) { HelperLink.open(server.localPort, key) }
        // Without a word, which the app mustn't take for the helper being gone.
        assertThrows(HelperLink.HungUpException::class.java) { HelperLink.open(server.localPort, key) }
        links[0].close()
        val until = System.currentTimeMillis() + 5000
        var fifth: HelperLink? = null
        while (fifth == null) {
            fifth = try { HelperLink.open(server.localPort, key) } catch (e: HelperLink.HungUpException) {
                if (System.currentTimeMillis() > until) throw e
                Thread.sleep(20)
                null
            }
        }
        (links.drop(1) + fifth).forEach { it.close() }
    }

    @Test fun aSessionThatCannotOpenEndsTheConnectionAfterTheWelcome() {
        start(sessions = HelperServer.Sessions { throw IllegalStateException("no") })
        // The key is proven by then, so the app knows this is its helper: see AdbApduBackendTest.
        val link = HelperLink.open(server.localPort, key)
        assertThrows(IOException::class.java) { link.call(JSONObject().put("op", "ping"), 5) }
        assertFalse(link.isOpen)
    }

    @Test fun aServerTricklingBytesCannotHoldTheHandshake() {
        // One byte every second: each read alone would beat a per-read time-out, and this is the card thread waiting.
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { s -> repeat(12) { s.getOutputStream().apply { write('{'.code); flush() }; Thread.sleep(1000) } }
            }
        }
        val began = System.currentTimeMillis()
        assertThrows(SocketTimeoutException::class.java) { HelperLink.open(server.localPort, key) }
        assertTrue(System.currentTimeMillis() - began < 9_000)
    }

    @Test fun anOverlongHandshakeLineIsNotAHelper() {
        // Stays connected, so it's the line's length that ends this, and not the end of the stream.
        thread(isDaemon = true) {
            runCatching { server.accept().use { s -> s.getOutputStream().apply { write(ByteArray(600) { 'x'.code.toByte() }); flush() }; Thread.sleep(8000) } }
        }
        val e = assertThrows(IOException::class.java) { HelperLink.open(server.localPort, key) }
        assertEquals("Not a card helper", e.message)
    }

    @Test fun channelsAreReleasedWhenTheAppDisconnects() {
        start()
        val link = HelperLink.open(server.localPort, key)
        link.call(JSONObject().put("op", "open"), 5)
        link.close()
        assertTrue(closed.await(5, TimeUnit.SECONDS))
    }

    @Test fun exitClosesTheChannelsThenEndsTheHelper() {
        start()
        val link = HelperLink.open(server.localPort, key)
        assertTrue(link.call(JSONObject().put("op", "exit"), 5).getBoolean("ok"))
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertTrue(exits.await(5, TimeUnit.SECONDS))
    }

    @Test fun aStuckCardRequestDoesNotKeepTheHelperRunning() {
        // "Stop card access" promises nothing keeps running, and the app holds no key for it once it stopped waiting.
        start()
        val stuck = HelperLink.open(server.localPort, key)
        thread(isDaemon = true) { runCatching { stuck.call(JSONObject().put("op", "stick"), 30) } }
        assertTrue(cardStuck.await(5, TimeUnit.SECONDS))
        val stopping = HelperLink.open(server.localPort, key)
        assertThrows(IOException::class.java) { stopping.call(JSONObject().put("op", "exit"), 1) }
        assertTrue(exits.await(4, TimeUnit.SECONDS))
        assertEquals(1L, closed.count) // it couldn't wait for the card
    }

    @Test fun linkNoticesWhenTheHelperGoes() {
        // A helper that completes the handshake, then its process ends (the phone restarted, adbd stopped).
        thread(isDaemon = true) {
            server.accept().use { s ->
                val out = s.getOutputStream()
                val helperNonce = "1".repeat(32)
                out.write("EOS:{\"ok\":true,\"hello\":2,\"nonce\":\"$helperNonce\"}\n".toByteArray())
                val appNonce = JSONObject(s.getInputStream().bufferedReader().readLine()).getString("nonce")
                val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
                    .doFinal("helper:$appNonce:$helperNonce".toByteArray())
                out.write("EOS:{\"ok\":true,\"mac\":\"${HelperLink.hex(mac)}\"}\n".toByteArray())
                out.flush()
                Thread.sleep(300)
            }
        }
        val link = HelperLink.open(server.localPort, key)
        val until = System.currentTimeMillis() + 5000
        while (link.isOpen && System.currentTimeMillis() < until) Thread.sleep(20)
        assertFalse(link.isOpen)
        assertThrows(IOException::class.java) { link.call(JSONObject().put("op", "ping"), 1) }
    }

    private fun uid(table: String, peerPort: Int, loopback: String = HelperServer.LOOPBACK4) =
        HelperServer.uidIn(BufferedReader(StringReader(table)), loopback, peerPort, 0x83AD)

    @Test fun peerUidComesFromTheConnectionTable() {
        // /proc/net/tcp as on an emulator: the helper's side (uid 2000) and the app's side (uid 10192).
        val table = """
              sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
               0: 0100007F:83AD 00000000:0000 0A 00000000:00000000 00:00000000 00000000  2000        0 1 1
               1: 0100007F:83AD 0100007F:C350 01 00000000:00000000 00:00000000 00000000  2000        0 2 1
               2: 0100007F:C350 0100007F:83AD 01 00000000:00000000 00:00000000 00000000 10192        0 3 1
        """.trimIndent()
        assertEquals(10192, uid(table, 0xC350))
        assertEquals(-1, uid(table, 0xC351))
    }

    @Test fun peerUidIsReadFromTcp6TooAsMappedIpv4() {
        val table = """
              sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
               0: 0000000000000000FFFF00000100007F:C350 0000000000000000FFFF00000100007F:83AD 01 00000000:00000000 00:00000000 00000000 10192        0 3 1
        """.trimIndent()
        assertEquals(10192, uid(table, 0xC350, HelperServer.LOOPBACK6))
    }

    @Test fun anotherLoopbackAddressOnTheSamePortsIsNotTheApp() {
        // Another app bound 127.0.0.2 with the app's own port number: only the 127.0.0.1 row counts.
        val table = """
              sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
               0: 0200007F:C350 0100007F:83AD 01 00000000:00000000 00:00000000 00000000 10300        0 4 1
               1: 0100007F:C350 0100007F:83AD 01 00000000:00000000 00:00000000 00000000 10192        0 3 1
        """.trimIndent()
        assertEquals(10192, uid(table, 0xC350))
        // Two rows for the very same pair can't both be trusted.
        assertEquals(-2, uid(table.replace("0200007F", "0100007F"), 0xC350))
    }

    @Test fun onlyLocalhostItselfIsLocalhost() {
        assertTrue(HelperServer.isLocalhost(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))))
        assertFalse(HelperServer.isLocalhost(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 2))))
        val mapped = ByteArray(16).also { it[10] = -1; it[11] = -1; it[12] = 127; it[15] = 1 }
        assertTrue(HelperServer.isLocalhost(InetAddress.getByAddress(mapped)))
        assertFalse(HelperServer.isLocalhost(InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 }))) // ::1
    }

    @Test fun aClientTricklingBytesLosesItsSlotAfterTheHandshakeTime() {
        start()
        java.net.Socket("127.0.0.1", server.localPort).use { s ->
            s.soTimeout = 15_000
            val input = s.getInputStream()
            while (input.read() != '\n'.code) { } // hello
            val began = System.currentTimeMillis()
            // One byte every second: each read alone would beat a per-read time-out.
            thread(isDaemon = true) {
                runCatching { repeat(12) { s.getOutputStream().apply { write('{'.code); flush() }; Thread.sleep(1000) } }
            }
            assertEquals(-1, runCatching { input.read() }.getOrDefault(-1)) // closed (or reset) by the helper
            assertTrue(System.currentTimeMillis() - began < 9_000)
        }
    }

    private companion object {
        const val APP_UID = 10192
        /** The longest kind there is: adopted storage, with the random folders of Android 11+. */
        const val LONGEST_APK = "/mnt/expand/01234567-89ab-cdef-0123-456789abcdef/app/~~AbCdEfGhIjKlMnOpQrStUw==/" +
            "com.esimonsim-AbCdEfGhIjKlMnOpQrStUw==/base.apk"
    }
}
