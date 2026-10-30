package com.esimonsim.lpa

import com.esimonsim.apdu.ApduBackend
import com.esimonsim.apdu.ApduException
import com.esimonsim.apdu.OpenResult
import com.esimonsim.es9.Es9Client
import com.esimonsim.es9.Es9Client.Answer
import com.esimonsim.es9.Es9Client.Companion.b64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

/** The whole download against a scripted card and a scripted provider: real ES10 and real ES9+ JSON. */
class DownloadTest {

    private val eid = "89049032000000000000000000012345"
    private val tx = "00112233445566778899AABBCCDDEEFF"
    private val iccid = "89012345678901234567"
    private val code = ActivationCode("smdp.example.com", "ABC-123")

    private class Call(val host: String, val name: String, val body: JSONObject, val timeoutMs: Int, val readTimeoutMs: Int)

    /** Everything that happened, in order: card commands by first tag, server calls by name. */
    private val log = ArrayList<String>()
    private val commands = ArrayList<ByteArray>()
    private val calls = ArrayList<Call>()

    private fun signed1(host: String) = Tlv.buildSeq(0x30, Tlv.build(0x80, Hex.decode(tx)),
        Tlv.build(0x81, ByteArray(16)), Tlv.build(0x83, host.toByteArray()), Tlv.build(0x84, ByteArray(16)))

    private fun initiate(host: String) = JSONObject().put("transactionId", tx).put("serverSigned1", b64(signed1(host)))
        .put("serverSignature1", b64(Tlv.build(0x5F37, ByteArray(64))))
        .put("euiccCiPKIdToBeUsed", b64(Tlv.build(0x04, ByteArray(20))))
        .put("serverCertificate", b64(Tlv.build(0x30, ByteArray(300)))).toString()

    private fun authClient(ccRequired: Boolean = false) = JSONObject()
        .put("profileMetadata", b64(Tlv.buildSeq(0xBF25, Tlv.build(0x5A, Hex.decode("98103254769810325476")),
            Tlv.build(0x91, "Provider".toByteArray()), Tlv.build(0x92, "Plan".toByteArray()))))
        .put("smdpSigned2", b64(Tlv.buildSeq(0x30, Tlv.build(0x80, Hex.decode(tx)),
            Tlv.build(0x01, byteArrayOf(if (ccRequired) -1 else 0)))))
        .put("smdpSignature2", b64(Tlv.build(0x5F37, ByteArray(64))))
        .put("smdpCertificate", b64(Tlv.build(0x30, ByteArray(300)))).toString()

    private val bpp = Tlv.buildSeq(0xBF36, Tlv.build(0xBF23, ByteArray(4)), Tlv.build(0xA0, ByteArray(3)),
        Tlv.buildSeq(0xA1, Tlv.build(0x88, ByteArray(2))), Tlv.buildSeq(0xA3, Tlv.build(0x86, ByteArray(5))))

    private fun result(finalResult: ByteArray) = Tlv.buildSeq(0xBF37, Tlv.buildSeq(0xBF27, Tlv.build(0x80, Hex.decode(tx)),
        Tlv.buildSeq(0xBF2F, Tlv.build(0x80, byteArrayOf(7)), Tlv.build(0x81, Hex.decode("0780")),
            Tlv.build(0x0C, "smdp.example.com".toByteArray())),
        Tlv.buildSeq(0xA2, finalResult)), Tlv.build(0x5F37, ByteArray(64)))

    private val success = result(Tlv.build(0xA0, ByteArray(0)))
    /** The card's signed answer to CancelSession. */
    private val cancelled = Tlv.buildSeq(0xBF41, Tlv.buildSeq(0xA0, Tlv.build(0x30, ByteArray(4)), Tlv.build(0x5F37, ByteArray(64))))
    private fun failure(reason: Int) = result(Tlv.buildSeq(0xA1, Tlv.build(0x80, byteArrayOf(4)), Tlv.build(0x81, byteArrayOf(reason.toByte()))))

