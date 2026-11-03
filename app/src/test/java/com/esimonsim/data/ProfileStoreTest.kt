package com.esimonsim.data

import android.content.Context
import android.content.ContextWrapper
import com.esimonsim.apdu.AccessMethod
import com.esimonsim.apdu.MemoryPrefs
import com.esimonsim.lpa.ActivationCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileStoreTest {
    private fun code(text: String) = ActivationCode.parse(text)!!

    @Test fun sameCodeHasSameFingerprintHoweverItWasEntered() {
        val qr = ProfileStore.fingerprint(code("LPA:1\$smdp.example.com\$ABC-123"))
        assertEquals(qr, ProfileStore.fingerprint(code("lpa:1\$SMDP.example.com\$abc-123")))
        assertEquals(qr, ProfileStore.fingerprint(ActivationCode.of("https://smdp.example.com/", "ABC-123")!!))
    }

    @Test fun differentCodesDiffer() {
        assertNotEquals(ProfileStore.fingerprint(code("LPA:1\$smdp.example.com\$ABC")),
            ProfileStore.fingerprint(code("LPA:1\$smdp.example.com\$ABD")))
        assertNotEquals(ProfileStore.fingerprint(code("LPA:1\$a.example.com\$ABC")),
            ProfileStore.fingerprint(code("LPA:1\$b.example.com\$ABC")))
    }

    @Test fun aDismissedUsbTipStaysDismissed() {
        val prefs = MemoryPrefs()
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int) = prefs
        }
        assertFalse(ProfileStore(context).usbTipDismissed())
        ProfileStore(context).dismissUsbTip()
        assertTrue(ProfileStore(context).usbTipDismissed())
    }

    @Test fun cardsAreRememberedByEidForEachWayIn() {
        val prefs = MemoryPrefs()
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int) = prefs
        }
        val store = ProfileStore(context)
        assertEquals(null, store.chosenCard())
        store.chooseCard("89049032000000000000000000012345")
        assertEquals("89049032000000000000000000012345", ProfileStore(context).chosenCard())
        store.setCardSlot(AccessMethod.ADB, "89049032000000000000000000012345", 1)
        assertEquals(1, store.cardSlot(AccessMethod.ADB, "89049032000000000000000000012345"))
        // Each way in numbers slots its own way.
        assertEquals(-1, store.cardSlot(AccessMethod.DIRECT, "89049032000000000000000000012345"))
        store.setNoManager(AccessMethod.ADB, 0, "abc")
        assertTrue(store.noManager(AccessMethod.ADB, 0, "abc"))
        assertFalse(store.noManager(AccessMethod.ADB, 0, "abd")) // another SIM there
        assertFalse(store.noManager(AccessMethod.ROOT, 0, "abc"))
        store.clearNoManagers()
        assertFalse(store.noManager(AccessMethod.ADB, 0, "abc"))
    }

    @Test fun fingerprintDoesNotContainTheCode() {
        val f = ProfileStore.fingerprint(code("LPA:1\$smdp.example.com\$SECRET42"))
        assertEquals(64, f.length)
        assertFalse(f.contains("SECRET42", ignoreCase = true))
    }
}
