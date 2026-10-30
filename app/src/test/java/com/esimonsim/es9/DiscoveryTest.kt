package com.esimonsim.es9

import com.esimonsim.lpa.Lpa
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

/** eSIMs sent to a card's EID: which discovery servers are asked, and how their answers are read. */
class DiscoveryTest {

    @Test fun theCardsOwnServerGoesFirstAndNoneTwice() {
        assertEquals(listOf("LPA.DS.GSMA.COM", Lpa.GOOGLE_SMDS), Lpa.discoveryServers("LPA.DS.GSMA.COM"))
        assertEquals(listOf("ds.example.com", Lpa.GSMA_SMDS, Lpa.GOOGLE_SMDS), Lpa.discoveryServers("ds.example.com"))
        assertEquals(listOf(Lpa.GSMA_SMDS, Lpa.GOOGLE_SMDS), Lpa.discoveryServers(null))
    }

    @Test fun theGsmaTestServerIsNotAsked() {
        // What a 5ber card names: real eSIMs never wait there, and it doesn't answer.
        assertEquals(listOf(Lpa.GSMA_SMDS, Lpa.GOOGLE_SMDS), Lpa.discoveryServers("testrootsmds.gsma.com"))
        assertEquals(listOf(Lpa.GSMA_SMDS, Lpa.GOOGLE_SMDS), Lpa.discoveryServers(" TestRootSMDS.gsma.com"))
    }

    @Test fun eventsKeepTheirIdAndServer() {
        // As lpa.ds.gsma.com answers ES11 AuthenticateClient (SGP.22 6.5.2.12).
        val r = JSONObject("""{"header":{"functionExecutionStatus":{"status":"Executed-Success"}},
            "transactionId":"0A1B","eventEntries":[{"eventId":"EV-1","rspServerAddress":"smdp.example.com"},
            {"eventId":"EV-2","rspServerAddress":" rsp.example.org "}]}""")
        assertEquals(listOf("EV-1" to "smdp.example.com", "EV-2" to "rsp.example.org"), Es9Client.events(r))
    }

    @Test fun nothingWaitingIsAnEmptyList() {
        assertEquals(emptyList<Pair<String, String>>(), Es9Client.events(JSONObject("""{"eventEntries":[]}""")))
        assertEquals(emptyList<Pair<String, String>>(), Es9Client.events(JSONObject("""{"transactionId":"0A"}""")))
    }

    @Test fun aCancelledCheckDoesNotConnect() {
        val cancel = Es9Client.Cancel().apply { cancel() }
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            assertThrows(IOException::class.java) {
                Es9Client("127.0.0.1:${server.localPort}", cancel = cancel).initiateAuthentication("AA==", "AA==")
            }
            server.soTimeout = 300
            assertThrows(SocketTimeoutException::class.java) { server.accept() }
        }
    }

    @Test fun entriesWithoutAServerAreLeftOut() {
        val r = JSONObject("""{"eventEntries":[{"eventId":"EV-1"},{"eventId":"EV-2","rspServerAddress":"localhost"},
            "junk",{"eventId":"EV-3","rspServerAddress":"smdp.example.com"}]}""")
        assertEquals(listOf("EV-3" to "smdp.example.com"), Es9Client.events(r))
    }
}
