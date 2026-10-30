package com.esimonsim.apdu

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/** The Shizuku side of card access, over the same pipe as su, against a fake server. */
class ShizukuApduBackendTest {
    private fun backend(gate: FakeShizuku) = ShizukuApduBackend({ "/data/app/a b/base.apk" }, gate)

    @Test fun startsTheHelperThroughAShellThatOutlivesTheSignal() {
        val gate = FakeShizuku(mayRun = true)
        backend(gate).connect(ask = false)
        assertTrue(gate.command, gate.command.startsWith("CLASSPATH='/data/app/a b/base.apk' app_process /system/bin "))
        // Shizuku's kill has to land on the shell: only stdin ending lets the helper close the card's channels.
        assertFalse(gate.command, gate.command.contains("exec app_process"))
        assertTrue(gate.command, gate.command.endsWith("; exit \$?"))
    }

    @Test fun shizukuRunsAsShellOrRoot() {
        for (uid in listOf(2000, 0)) {
            val shizuku = backend(FakeShizuku(mayRun = true, process = { FakeSu(helper = helperAs(uid)) }))
            shizuku.connect(ask = false)
            assertTrue(shizuku.isReady())
        }
    }

    @Test fun anyOtherHelperIsRefused() {
        for (uid in listOf(1000, 10123)) {
            var su: FakeSu? = null
            val shizuku = backend(FakeShizuku(mayRun = true, process = { FakeSu(helper = helperAs(uid)).also { su = it } }))
            assertThrows(ShizukuApduBackend.StartException::class.java) { shizuku.connect(ask = false) }
            assertFalse(shizuku.isReady())
            assertTrue(su!!.destroyed)
        }
    }

    @Test fun stoppedShizukuStartsNothing() {
        val gate = FakeShizuku(running = false)
        assertThrows(ShizukuApduBackend.NotRunningException::class.java) { backend(gate).connect(ask = true) }
        assertEquals(0, gate.asks)
        assertEquals(0, gate.starts)
    }

    @Test fun nothingStartsBeforeItsAllowed() {
        val gate = FakeShizuku()
        val e = assertThrows(ShizukuApduBackend.DeniedException::class.java) { backend(gate).connect(ask = false) }
        assertFalse(e.answered)
        assertEquals(0, gate.asks)
        assertEquals(0, gate.starts)
    }

    @Test fun alreadyAllowedIsntAskedAgain() {
        val gate = FakeShizuku(mayRun = true)
        backend(gate).connect(ask = true)
        assertEquals(0, gate.asks)
    }

    @Test fun yesStartsTheHelper() {
        val gate = FakeShizuku()
        val shizuku = backend(gate)
        shizuku.connect(ask = true)
        assertEquals(1, gate.asks)
        assertTrue(shizuku.isReady())
    }

    @Test fun noIsADecisionAndSilenceIsNot() {
        val gate = FakeShizuku(answer = false)
        val no = assertThrows(ShizukuApduBackend.DeniedException::class.java) { backend(gate).connect(ask = true) }
        assertTrue(no.answered)
        assertEquals("Shizuku didn't allow this app.", no.message)
        gate.answer = null
        val silence = assertThrows(ShizukuApduBackend.DeniedException::class.java) { backend(gate).connect(ask = true) }
        assertFalse(silence.answered)
        assertTrue(silence.message!!, silence.message!!.startsWith("No answer from Shizuku."))
        assertEquals(0, gate.starts)
    }

    @Test fun everyTryAsksAgain() {
        // A question may be stuck behind this app, where nobody can answer it.
        val gate = FakeShizuku(answer = null)
        val shizuku = backend(gate)
        assertThrows(ShizukuApduBackend.DeniedException::class.java) { shizuku.connect(ask = true) }
        assertThrows(ShizukuApduBackend.DeniedException::class.java) { shizuku.allow() }
        assertEquals(2, gate.asks)
    }

    @Test fun serverStoppingDuringTheQuestionSaysSo() {
        val gate = object : ShizukuApduBackend.Gate by FakeShizuku() {
            var up = true
            override val running get() = up
            override fun ask(seconds: Long): Boolean? { up = false; return null }
        }
        assertThrows(ShizukuApduBackend.NotRunningException::class.java) {
            ShizukuApduBackend({ "x" }, gate).connect(ask = true)
        }
    }

