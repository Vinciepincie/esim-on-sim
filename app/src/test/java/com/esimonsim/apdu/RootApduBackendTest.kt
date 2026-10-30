package com.esimonsim.apdu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The su side of root access, against a fake su process. */
class RootApduBackendTest {
    private var command = ""

    private fun root(su: () -> Process) = RootApduBackend({ "/data/app/a b/base.apk" }, { command = it; su() })

    @Test fun startsTheHelperFromTheApk() {
        val backend = root { FakeSu(helper = helperAs(0)) }
        backend.connect()
        assertTrue(backend.isReady())
        assertTrue(command, command.startsWith("CLASSPATH='/data/app/a b/base.apk' exec app_process /system/bin "))
    }

    @Test fun noSuIsNotRooted() {
        assertThrows(RootApduBackend.NotRootedException::class.java) {
            root { throw IOException("Cannot run program \"su\": error=13, Permission denied") }.connect()
        }
    }

    @Test fun unansweredQuestionCountsAsRefused() {
        var su: FakeSu? = null
        val backend = root { FakeSu { Thread.sleep(5000); helperAs(0)(it) }.also { su = it } }
        assertThrows(RootApduBackend.DeniedException::class.java) { backend.connect(promptSeconds = 1) }
        assertFalse(backend.isReady())
        assertTrue(su!!.destroyed) // nothing left waiting
    }

    @Test fun anAnswerJustPastTheLimitStillCounts() {
        // A process Android froze wakes after its limit, with the answer about to be read.
        val backend = root { FakeSu { Thread.sleep(1500); helperAs(0)(it) } }
        backend.connect(promptSeconds = 1)
        assertTrue(backend.isReady())
    }

    @Test fun useSimSaysWhenRoamingMayNotLast() {
        var mayReset: Boolean? = true
        val backend = root { FakeSu { q ->
            if (q.getString("op") != "useSim") helperAs(0)(q)
            else JSONObject().put("ok", true).put("applied", true).apply { mayReset?.let { put("roamingMayReset", it) } }
        } }
        backend.connect()
        assertEquals(SimSettingsResult(applied = true, roamingMayReset = true), backend.useSim(0, "8944", data = false, roaming = true))
        mayReset = null // a helper from before this was reported
        assertEquals(SimSettingsResult(applied = true), backend.useSim(0, "8944", data = false, roaming = true))
    }

    @Test fun helperErrorsBecomeWordsPeopleCanActOn() {
        val refused = ApduException.fromHelper("SecurityException: Calling uid 2000 does not have permission").message!!
        assertEquals("Your phone refused this app's request to the card. Restart your phone, then try again.", refused)
        for (raw in listOf("IllegalStateException: The card gave no answer", "NullPointerException: null", "", "DeadObjectException: x"))
            assertEquals("The card didn't answer. Check that it's in the phone, then try again.", ApduException.fromHelper(raw).message)
    }

    @Test fun lostHelperFailsTheRequestNotTheApp() {
        var answers = 0
        val backend = root { FakeSu { q -> if (++answers > 1) null else helperAs(0)(q) } }
        backend.connect()
        val e = assertThrows(ApduException::class.java) { backend.openChannel(1, 0, "A0000005591010FFFFFFFF89000001") }
        assertTrue(e.message!!, e.message!!.startsWith("Card connection lost"))
        assertFalse(backend.isReady())
        assertEquals(2, answers)
    }

    @Test fun helperErrorsAreSaidInPlainWords() {
        var error = ""
        val backend = root { FakeSu { q ->
            if (q.getString("op") == "open") JSONObject().put("ok", false).put("error", error) else helperAs(0)(q)
        } }
        backend.connect()
        val noAnswer = "The card didn't answer. Check that it's in the phone, then try again."
        for ((raw, said) in listOf(
            "DeadObjectException: null" to "Android's SIM service restarted. Try again in a moment.",
            "SecurityException: READ_PRIVILEGED_PHONE_STATE" to
                "Your phone refused this app's request to the card. Restart your phone, then try again.",
            "UnsupportedOperationException: This phone has no known way to reach the card" to
                "This version of Android has no way for this app to reach the card.",
            "IllegalStateException: The card gave no answer" to noAnswer,
            "IllegalStateException: Telephony service unavailable" to noAnswer,
            "NullPointerException: null" to noAnswer,
            "" to noAnswer,
        )) {
            error = raw
            val e = assertThrows(ApduException::class.java) { backend.openChannel(1, 0, "A0000005591010FFFFFFFF89000001") }
            assertEquals(said, e.message)
            assertTrue(backend.isReady()) // a request failed, not the connection: "Try again" uses the same one
        }
    }
}
