package com.esimonsim.es9

import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Timer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.schedule
import kotlin.io.encoding.Base64
import javax.net.ssl.HttpsURLConnection

/**
 * ES9+ client: talks JSON over HTTPS directly to the profile's SM-DP+ server
 * (not to any 5ber server). Mirrors lpac's es9p flow and headers.
 */
class Es9Client(private val smdp: String, private val connectTimeoutMs: Int = 30_000, private val cancel: Cancel? = null,
                private val transport: Transport = Https) {

    /** Stops requests from another thread: [cancel] drops the connection in use, so a call waiting on the
     *  server throws at once, and later calls throw before connecting. Card commands are never cut off. */
    class Cancel {
        @Volatile var cancelled = false; private set
        private var conn: HttpURLConnection? = null

        /** Closing a connection is network I/O: not on the main thread. */
        fun cancel() { synchronized(this) { cancelled = true; conn }?.disconnect() }
        fun check() { if (cancelled) throw IOException("Cancelled") }
        internal fun track(c: HttpURLConnection) = synchronized(this) { check(); conn = c }
    }

    /** One HTTP exchange, kept apart so tests can play a provider and still run the real JSON handling below. */
    fun interface Transport {
        fun post(url: String, json: String, connectTimeoutMs: Int, readTimeoutMs: Int, cancel: Cancel?): Answer
    }

    /** The server's HTTP status and its reply. */
    data class Answer(val code: Int, val text: String)

    /** [httpCode]: the server's HTTP status when it answered with an error, else 0. */
    /** [subject]: the SGP.22 subject code the server refused with, e.g. "8.2.6"; empty when it didn't say. */
    class Es9Exception(message: String, val httpCode: Int = 0, val subject: String = "") : Exception(message)

    data class InitiateResult(
        val transactionId: String,
        val serverSigned1: String,
        val serverSignature1: String,
        val euiccCiPKIdToBeUsed: String,
        val serverCertificate: String,
    )

    data class AuthClientResult(
        val profileMetadata: String?,
        val smdpSigned2: String,
        val smdpSignature2: String,
        val smdpCertificate: String,
    )

    fun initiateAuthentication(euiccChallengeB64: String, euiccInfo1B64: String): InitiateResult {
        val body = JSONObject()
            .put("smdpAddress", smdp)
            .put("euiccChallenge", euiccChallengeB64)
            .put("euiccInfo1", euiccInfo1B64)
        val r = post("/gsma/rsp2/es9plus/initiateAuthentication", body)
        return InitiateResult(
            transactionId = r.need("transactionId"),
            serverSigned1 = r.need("serverSigned1"),
            serverSignature1 = r.need("serverSignature1"),
            euiccCiPKIdToBeUsed = r.need("euiccCiPKIdToBeUsed"),
            serverCertificate = r.need("serverCertificate"),
        )
    }

    fun authenticateClient(transactionId: String, authServerResponseB64: String): AuthClientResult {
        val body = JSONObject()
            .put("transactionId", transactionId)
            .put("authenticateServerResponse", authServerResponseB64)
        val r = post("/gsma/rsp2/es9plus/authenticateClient", body)
        // A discovery server answers on the same path, with where the eSIM really is.
        if (!r.has("smdpSigned2") && r.has("eventEntries")) throw Handover(events(r))
        return AuthClientResult(
            profileMetadata = r.optString("profileMetadata").ifEmpty { null },
            smdpSigned2 = r.need("smdpSigned2"),
            smdpSignature2 = r.need("smdpSignature2"),
            smdpCertificate = r.need("smdpCertificate"),
        )
    }

    /** AuthenticateClient sent to an SM-DS: the eSIMs waiting for this card, as (event ID, SM-DP+ address). */
    fun discoveryEvents(transactionId: String, authServerResponseB64: String): List<Pair<String, String>> {
        val body = JSONObject()
            .put("transactionId", transactionId)
            .put("authenticateServerResponse", authServerResponseB64)
        return events(post("/gsma/rsp2/es9plus/authenticateClient", body))
    }

    /** The server asked to download from was a discovery server: the eSIM is at one of [events], each as
     *  (event ID, server address). */
    class Handover(val events: List<Pair<String, String>>) : Exception("The eSIM is waiting at another server")

    fun getBoundProfilePackage(transactionId: String, prepareDownloadResponseB64: String): String {
        val body = JSONObject()
            .put("transactionId", transactionId)
            .put("prepareDownloadResponse", prepareDownloadResponseB64)
        val r = post("/gsma/rsp2/es9plus/getBoundProfilePackage", body)
        return r.need("boundProfilePackage")
    }

    /** A field the reply can't do without. JSONException's own "No value for…" would reach the screen. */
    private fun JSONObject.need(name: String) =
        optString(name).ifEmpty { throw Es9Exception("The eSIM provider's server sent an incomplete reply. Try again later.") }

    /** True once the server has answered: it took the notice, or refused it, which sending it again
     *  won't change. False when it couldn't be reached, failed on its side, or asked to be tried later
     *  (408, 429): worth sending later. */
    fun handleNotification(pendingNotificationB64: String): Boolean {
        val body = JSONObject().put("pendingNotification", pendingNotificationB64)
        return try { post("/gsma/rsp2/es9plus/handleNotification", body, expectJson = false); true }
        catch (e: Es9Exception) { e.httpCode in 400..499 && e.httpCode != 408 && e.httpCode != 429 }
        catch (_: Exception) { false }
    }

    /** ES9+ CancelSession, with the card's signed answer to ES10b CancelSession. On a path that's already failing
     *  with the busy screen up, so it gets less time than a request people are waiting for. */
    fun cancelSession(transactionId: String, cancelSessionResponseB64: String) {
        val body = JSONObject().put("transactionId", transactionId).put("cancelSessionResponse", cancelSessionResponseB64)
        post("/gsma/rsp2/es9plus/cancelSession", body, expectJson = false, timeoutMs = CANCEL_TIMEOUT_MS)
    }

    /** [timeoutMs], when given, is both the connect and the read timeout. */
    private fun post(path: String, body: JSONObject, expectJson: Boolean = true, timeoutMs: Int = 0): JSONObject {
        val (code, text) = transport.post("https://$smdp$path", body.toString(),
            if (timeoutMs > 0) timeoutMs else connectTimeoutMs, if (timeoutMs > 0) timeoutMs else READ_TIMEOUT_MS, cancel)
        if (code !in 200..299) {
            Log.w(TAG, "$path HTTP $code: ${text.take(500)}")
            throw Es9Exception("The eSIM provider's server returned an error (HTTP $code). Try again later.", code)
        }
        if (!expectJson) return JSONObject()

        fun unexpected(): Es9Exception {
            Log.w(TAG, "$path unexpected reply: ${text.take(200)}")
            return Es9Exception("The eSIM provider's server sent an unexpected reply. Try again later.")
        }
        val json = try { JSONObject(text.trim()) } catch (e: Exception) { throw unexpected() }
        catch (e: StackOverflowError) { throw unexpected() } // Android's parser recurses, with no limit of its own
        // Surface a server-reported execution failure with its reason.
        json.optJSONObject("header")?.optJSONObject("functionExecutionStatus")?.let { fes ->
            val status = fes.optString("status")
            if (status.isNotEmpty() && !status.startsWith("Executed")) {
                val scd = fes.optJSONObject("statusCodeData")
                val subject = scd?.optString("subjectCode").orEmpty()
                val reason = scd?.optString("reasonCode").orEmpty()
                val msg = scd?.optString("message").orEmpty()
                Log.w(TAG, "$path rejected: $status $subject/$reason $msg")
                throw Es9Exception("${describe(subject, reason)} (code $subject/$reason${if (msg.isNotEmpty()) ": $msg" else ""})",
                    subject = subject)
            }
        }
        return json
    }

    /** The real thing: HTTPS, with the GSMA roots trusted as well (see [CiTls]). */
    object Https : Transport {
        private val deadlines by lazy { Timer("es9-deadline", true) }

        override fun post(url: String, json: String, connectTimeoutMs: Int, readTimeoutMs: Int, cancel: Cancel?) =
            post(url, json, connectTimeoutMs, readTimeoutMs, cancel, DEADLINE_MS)

        internal fun post(url: String, json: String, connectTimeoutMs: Int, readTimeoutMs: Int, cancel: Cancel?,
                          deadlineMs: Long): Answer {
            // Same host + same socket factory for every call, so keep-alive reuses the TLS session.
            val conn = URL(url).openConnection() as HttpURLConnection
            cancel?.track(conn)
            if (conn is HttpsURLConnection) CiTls.socketFactory?.let { conn.sslSocketFactory = it }
            conn.requestMethod = "POST"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", "gsma-rsp-lpad")
            conn.setRequestProperty("X-Admin-Protocol", "gsma/rsp/v2.2.2")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            // A read timeout only limits each read, so a server sending a byte at a time gets round it. Dropping the
            // connection from outside also ends a wait for the status line and headers, which no read loop could.
            val expired = AtomicBoolean()
            val deadline = deadlines.schedule(deadlineMs) { expired.set(true); runCatching { conn.disconnect() } }
            try {
                conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                // A cancel just before connecting finds nothing to drop: caught here, before the wait for the answer.
                cancel?.check()

                val code = conn.responseCode
                if (code !in 200..299) return Answer(code, conn.errorStream?.use { String(read(it, MAX_ERROR_BYTES)) }.orEmpty())
                val reply = conn.inputStream.use { read(it, MAX_REPLY_BYTES) }
                // The stream can just end when the connection drops, which mustn't pass for a whole reply.
                if (expired.get()) throw IOException("Dropped at the deadline")
                if (reply.size > MAX_REPLY_BYTES)
                    throw Es9Exception("The eSIM provider's server sent far more than an eSIM needs, so it wasn't used. Try again later.")
                return Answer(code, String(reply))
            } catch (e: Exception) {
                if (!expired.get()) throw e
                throw SocketTimeoutException("Server exchange took too long").apply { initCause(e) } // not "Socket closed"
            } finally { deadline.cancel() }
        }
    }

    companion object {
        private const val TAG = "ES9"
        private const val READ_TIMEOUT_MS = 60_000
        private const val CANCEL_TIMEOUT_MS = 10_000
        /** A whole request. Generous, as a package can be slow on poor mobile data, and giving up on a genuine one
         *  could cost a one-time activation code; installs can't be cancelled, so this is what ends a stuck one. */
        private const val DEADLINE_MS = 5 * 60_000L
        /** Well above a base64 eSIM package. A reply is held in memory, so a hostile server can't be left to fill it. */
        private const val MAX_REPLY_BYTES = 8 * 1024 * 1024
        /** Error bodies are only logged. */
        private const val MAX_ERROR_BYTES = 64 * 1024

        /** [input] up to one byte past [limit], so a caller can tell it was cut off. Counts what comes out of the
         *  stream: decompressed, so gzip can't get round it. */
        internal fun read(input: InputStream, limit: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (out.size() <= limit) {
                val n = input.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }

        /** eventEntries as (event ID, server address): an empty list when nothing waits for this card
         *  (SGP.22 5.8.2). Entries without a usable address are left out. */
        internal fun events(r: JSONObject): List<Pair<String, String>> {
            val events = r.optJSONArray("eventEntries") ?: return emptyList()
            return (0 until events.length()).mapNotNull { i ->
                val e = events.optJSONObject(i) ?: return@mapNotNull null
                val smdp = e.optString("rspServerAddress").trim().takeIf { '.' in it } ?: return@mapNotNull null
                e.optString("eventId").trim() to smdp
            }
        }

        fun b64(bytes: ByteArray): String = Base64.encode(bytes)
        // SM-DP+ uses the standard alphabet (+ and /); strip any line breaks first. Padding optional, as with Java's.
        fun unb64(s: String): ByteArray = Base64.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
            .decode(if (s.any(Char::isWhitespace)) s.filterNot(Char::isWhitespace) else s)

        /** SGP.22 ES9+ status codes (subject / reason) for the rejections people actually hit. */
        internal fun describe(subject: String, reason: String): String = when {
            reason == "4.8" -> "The card doesn't have room for this eSIM. Delete an eSIM you no longer use, then try again."
            reason == "6.4" -> "Too many attempts for this eSIM. Ask your provider for a new activation code."
            reason == "4.10" -> "This activation code has expired. Ask your provider for a new one."
            subject == "8.2.6" -> "The provider refused this activation code. It may be mistyped, expired or already used."
            subject == "8.2.7" -> "The provider needs a valid confirmation code for this eSIM."
            subject == "8.8.5" -> "This eSIM can't be downloaded any more. It may have expired or already been installed."
            subject == "8.1.1" -> "The provider won't send this eSIM to this card. It may be tied to another device."
            else -> "The eSIM provider refused the request. Ask your provider for help and quote this code."
        }
    }
}