    /** Puts STORE DATA blocks back together and answers whole commands. */
    private inner class Card(val installResult: ByteArray) : ApduBackend {
        private val block = ByteArrayOutputStream()
        var open = 0
        /** Whole replies by command tag, status word included, in place of the usual ones. */
        val answers = HashMap<String, String>()
        override fun isReady() = true
        override fun openChannel(slot: Int, port: Int, aidHex: String) =
            if (slot == 1) { open++; OpenResult(1, 0, null) } else OpenResult(-1, 2, null)
        override fun closeChannel(slot: Int, port: Int, channel: Int) { open-- }
        override fun transmit(slot: Int, port: Int, channel: Int, cla: Int, ins: Int, p1: Int, p2: Int, p3: Int, dataHex: String): String {
            block.write(Hex.decode(dataHex))
            if (p1 and 0x80 == 0) return "9000"
            val command = block.toByteArray()
            block.reset()
            commands += command
            val tag = Hex.encode(command).let { if (it.startsWith("BF")) it.take(4) else it.take(2) }
            log += tag
            answers[tag]?.let { return it }
            return when (tag) {
                "BF3E" -> "BF3E125A10$eid"
                "BF20" -> "BF2003820102"
                "BF2E" -> "BF2E1280100102030405060708090A0B0C0D0E0F10"
                "BF38" -> "BF3803A00100"
                "BF21" -> "BF2103A00100"
                "BF36", "A0", "A1", "88", "A3" -> ""
                "86" -> Hex.encode(installResult)
                "BF30" -> "BF3003800100"
                "BF41" -> Hex.encode(cancelled)
                else -> error("unexpected $tag")
            } + "9000"
        }
    }

    private fun ok(json: String) = Answer(200, json)

    private fun provider(authClient: String = authClient(), bound: ByteArray = bpp, notice: () -> Answer = { Answer(204, "") }):
        (String, String) -> Answer = { host, call ->
        when (call) {
            "initiateAuthentication" -> ok(initiate(host))
            "authenticateClient" -> ok(authClient)
            "getBoundProfilePackage" -> ok(JSONObject().put("boundProfilePackage", b64(bound)).toString())
            "handleNotification" -> notice()
            "cancelSession" -> Answer(200, "{}")
            else -> error("unexpected $call")
        }
    }

    private fun connect(installResult: ByteArray = success, server: (String, String) -> Answer): Pair<Lpa, Card> {
        val card = Card(installResult)
        val lpa = Lpa(card, transport = { url, json, timeoutMs, readTimeoutMs, _ ->
            val host = url.removePrefix("https://").substringBefore('/')
            assertTrue(url, url.startsWith("https://$host/gsma/rsp2/es9plus/"))
            val call = url.substringAfterLast('/')
            log += call
            calls += Call(host, call, JSONObject(json), timeoutMs, readTimeoutMs)
            server(host, call)
        })
        assertEquals(eid, lpa.discover())
        log.clear(); commands.clear(); calls.clear()
        return lpa to card
    }

    private fun command(tag: String) = commands.first { Hex.encode(it).startsWith(tag) }
    private fun ctxParams1(bf38: ByteArray) = Tlv.parse(bf38)!!.children.last().also { assertEquals(0xA0, it.tag) }

    @Test fun aDownloadRunsInOrderAndClearsTheCardsCopyOfTheNotice() {
        val (lpa, card) = connect(server = provider())
        val steps = ArrayList<Lpa.Step>()
        assertEquals(Lpa.DownloadOutcome("Plan", iccid), lpa.downloadProfile(code, null) { step, _ -> steps += step })
        assertEquals(Lpa.Step.entries.toList(), steps.distinct())
        assertEquals(steps.sorted(), steps)
        assertEquals(listOf("BF3E", "BF20", "BF2E", "initiateAuthentication", "BF38", "authenticateClient", "BF21",
            "getBoundProfilePackage", "BF36", "A0", "A1", "88", "A3", "86", "handleNotification", "BF30"), log)
        assertEquals(0, card.open)
    }

    @Test fun theProviderGetsWhatTheCardSaid() {
        val (lpa, _) = connect(server = provider())
        lpa.downloadProfile(code, null) { _, _ -> }
        val init = calls.first { it.name == "initiateAuthentication" }.body
        assertEquals("smdp.example.com", init.getString("smdpAddress"))
        assertEquals(b64(Hex.decode("BF2003820102")), init.getString("euiccInfo1"))
        assertEquals(b64(Hex.decode("0102030405060708090A0B0C0D0E0F10")), init.getString("euiccChallenge"))
        val auth = calls.first { it.name == "authenticateClient" }.body
        assertEquals(tx, auth.getString("transactionId"))
        assertEquals(b64(Hex.decode("BF3803A00100")), auth.getString("authenticateServerResponse"))
        val bound = calls.first { it.name == "getBoundProfilePackage" }.body
        assertEquals(b64(Hex.decode("BF2103A00100")), bound.getString("prepareDownloadResponse"))
        assertEquals(b64(success), calls.first { it.name == "handleNotification" }.body.getString("pendingNotification"))
    }

