package com.esimonsim.apdu

import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The app's end of a connection to the card helper running in the background (adb/HelperServer), over
 *  127.0.0.1: works without Wi-Fi or Wireless debugging. Both ends prove they hold [key] with HMAC over
 *  fresh nonces, so the key never crosses the socket and nothing else on the phone can pose as either.
 *  That holds as long as only the helper got the key: on Android 10 and older it goes over plain adb, without
 *  TLS, so AdbApduBackend first has adbd prove who it is (AdbApduBackend.proveShell).
 *  A reader thread notices at once when the helper goes away (the phone restarted, adbd stopped). */
internal class HelperLink private constructor(private val socket: Socket, private val input: InputStream) {
    private val lines = LinkedBlockingQueue<String>()
    @Volatile var isOpen = true; private set

    /** The APK the helper's code comes from; differs from the app's own after an update. */
    var apk: String = ""; private set

    init {
        Thread({
            try { while (true) lines.put(readLine(input) ?: break) } catch (_: Exception) { }
            isOpen = false
            lines.put(GONE)
        }, "card-helper-link").apply { isDaemon = true }.start()
    }

    /** One request and its "EOS:" answer. Throws IOException when the helper is gone or doesn't answer
     *  within [timeoutSeconds]; the link is closed then, as a late answer would be taken for the next one. */
    fun call(request: JSONObject, timeoutSeconds: Long): JSONObject {
        if (!isOpen) throw IOException("Card helper exited")
        try {
            socket.getOutputStream().apply { write("$request\n".toByteArray(Charsets.UTF_8)); flush() }
            while (true) {
                // A time-out, not a lost helper: a stuck card request on another connection can cause it. The grace is
                // for a process Android froze: on waking, this can run before the reader has queued the answer.
                val line = lines.poll(timeoutSeconds, TimeUnit.SECONDS) ?: lines.poll(GRACE_SECONDS, TimeUnit.SECONDS)
                    ?: throw SocketTimeoutException("Card helper timed out")
                if (line === GONE) throw IOException("Card helper exited")
                if (line.startsWith("EOS:")) return JSONObject(line.substring(4))
            }
        } catch (e: Exception) {
            close()
            throw e as? IOException ?: IOException(e)
        }
    }

    fun close() {
        isOpen = false
        runCatching { socket.close() }
    }

    /** The helper refused this app's proof: it was started with another key, so it can't be used. */
    class RefusedException : IOException("Card helper refused the app")

    /** The other end hung up during the handshake, without a word. A helper with all its connections taken does just
     *  that, so this doesn't say it's gone: one that is gone no longer accepts connections at all. */
    class HungUpException : IOException("Not a card helper")

    companion object {
        private val GONE = String() // identity marks the end of the stream
        /** How long past its limit a request waits for an answer: see [call]. [PipeApduBackend] and the ADB stream of
         *  [AdbApduBackend] wait the same. */
        internal const val GRACE_SECONDS = 2L
        private const val HANDSHAKE_MS = 5000
        /** Hello and the welcome are short, even with the APK's path. */
        private const val HANDSHAKE_LINE = 512
        private val LOOPBACK = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        /** Connects and runs the handshake. ConnectException: nothing listens on [port] any more. */
        fun open(port: Int, key: ByteArray, connectTimeoutMs: Int = 1500): HelperLink {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(LOOPBACK, port), connectTimeoutMs)
                val deadline = System.nanoTime() + HANDSHAKE_MS * 1_000_000L
                socket.tcpNoDelay = true // one short line each way per APDU
                val input = socket.getInputStream().buffered()
                fun answer(): JSONObject {
                    val line = handshakeLine(socket, input, deadline) ?: throw HungUpException()
                    if (!line.startsWith("EOS:")) throw IOException("Not a card helper")
                    return JSONObject(line.substring(4))
                }
                val hello = answer()
                val helperNonce = hello.optString("nonce")
                if (hello.optInt("hello") != VERSION || helperNonce.length != 32) throw IOException("Not a card helper")
                val appNonce = hex(ByteArray(16).also { SecureRandom().nextBytes(it) })
                val auth = JSONObject().put("op", "auth").put("nonce", appNonce)
                    .put("mac", hex(mac(key, "app", helperNonce, appNonce)))
                socket.getOutputStream().apply { write("$auth\n".toByteArray(Charsets.UTF_8)); flush() }
                val welcome = answer()
                if (!welcome.optBoolean("ok")) throw RefusedException()
                // A server that can't prove the key isn't our helper, whatever it says.
                val proof = runCatching { unhex(welcome.getString("mac")) }.getOrNull()
                if (proof == null || !MessageDigest.isEqual(proof, mac(key, "helper", appNonce, helperNonce)))
                    throw IOException("Not a card helper")
                socket.soTimeout = 0 // the reader waits as long as the app stays connected
                return HelperLink(socket, input).also { it.apk = welcome.optString("apk") }
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
        }

        /** Background helper protocol, adb/HelperServer.VERSION. */
        const val VERSION = 2

        fun newKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

        private fun mac(key: ByteArray, who: String, first: String, second: String): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal("$who:$first:$second".toByteArray(Charsets.UTF_8))
            }

        /** One line, at most 64 KB (an answer can carry a whole card response); null at the end of the stream. */
        private fun readLine(input: InputStream): String? {
            val line = StringBuilder()
            while (true) {
                val c = input.read()
                if (c == -1) return line.takeIf { it.isNotEmpty() }?.toString()
                if (c == '\n'.code) return line.toString()
                if (line.length >= 65536) throw IOException("Answer too large")
                line.append(c.toChar())
            }
        }

        /** A handshake line, with [deadline] (System.nanoTime) for the whole of it, as at the helper's end: this runs
         *  on the card thread, and whatever holds an old port could keep it here by trickling bytes. Null when the
         *  other end closed without saying anything. */
        private fun handshakeLine(socket: Socket, input: InputStream, deadline: Long): String? {
            val line = StringBuilder()
            while (true) {
                val left = (deadline - System.nanoTime()) / 1_000_000L
                if (left <= 0) throw SocketTimeoutException("Handshake too slow")
                socket.soTimeout = left.toInt()
                val c = input.read()
                if (c == -1) return line.takeIf { it.isNotEmpty() }?.toString()
                if (c == '\n'.code) return line.toString()
                if (line.length >= HANDSHAKE_LINE) throw IOException("Not a card helper")
                line.append(c.toChar())
            }
        }

        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        fun unhex(s: String): ByteArray {
            require(s.length % 2 == 0)
            return ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        }
    }
}
