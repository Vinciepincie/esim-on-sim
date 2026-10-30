package com.esimonsim.data

import android.content.Context
import android.content.ContextWrapper
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

    @Test fun fingerprintDoesNotContainTheCode() {
        val f = ProfileStore.fingerprint(code("LPA:1\$smdp.example.com\$SECRET42"))
        assertEquals(64, f.length)
        assertFalse(f.contains("SECRET42", ignoreCase = true))
    }
}