    @Test fun theCardGetsTheMatchingIdAndThePhonesDetails() {
        val (lpa, _) = connect(server = provider())
        lpa.downloadProfile(code, null) { _, _ -> }
        val bf38 = command("BF38")
        assertTrue("spans STORE DATA blocks", bf38.size > 120)
        // Not bf38.find(0x80): depth first, that is the transaction ID inside serverSigned1.
        val ctx = ctxParams1(bf38)
        assertEquals(0x80, ctx.children[0].tag)
        assertEquals("ABC-123", String(ctx.children[0].value))
        val device = ctx.children[1]
        assertEquals(0xA1, device.tag)
        assertEquals("35290611", Hex.encode(device.children[0].value))
    }

    @Test fun noMatchingIdIsLeftOutRatherThanSentEmpty() {
        val (lpa, _) = connect(server = provider())
        lpa.downloadProfile(ActivationCode("smdp.example.com", ""), null) { _, _ -> }
        assertEquals(0xA1, ctxParams1(command("BF38")).children.single().tag)
    }

    @Test fun aNoticeThatCouldNotBeSentStaysOnTheCard() {
        // Failed on its side, offline, or asked to be tried later.
        for (fail in listOf<() -> Answer>({ Answer(500, "") }, { throw IOException("offline") }, { Answer(408, "") }, { Answer(429, "") })) {
            log.clear()
            val (lpa, _) = connect(server = provider(notice = fail))
            assertEquals(Lpa.DownloadOutcome("Plan", iccid), lpa.downloadProfile(code, null) { _, _ -> })
            assertEquals("handleNotification", log.last())
            assertFalse("BF30" in log)
        }
    }

    @Test fun aNoticeTheProviderRefusedIsNotKept() {
        val (lpa, _) = connect(server = provider(notice = { Answer(404, "") }))
        lpa.downloadProfile(code, null) { _, _ -> }
        assertEquals("BF30", log.last())
    }

    @Test fun anEsimAlreadyOnTheCardStopsBeforeTheCardCommitsToIt() {
        val (lpa, card) = connect(server = provider())
        val e = assertThrows(AlreadyOnCardException::class.java) { lpa.downloadProfile(code, null, setOf(iccid)) { _, _ -> } }
        assertEquals(iccid, e.iccid)
        assertFalse("BF21" in log)
        assertFalse("getBoundProfilePackage" in log)
        assertEquals(listOf("BF41", "cancelSession"), log.takeLast(2)) // the order stays open
        assertEquals(0, card.open)
    }

    private fun events(vararg entries: Pair<String, String>) = ok(JSONObject().put("eventEntries", JSONArray(entries.map { (id, host) ->
        JSONObject().put("eventId", id).put("rspServerAddress", host) })).toString())

    @Test fun aDiscoveryServerHandsTheDownloadOnAndProgressNeverGoesBack() {
        val real = provider()
        val (lpa, card) = connect { host, call ->
            if (host == "ds.example.com" && call == "authenticateClient") events("EV-1" to "smdp.example.com") else real(host, call)
        }
        val steps = ArrayList<Lpa.Step>()
        lpa.downloadProfile(ActivationCode("ds.example.com", "FIRST"), null) { step, _ -> steps += step }
        assertEquals(steps.sorted(), steps)
        assertEquals("smdp.example.com", calls.last().host)
        val bf38s = commands.filter { Hex.encode(it).startsWith("BF38") }
        assertEquals(listOf("FIRST", "EV-1"), bf38s.map { String(ctxParams1(it).children[0].value) })
        assertEquals(0, card.open)
    }

    @Test fun serversThatKeepHandingOnAreGivenUpOn() {
        val real = provider()
        val (lpa, _) = connect { host, call ->
            if (call == "authenticateClient") events("EV" to "next.example.com") else real(host, call)
        }
        val e = assertThrows(ApduException::class.java) { lpa.downloadProfile(code, null) { _, _ -> } }
        assertTrue(e.message!!, "kept passing" in e.message!!)
        assertEquals(3, calls.count { it.name == "authenticateClient" })
    }

