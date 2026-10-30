package com.esimonsim.ui

import com.esimonsim.lpa.EuiccProfile
import org.junit.Assert.assertEquals
import org.junit.Test

/** What a screen reader says where the screen shows something else. */
class SpeechTest {
    private val esim = EuiccProfile("8944478612345678901", "a", enabled = false, nickname = "Travel", providerName = "1GLOBAL",
        profileName = null, profileClass = 2)

    @Test fun numbersPeopleTakeDownAreReadDigitByDigit() = assertEquals("8 9 0 4", spokenDigits("8904"))

    @Test fun aListRowOnlyReadsTheLastDigits() {
        assertEquals("Network 1GLOBAL. ICCID ending 8 9 0 1", metaSpeech(esim))
        assertEquals("ICCID ending 8 9 0 1", metaSpeech(esim.copy(providerName = null)))
    }

    @Test fun aStepsStatusNamesTheStep() {
        assertEquals("Developer options: On", stepSpeech("Developer options", "On"))
        assertEquals("On a computer", stepSpeech("On a computer", ""))
    }
}
