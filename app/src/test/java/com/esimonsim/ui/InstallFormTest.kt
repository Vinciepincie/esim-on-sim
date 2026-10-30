package com.esimonsim.ui

import com.esimonsim.lpa.ActivationCode
import com.esimonsim.lpa.EuiccProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallFormTest {
    private val plain = ActivationCode("smdp.example.com", "ABC")
    private val other = ActivationCode("smdp.example.com", "XYZ")

    @Test fun aCodeCanSayItNeedsAConfirmationCode() {
        assertTrue(needsConfirmationCode(plain.copy(ccRequired = true), null))
        assertFalse(needsConfirmationCode(plain, null))
        assertFalse(needsConfirmationCode(null, null))
    }

    @Test fun orTheProviderCanAskForOne() = assertTrue(needsConfirmationCode(plain, asked = plain))

    @Test fun anotherCodeOnTheFormDoesntInheritThat() = assertFalse(needsConfirmationCode(other, asked = plain))

    @Test fun anESimFromTheWaitingListNeedsTheFieldToo() = assertTrue(needsConfirmationCode(null, asked = plain))

    @Test fun closingTheFormAfterTheProviderAskedKeepsWhatsInIt() =
        assertTrue(LpaUi(adding = true).ccAsked(plain).copy(adding = false).keepDraft)

    @Test fun aKeptFormStillWaitsForTheCodeTheProviderAskedFor() {
        val reopened = LpaUi(adding = true).ccAsked(plain).copy(adding = false).addOpened()
        assertTrue(reopened.adding)
        assertEquals(plain, reopened.ccNeeded)
        assertTrue(needsConfirmationCode(plain, reopened.ccNeeded))
    }

    @Test fun aKeptFormIsKeptOnce() = assertFalse(LpaUi(keepDraft = true).addOpened().keepDraft)

    @Test fun aNewFormStartsWithoutAnEarlierAsk() = assertNull(LpaUi(ccNeeded = plain).addOpened().ccNeeded)

    @Test fun onlyACurrentESimCanKeepWorking() {
        val off = EuiccProfile("1", "a", enabled = false, nickname = null, providerName = null, profileName = null, profileClass = 2)
        assertEquals("You need internet for this. Wi-Fi is fine.", installNote(emptyList()))
        assertEquals("You need internet for this. Wi-Fi is fine.", installNote(listOf(off)))
        assertEquals("Your current eSIM keeps working while the new one installs.", installNote(listOf(off, off.copy(enabled = true))))
    }

    @Test fun aBareCodeIsOneWord() {
        assertTrue(isBareCode("ABC-123-XYZ"))
        assertFalse(isBareCode(""))
        assertFalse(isBareCode("just some words here"))
        assertFalse(isBareCode("https://example.invalid/activate?code=123"))
        assertFalse(isBareCode("LPA:1\$smdp"))
    }
}