    @Test fun aHandoverToNowhereSaysNothingIsWaiting() {
        val real = provider()
        val (lpa, _) = connect { host, call -> if (call == "authenticateClient") events() else real(host, call) }
        val e = assertThrows(ApduException::class.java) { lpa.downloadProfile(code, null) { _, _ -> } }
        assertTrue(e.message!!, "isn't waiting" in e.message!!)
    }

    @Test fun theConfirmationCodeIsHashedTwiceWithTheTransactionId() {
        val (lpa, _) = connect(server = provider(authClient(ccRequired = true)))
        lpa.downloadProfile(code, "1234") { _, _ -> }
        // printf 1234 | sha256sum, then that digest followed by the transaction ID through sha256sum.
        val hash = Tlv.parse(command("BF21"))!!.children.single { it.tag == 0x04 }
        assertEquals("06B387355633C795AA7EC0BBA73682C268C2E9326B649540D7FDB3F866A8761C", Hex.encode(hash.value))
    }

    @Test fun noHashIsSentWhenTheProviderDoesNotAskForACode() {
        val (lpa, _) = connect(server = provider())
        lpa.downloadProfile(code, "1234") { _, _ -> }
        assertTrue(Tlv.parse(command("BF21"))!!.children.none { it.tag == 0x04 })
    }

    @Test fun aCodeTheProviderAsksForLateCancelsCleanly() {
        val (lpa, card) = connect(server = provider(authClient(ccRequired = true)))
        assertThrows(ConfirmationCodeNeededException::class.java) { lpa.downloadProfile(code, null) { _, _ -> } }
        assertFalse("BF21" in log)
        assertFalse("getBoundProfilePackage" in log)
        assertEquals(listOf("BF41", "cancelSession"), log.takeLast(2))
        assertEquals(0, card.open)
    }

    @Test fun aLoadTheCardRefusesCancelsTheSessionAndKeepsTheOrder() {
        val (lpa, card) = connect(server = provider())
        card.answers["86"] = "6A84"
        assertEquals(Lpa.CARD_FULL, assertThrows(ApduException::class.java) { lpa.downloadProfile(code, null) { _, _ -> } }.message)
        // CancelSessionRequest: transaction ID, and reason timeout(2), which leaves the code usable.
        assertEquals("BF41158010${tx}810102", Hex.encode(command("BF41")))
        val sent = calls.single { it.name == "cancelSession" }
        assertEquals(tx, sent.body.getString("transactionId"))
        assertEquals(b64(cancelled), sent.body.getString("cancelSessionResponse"))
        assertEquals(10_000, sent.timeoutMs)
        assertEquals(10_000, sent.readTimeoutMs)
        assertEquals(0, card.open)
    }

    @Test fun aCardThatRefusesToCancelTellsTheServerNothing() {
        // No signature: its session is over already, perhaps because the install finished.
        val (lpa, card) = connect(server = provider())
        card.answers["86"] = "6A84"
        card.answers["BF41"] = "BF41038101059000"
        assertThrows(ApduException::class.java) { lpa.downloadProfile(code, null) { _, _ -> } }
        assertEquals("BF41", log.last())
    }

    @Test fun aCancelThatFailsNeverHidesTheFailure() {
        for (fail in listOf<() -> Unit>({ throw IOException("offline") }, { throw ApduException("Card connection lost") })) {
            val real = provider()
            val (lpa, card) = connect { host, call -> if (call == "cancelSession") { fail(); error("unreached") } else real(host, call) }
            card.answers["86"] = "6A84"
            assertEquals(Lpa.CARD_FULL, assertThrows(ApduException::class.java) { lpa.downloadProfile(code, null) { _, _ -> } }.message)
        }
        val (lpa, card) = connect(server = provider())
        card.answers["86"] = "6A84"
        card.answers["BF41"] = "6F00"
        assertEquals(Lpa.CARD_FULL, assertThrows(ApduException::class.java) { lpa.downloadProfile(code, null) { _, _ -> } }.message)
    }

