package com.esimonsim.es9

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/** The real transport, over plain HTTP on loopback: the URL is all that differs from a provider's. */
class HttpsTransportTest {

    private fun <T> server(status: Int, reply: String, check: (Map<String, String>, String) -> Unit = { _, _ -> },
                           run: (String) -> T): T {
        val http = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        http.createContext("/x") { e ->
            val headers = e.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }
            check(headers + ("method" to e.requestMethod), e.requestBody.readBytes().toString(Charsets.UTF_8))
            val bytes = reply.toByteArray()
            e.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) e.responseBody.write(bytes)
            e.close()
        }
        http.start()
        try { return run("http://127.0.0.1:${http.address.port}/x") } finally { http.stop(0) }
    }

    @Test fun postsJsonWithTheHeadersProvidersExpect() {
        var seen: Map<String, String> = emptyMap()
        var body = ""
        val answer = server(200, "{\"a\":1}", { h, b -> seen = h; body = b }) { Es9Client.Https.post(it, "{\"q\":1}", 5000, 5000, null) }
        assertEquals(Es9Client.Answer(200, "{\"a\":1}"), answer)
        assertEquals("{\"q\":1}", body)
        assertEquals("POST", seen["method"])
        assertEquals("gsma-rsp-lpad", seen["user-agent"])
        assertEquals("gsma/rsp/v2.2.2", seen["x-admin-protocol"])
        assertEquals("application/json", seen["content-type"])
        assertEquals("application/json", seen["accept"])
    }

    @Test fun anErrorStatusStillBringsItsBody() {
        assertEquals(Es9Client.Answer(503, "down"), server(503, "down") { Es9Client.Https.post(it, "{}", 5000, 5000, null) })
    }

    @Test fun anErrorWithNoBodyIsEmptyNotACrash() {
        assertEquals(Es9Client.Answer(500, ""), server(500, "") { Es9Client.Https.post(it, "{}", 5000, 5000, null) })
    }

    /** A server that takes a request, then sends [head] and a byte of [drip] every 20 ms, for as long as it can. */
    private fun <T> dripping(head: String, drip: Char, run: (String) -> T): T =
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            thread(isDaemon = true) {
                runCatching {
                    server.accept().use { s ->
                        val request = s.getInputStream()
                        val seen = StringBuilder()
                        while (!seen.endsWith("\r\n\r\n")) seen.append(request.read().toChar())
                        repeat(seen.toString().substringAfter("Content-Length: ").substringBefore('\r').toInt()) { request.read() }
                        s.getOutputStream().write(head.toByteArray())
                        while (true) { s.getOutputStream().write(drip.code); Thread.sleep(20) }
                    }
                }
            }
            run("http://127.0.0.1:${server.localPort}/x")
        }

    private fun assertGivesUpInTime(url: String) {
        val started = System.nanoTime()
        // A read timeout far longer than the test: only the deadline can end it.
        assertThrows(SocketTimeoutException::class.java) { Es9Client.Https.post(url, "{}", 5000, 60_000, null, 300) }
        assertTrue(System.nanoTime() - started < 30_000_000_000L)
    }

    @Test fun aReplySentAByteAtATimeRunsIntoTheDeadline() {
        dripping("HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\n\r\n", 'x', ::assertGivesUpInTime)
    }

    @Test fun headersSentAByteAtATimeRunIntoTheDeadline() {
        dripping("HTTP/1.1 200 OK\r\nX-Slow: ", 'x', ::assertGivesUpInTime)
    }

    @Test fun aRequestInTimeIsUntouchedByItsDeadline() {
        val answer = server(200, "{}") { Es9Client.Https.post(it, "{}", 5000, 5000, null, 60_000) }
        assertEquals(Es9Client.Answer(200, "{}"), answer)
    }

    @Test fun readingStopsJustPastTheLimit() {
        val endless = object : InputStream() { override fun read() = 'x'.code }
        assertEquals(101, Es9Client.read(endless, 100).size)
        assertEquals(100, Es9Client.read(ByteArray(100).inputStream(), 100).size)
        assertEquals(0, Es9Client.read(ByteArray(0).inputStream(), 100).size)
    }

    @Test fun aReplyPastTheCapIsRefused() {
        val http = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        http.createContext("/x") { e ->
            e.sendResponseHeaders(200, 0)
            runCatching { val block = ByteArray(65536); repeat(1000) { e.responseBody.write(block) } }
            e.close()
        }
        http.start()
        try {
            assertThrows(Es9Client.Es9Exception::class.java) {
                Es9Client.Https.post("http://127.0.0.1:${http.address.port}/x", "{}", 5000, 5000, null)
            }
        } finally { http.stop(0) }
    }

    @Test fun aHugeErrorBodyIsCutShortAndStillAnError() {
        val body = "e".repeat(200_000)
        assertEquals(503, server(503, body) { Es9Client.Https.post(it, "{}", 5000, 5000, null) }.code)
    }

    @Test fun aCancelStopsBeforeTheAnswer() {
        val cancel = Es9Client.Cancel().apply { cancel() }
        server(200, "{}") { url -> assertThrows(IOException::class.java) { Es9Client.Https.post(url, "{}", 5000, 5000, cancel) } }
    }
}
