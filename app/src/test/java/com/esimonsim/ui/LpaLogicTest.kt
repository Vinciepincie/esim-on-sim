package com.esimonsim.ui

import com.esimonsim.apdu.AllowNeededException
import com.esimonsim.apdu.ApduException
import com.esimonsim.apdu.DebuggingPortClosedException
import com.esimonsim.apdu.NearbyDevicesNeededException
import com.esimonsim.apdu.PairingRequiredException
import com.esimonsim.apdu.PortNotFoundException
import com.esimonsim.apdu.RootApduBackend
import com.esimonsim.apdu.SetupNeededException
import com.esimonsim.apdu.ShizukuApduBackend
import com.esimonsim.es9.Es9Client
import com.esimonsim.lpa.ConfirmationCodeNeededException
import com.esimonsim.lpa.Lpa
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/** The decisions in LpaViewModel that don't need a phone. The ViewModel itself can't be built on the JVM. */
class LpaLogicTest {
    private fun notice(seq: Int, host: String) = Lpa.Notification(seq, Lpa.Event.ENABLE, host, "8901", "signed")

    @Test fun anAppOnItsWayOutEndsTheCheckAsWellAsTheQuestion() {
        val check = Es9Client.Cancel()
        var asked = false
        stopWaiting({ asked = true }, check).join(5000)
        assertTrue(asked)
        assertTrue(check.cancelled)
    }

    @Test fun anAppOnItsWayOutWithNoCheckRunningStillEndsTheQuestion() {
        var asked = false
        stopWaiting({ asked = true }, null).join(5000)
        assertTrue(asked)
    }

    @Test fun aHostThatFailsIsTriedOnceARunAndTheOthersStillGetTheirs() {
        val tried = ArrayList<Int>()
        val delivered = deliverEach(listOf(notice(1, "dead.example"), notice(2, "live.example"), notice(3, "dead.example"),
            notice(4, "live.example"))) { n -> tried += n.seq; n.address == "live.example" }
        assertEquals(listOf(1, 2, 4), tried)
        assertEquals(listOf(2, 4), delivered.map { it.seq })
    }

    @Test fun aHostIsTheSameHoweverTheCardWritesIt() {
        val tried = ArrayList<Int>()
        deliverEach(listOf(notice(1, "Dead.Example"), notice(2, "dead.example"))) { n -> tried += n.seq; false }
        assertEquals(listOf(1), tried)
    }

    @Test fun aFailedHostGetsAnotherChanceNextRun() {
        val notices = listOf(notice(1, "flaky.example"))
        assertEquals(emptyList<Lpa.Notification>(), deliverEach(notices) { false })
        assertEquals(notices, deliverEach(notices) { true })
    }

    @Test fun noticesLeftAfterASwitchAreTriedAbout5And20And60SecondsLater() {
        assertEquals(listOf(5_000L, 20_000L, 60_000L), NOTICE_RETRIES.runningReduce(Long::plus))
    }

    @Test fun triesStopWhenNothingIsLeft() = runBlocking {
        var tries = 0
        whileLeft(listOf(1L, 1L, 1L)) { ++tries < 2 }
        assertEquals(2, tries)
    }

    @Test fun triesStopAfterTheLastWait() = runBlocking {
        var tries = 0
        whileLeft(listOf(1L, 1L, 1L)) { tries++; true }
        assertEquals(4, tries)
    }

    @Test fun withoutWaitsThereIsOneTry() = runBlocking {
        var tries = 0
        whileLeft(emptyList()) { tries++; true }
        assertEquals(1, tries)
    }

    @Test fun connectingBySelfRetriesOnlyWhatWaitingCanChange() {
        assertTrue(worthRetrying(IOException("Couldn't connect to Wireless debugging."), portMayOpen = false))
        assertTrue(worthRetrying(DebuggingPortClosedException(), portMayOpen = true))
        assertFalse(worthRetrying(DebuggingPortClosedException(), portMayOpen = false))
        for (e in listOf(PairingRequiredException(), SetupNeededException(null), AllowNeededException(asked = false),
            NearbyDevicesNeededException(), PortNotFoundException()))
            assertFalse(e.toString(), worthRetrying(e, portMayOpen = true))
    }

    @Test fun whatOnlyPeopleCanFixEndsTheWaitForARestartingCard() {
        for (e in listOf(PairingRequiredException(), AllowNeededException(asked = false), NearbyDevicesNeededException())) {
            assertTrue(e.toString(), reloadGivesUp(e, restarting = true, ready = false))
            assertTrue(e.toString(), reloadGivesUp(e, restarting = false, ready = true))
        }
    }

    @Test fun otherFailuresAreWaitedOutWhileTheCardRestarts() {
        val lost = ApduException("Card connection lost or timed out. Reconnect and refresh before trying again.")
        assertFalse(reloadGivesUp(lost, restarting = true, ready = false))
        assertFalse(reloadGivesUp(lost, restarting = false, ready = true))
        assertTrue(reloadGivesUp(lost, restarting = false, ready = false))
    }

    /** A clock that moves only when paused, by [pause]'s amount unless [nap] overrides it. */
    private class Clock(var nap: Long? = null) {
        var time = 1000L
        val pauses = ArrayList<Long>()
        fun now() = time
        fun pause(ms: Long) { pauses += ms; time += nap ?: ms }
    }