    @Test fun aServerThatRefusedOrCantBeReachedIsNotAskedToCancel() {
        val refusal = """{"header":{"functionExecutionStatus":{"status":"Failed","statusCodeData":
            {"subjectCode":"8.2","reasonCode":"1.1"}}}}"""
        for (fail in listOf<() -> Answer>({ ok(refusal) }, { throw IOException("offline") })) {
            val real = provider()
            val (lpa, _) = connect { host, call -> if (call == "getBoundProfilePackage") fail() else real(host, call) }
            assertThrows(Exception::class.java) { lpa.downloadProfile(code, null) { _, _ -> } }
            assertFalse("BF41" in log)
            assertFalse("cancelSession" in log)
        }
    }

    @Test fun nothingIsCancelledWhenAllGoesWellOrTheCardReportsTheFailure() {
        val (lpa, _) = connect(server = provider())
        lpa.downloadProfile(code, null) { _, _ -> }
        assertFalse("BF41" in log)
        val (failing, _) = connect(failure(12), provider())
        assertThrows(ApduException::class.java) { failing.downloadProfile(code, null) { _, _ -> } }
        assertFalse("BF41" in log)
    }

    @Test fun aServerTheCardRefusesGetsNoFurther() {
        val refused = Hex.encode(Tlv.buildSeq(0xBF38, Tlv.buildSeq(0xA1, Tlv.build(0x80, Hex.decode(tx)), Tlv.build(0x02, byteArrayOf(1))))) + "9000"
        val (lpa, card) = connect(server = provider())
        card.answers["BF38"] = refused
        val e = assertThrows(Es9Client.Es9Exception::class.java) { lpa.downloadProfile(code, null) { _, _ -> } }
        assertEquals("Your card didn't accept this provider's certificate, so nothing was installed.", e.message)
        assertFalse("authenticateClient" in log)
        assertFalse("BF41" in log)
        assertEquals(0, card.open)
        // A discovery server the same.
        val (check, card2) = connect { host, _ -> ok(initiate(host)) }
        card2.answers["BF38"] = refused
        assertThrows(Es9Client.Es9Exception::class.java) { check.waitingEsims("ds.example.com") }
        assertFalse("authenticateClient" in log)
    }

    @Test fun onlyTheCardsOwnRefusalCounts() {
        val (lpa, _) = connect(server = provider())
        // An accepted answer echoes the phone's details, an A1 of its own further in.
        val accepted = Tlv.buildSeq(0xBF38, Tlv.buildSeq(0xA0, Tlv.buildSeq(0x30, Tlv.buildSeq(0xA1, Tlv.build(0x80, ByteArray(4))))))
        assertFalse(lpa.cardRefusedServer(accepted))
        assertTrue(lpa.cardRefusedServer(Tlv.buildSeq(0xBF38, Tlv.buildSeq(0xA1, Tlv.build(0x02, byteArrayOf(1))))))
        assertFalse(lpa.cardRefusedServer(Tlv.buildSeq(0xBF21, Tlv.build(0xA1, ByteArray(0)))))
        assertFalse(lpa.cardRefusedServer(ByteArray(0)))
    }

    @Test fun noticesGetLittleTimeToConnectToADeadServer() {
        val (lpa, _) = connect(server = provider())
        assertTrue(lpa.deliver(Lpa.Notification(1, Lpa.Event.ENABLE, "smdp.example.com", iccid, "AAAA")))
        assertEquals(10_000, calls.single { it.name == "handleNotification" }.timeoutMs)
    }

    @Test fun aSwitchNoticeThatCouldNotBeSentWaitsForTheNextTry() {
        val (lpa, _) = connect(server = provider(notice = { throw IOException("offline") }))
        assertFalse(lpa.deliver(Lpa.Notification(1, Lpa.Event.DISABLE, "smdp.example.com", iccid, "AAAA")))
    }

    @Test fun whatTheCardOrProviderGotWrongIsSaidInPlainWords() {
        val (lpa, card) = connect(server = provider())
        card.answers["BF2E"] = "9000"
        assertEquals("The card didn't respond properly. Try again.",
            assertThrows(ApduException::class.java) { lpa.downloadProfile(code, null) { _, _ -> } }.message)
        val noTransaction = JSONObject(authClient(ccRequired = true)).put("smdpSigned2", b64(Tlv.buildSeq(0x30, Tlv.build(0x01, byteArrayOf(-1))))).toString()
        val (late, _) = connect(server = provider(noTransaction))
        assertEquals("The provider sent an eSIM this app can't read. Ask your provider for help.",
            assertThrows(ApduException::class.java) { late.downloadProfile(code, "1234") { _, _ -> } }.message)
    }

