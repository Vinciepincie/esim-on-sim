package com.esimonsim.apdu

import com.esimonsim.ui.internetUse
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The About page's internet answer (ui/AboutScreen), here with the other card access tests: it must
 *  stay true for each build's QR scanner. */
class InternetUseTest {
    @Test fun withoutGoogleCodeNothingElseGoesOnline() {
        val text = internetUse("")
        assertTrue(text, text.startsWith("The app only uses the internet to:\n• download an eSIM you add"))
        assertTrue(text, text.endsWith("about your card's EID\nNo account, no tracking."))
    }

    @Test fun aScannerThatReportsIsNamed() {
        val note = "The QR scanner is Google Play services' scanner, which also sends Google usage statistics."
        val text = internetUse(" $note ")
        assertFalse(text, text.contains(" only ") || text.contains("tracking"))
        assertTrue(text, text.startsWith("The app uses the internet to:\n• download an eSIM you add"))
        assertTrue(text, text.endsWith("about your card's EID\n$note No account."))
    }

    @Test fun providersAreToldOnlyWhenTheyAsk() {
        val text = internetUse("")
        assertTrue(text, text.contains("• tell a provider when its eSIM is installed, turned on or off, or deleted, if it asks to"))
    }
}