    @Test fun theFirstAttemptComesWithoutAPause() {
        val clock = Clock()
        fun run() { retryFor(30_000, 1000, clock::now, clock::pause) { return } }
        run()
        assertTrue(clock.pauses.isEmpty())
    }

    @Test fun attemptsRunUntilTheTimeIsUp() {
        val clock = Clock()
        var attempts = 0
        retryFor(3000, 1000, clock::now, clock::pause) { attempts++ }
        assertEquals(4, attempts) // at 0, 1, 2 and 3 s: the last pause began before the limit
        assertEquals(listOf(1000L, 1000L, 1000L), clock.pauses)
    }

    @Test fun anAttemptThatLeavesEndsIt() {
        val clock = Clock()
        var attempts = 0
        fun run(): String {
            retryFor(30_000, 1000, clock::now, clock::pause) { if (++attempts == 3) return "done" }
            return "gave up"
        }
        assertEquals("done", run())
        assertEquals(3, attempts)
    }

    @Test fun aPauseThatOutlastsTheTimeLimitStillGetsOneMoreAttempt() {
        // The app frozen in its pause: on waking, the limit has long passed.
        val clock = Clock(nap = 120_000)
        var attempts = 0
        retryFor(30_000, 1000, clock::now, clock::pause) { attempts++ }
        assertEquals(2, attempts)
    }

    @Test fun onlyRootOrShizukuLeaveWhatTheScreenSaysAboutTheDebuggingPort() {
        for (e in listOf(RootApduBackend.DeniedException(), RootApduBackend.NotRootedException(),
            ShizukuApduBackend.NotRunningException(), ShizukuApduBackend.DeniedException(answered = true)))
            assertTrue(e.toString(), failedOtherWay(e))
        for (e in listOf(IOException("Couldn't connect to Android's debugging tool."), SetupNeededException(null),
            AllowNeededException(asked = false), DebuggingPortClosedException()))
            assertFalse(e.toString(), failedOtherWay(e))
    }

    @Test fun progressOnlyEverGoesUpAndEndsFull() {
        val steps = Lpa.Step.entries.map { overall(it, 0f) } + overall(Lpa.Step.INSTALL, 0.5f) + overall(Lpa.Step.INSTALL, 1f)
        assertEquals(steps.sorted(), steps)
        assertEquals(0f, steps.first())
        assertEquals(0.3f, overall(Lpa.Step.INSTALL, 0f))
        assertEquals(1f, steps.last())
    }

    @Test fun whatTheAppAlreadyWordedKeepsItsWords() {
        for (e in listOf(ApduException("The card is busy."), PairingRequiredException(),
            Es9Client.Es9Exception("The provider refused."), ConfirmationCodeNeededException()))
            assertEquals(e.message, networkMessage(e))
    }

    @Test fun anUnreachableServerSaysWhatToCheck() {
        for (e in listOf(UnknownHostException("smdp.invalid"), ConnectException("refused"),
            IOException("wrapped", UnknownHostException("smdp.invalid"))))
            assertEquals("Couldn't reach the eSIM provider's server. Check your internet connection and try again.", networkMessage(e))
    }

    @Test fun anInstallCanAddThatTheAddressMayBeWrong() {
        assertEquals("Couldn't reach the eSIM provider's server. The address may be wrong.",
            networkMessage(UnknownHostException("x"), reach = "The address may be wrong."))
    }

    @Test fun discoveryKeepsItsOwnServerNameAndAdvice() {
        assertEquals("Couldn't reach the eSIM discovery servers. Check your internet connection and try again.",
            networkMessage(ConnectException("x"), "the eSIM discovery servers"))
    }

    @Test fun noAnswerAndABadConnectionMentionWiFiSignIn() {
        val silent = networkMessage(SocketTimeoutException("x"))
        assertTrue(silent, silent.startsWith("No answer from the eSIM provider's server."))
        assertTrue(silent, silent.contains("open a web page first to sign in"))
        for (e in listOf(SSLException("x"), SSLHandshakeException("x"), IOException("wrapped", SSLException("x")))) {
            val secure = networkMessage(e)
            assertTrue(secure, secure.startsWith("Couldn't make a secure connection to the eSIM provider's server."))
            assertTrue(secure, secure.contains("date and time"))
            assertTrue(secure, secure.contains("open a web page first to sign in"))
        }
    }

    @Test fun aDroppedConnectionIsSaidInPlainWords() {
        for (e in listOf(SocketException("Software caused connection abort"), SocketException("Connection reset"),
            EOFException("unexpected end of stream"), IOException("stream was reset: CANCEL")))
            assertEquals("The connection to the eSIM provider's server dropped. Check your internet connection and try again.",
                networkMessage(e))
    }

    @Test fun anythingUnforeseenHidesItsRawText() {
        for (e in listOf(JSONException("No value for ok"), NumberFormatException("For input string: \"zz\""),
            IllegalStateException("call discover() first"))) {
            val words = networkMessage(e)
            assertEquals("Something went wrong. Try again.", words)
            assertFalse(words, words.contains(e.message!!))
        }
        assertEquals("Check your list.", networkMessage(JSONException("x"), otherwise = "Check your list."))
    }
}