    @Test fun aReplyMissingWhatItMustHoldIsSaidInPlainWords() {
        val real = provider()
        val (lpa, _) = connect { host, call -> if (call == "getBoundProfilePackage") ok("{}") else real(host, call) }
        assertEquals("The eSIM provider's server sent an incomplete reply. Try again later.",
            assertThrows(Es9Client.Es9Exception::class.java) { lpa.downloadProfile(code, null) { _, _ -> } }.message)
        val (start, _) = connect { _, _ -> ok("{}") }
        assertThrows(Es9Client.Es9Exception::class.java) { start.downloadProfile(code, null) { _, _ -> } }
    }

    @Test fun aFailedInstallIsStillReportedThenExplained() {
        for ((reason, words) in listOf(9 to "already on the card", 11 to "interrupted", 12 to "error 12")) {
            log.clear()
            val (lpa, _) = connect(failure(reason), provider())
            val e = assertThrows(ApduException::class.java) { lpa.downloadProfile(code, null) { _, _ -> } }
            assertTrue(e.message!!, words in e.message!!)
            assertEquals(listOf("handleNotification", "BF30"), log.takeLast(2))
        }
    }

    @Test fun aPackageMissingAPartNeverReachesTheCard() {
        val noA3 = Tlv.buildSeq(0xBF36, Tlv.build(0xBF23, ByteArray(4)), Tlv.build(0xA0, ByteArray(3)),
            Tlv.buildSeq(0xA1, Tlv.build(0x88, ByteArray(2))))
        for (bad in listOf(Tlv.build(0x30, ByteArray(4)), noA3)) {
            log.clear()
            val (lpa, _) = connect(server = provider(bound = bad))
            assertThrows(ApduException::class.java) { lpa.downloadProfile(code, null) { _, _ -> } }
            assertFalse("BF36" in log)
        }
    }

    @Test fun aRefusalKeepsTheProvidersSubjectCode() {
        val refusal = """{"header":{"functionExecutionStatus":{"status":"Failed","statusCodeData":
            {"subjectCode":"8.2.6","reasonCode":"3.8","message":"Refused"}}}}"""
        val (lpa, _) = connect { _, _ -> ok(refusal) }
        val e = assertThrows(Es9Client.Es9Exception::class.java) { lpa.downloadProfile(code, null) { _, _ -> } }
        assertEquals("8.2.6", e.subject)
    }

    @Test fun aReplyThatIsntJsonIsSaidPlainly() {
        // Nested past any stack: Android's parser recurses, and the overflow isn't an Exception.
        for (reply in listOf("<html>Bad gateway</html>", "{\"a\":".repeat(100_000))) {
            val (lpa, _) = connect { _, _ -> ok(reply) }
            val e = assertThrows(Es9Client.Es9Exception::class.java) { lpa.downloadProfile(code, null) { _, _ -> } }
            assertEquals("The eSIM provider's server sent an unexpected reply. Try again later.", e.message)
        }
    }

    @Test fun aDiscoveryServerAnsweringForAnotherAddressIsNotBelieved() {
        val (lpa, _) = connect { _, call -> if (call == "initiateAuthentication") ok(initiate("other.example.com")) else error(call) }
        assertThrows(ApduException::class.java) { lpa.waitingEsims("ds.example.com") }
        assertFalse("BF38" in log)
        assertFalse("authenticateClient" in log)
    }

    @Test fun discoveryListsEventsAndWaitsLessForADeadServer() {
        val (lpa, _) = connect { host, call -> if (call == "initiateAuthentication") ok(initiate(host)) else events("EV-1" to "smdp.example.com") }
        assertEquals(listOf(Lpa.WaitingEsim("smdp.example.com", "EV-1")), lpa.waitingEsims("ds.example.com"))
        assertEquals(setOf(10_000), calls.map { it.timeoutMs }.toSet())
        assertTrue(ctxParams1(command("BF38")).children.none { it.tag == 0x80 })
    }

    @Test fun downloadsKeepTheFullConnectTimeout() {
        val (lpa, _) = connect(server = provider())
        lpa.downloadProfile(code, null) { _, _ -> }
        assertEquals(setOf(30_000), calls.map { it.timeoutMs }.toSet())
    }
}