    @Test fun permissionTakenBackCountsAsNotAllowed() {
        val gate = FakeShizuku(mayRun = true, process = { throw SecurityException("Permission Denial: newProcess") })
        assertFalse(assertThrows(ShizukuApduBackend.DeniedException::class.java) { backend(gate).connect(ask = true) }.answered)
    }

    @Test fun whateverElseGoesWrongRulesNothingOut() {
        for (failure in listOf(IllegalStateException("exec failed"), RuntimeException("DeadObjectException"), IOException("null"))) {
            val gate = FakeShizuku(mayRun = true, process = { throw failure })
            val e = assertThrows(ShizukuApduBackend.StartException::class.java) { backend(gate).connect(ask = true) }
            assertEquals(failure, e.cause)
        }
    }

    @Test fun helperThatExitsAtOnceFailsTheStart() {
        val gate = FakeShizuku(mayRun = true, process = { FakeSu(exitAtOnce = true) { null } })
        assertThrows(ShizukuApduBackend.StartException::class.java) { backend(gate).connect(ask = false) }
    }

    @Test fun allowingAloneStartsNothing() {
        val gate = FakeShizuku()
        val shizuku = backend(gate)
        shizuku.allow()
        assertTrue(gate.allowed)
        assertEquals(0, gate.starts)
        assertFalse(shizuku.isReady())
        shizuku.allow()
        assertEquals(1, gate.asks) // no second question
    }

    @Test fun lostHelperFailsTheRequestNotTheApp() {
        var answers = 0
        val shizuku = backend(FakeShizuku(mayRun = true, process = { FakeSu { q -> if (++answers > 1) null else helperAs(2000)(q) } }))
        shizuku.connect(ask = false)
        val e = assertThrows(ApduException::class.java) { shizuku.openChannel(1, 0, "A0000005591010FFFFFFFF89000001") }
        assertTrue(e.message!!, e.message!!.startsWith("Card connection lost"))
        assertFalse(shizuku.isReady())
    }

    @Test fun helperErrorsAreSaidInPlainWords() {
        val shizuku = backend(FakeShizuku(mayRun = true, process = { FakeSu { q ->
            if (q.getString("op") == "open") JSONObject().put("ok", false).put("error", "DeadObjectException: null") else helperAs(2000)(q)
        } }))
        shizuku.connect(ask = false)
        val e = assertThrows(ApduException::class.java) { shizuku.openChannel(1, 0, "A0000005591010FFFFFFFF89000001") }
        assertEquals("Android's SIM service restarted. Try again in a moment.", e.message)
        assertTrue(shizuku.isReady())
    }

    @Test fun deadServerDoesNotEscapeClosing() {
        val shizuku = backend(FakeShizuku(mayRun = true, process = { FakeSu(serverGone = true, helper = helperAs(2000)) }))
        shizuku.connect(ask = false)
        shizuku.disconnect()
        assertFalse(shizuku.isReady())
    }

    @Test fun deadServerDoesNotCrashTheErrorReader() {
        val uncaught = AtomicReference<Throwable?>()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.set(e) }
        try {
            val shizuku = backend(FakeShizuku(mayRun = true, process = { FakeSu(serverGone = true, helper = helperAs(2000)) }))
            shizuku.connect(ask = false)
            Thread.sleep(100)
            shizuku.disconnect()
            assertNull(uncaught.get())
        } finally { Thread.setDefaultUncaughtExceptionHandler(before) }
    }

    @Test fun simSettingsGoThroughTheHelper() {
        var asked: JSONObject? = null
        val shizuku = backend(FakeShizuku(mayRun = true, process = { FakeSu { q -> if (q.getString("op") == "useSim") asked = q; helperAs(2000)(q) } }))
        shizuku.connect(ask = false)
        assertTrue(shizuku.useSim(1, "8944", data = true, roaming = false).applied)
        assertEquals("8944", asked!!.getString("iccid"))
    }
}
