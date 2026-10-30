package com.esimonsim.ui

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What keeps the process alive during card work. The service itself needs a phone. */
class CardWorkTest {
    private class App(val refuses: Boolean = false) : ContextWrapper(null) {
        var starts = 0
        override fun getApplicationContext(): Context = this
        override fun startService(service: Intent?): ComponentName? {
            starts++
            if (refuses) throw IllegalStateException("Not allowed to start service")
            return null
        }
    }

    @After fun finish() = CardWork.end()

    @Test fun theServiceIsAskedForAndSeesWhenTheWorkEnds() {
        val app = App()
        CardWork.begin(app, "Installing eSIM…")
        assertEquals(1, app.starts)
        assertTrue(CardWork.active.value)
        CardWork.end()
        assertFalse(CardWork.active.value)
    }

    @Test fun aServiceAndroidRefusesDoesNotStopTheWork() {
        CardWork.begin(App(refuses = true), "Switching eSIM…")
        assertTrue(CardWork.active.value)
    }

    @Test fun theWatchEndsWithTheWork() = runBlocking {
        CardWork.begin(App(), "Installing eSIM…")
        val watch = async(start = CoroutineStart.UNDISPATCHED) { CardWork.ended() } // waiting by the time the work ends
        CardWork.end()
        withTimeout(5000) { watch.await() }
    }

    @Test fun theWatchEndsByItselfIfTheWorkNeverDoes() = runBlocking {
        // A card call that never returns: below Android 14 nothing else stops the service.
        CardWork.begin(App(), "Installing eSIM…")
        withTimeout(5000) { CardWork.ended(limit = 10) }
        assertTrue(CardWork.active.value) // it stops watching; it doesn't pretend the work ended
    }

    @Test fun endingTwiceIsHarmless() {
        CardWork.begin(App(), "Switching eSIM…")
        CardWork.end()
        CardWork.end()
        assertFalse(CardWork.active.value)
    }
}
