package com.esimonsim.ui

import android.app.UiModeManager
import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Theme setting's choices, and what it hands Android (see ThemeSetting). */
class ThemeSettingTest {
    @Test fun everyVersionOffersAllThree() =
        assertEquals(listOf("Same as phone", "Light", "Dark"), ThemeChoice.entries.map { it.label })

    @Test fun sameAsPhoneSaysWhatItFollows() {
        assertEquals("Follows Dark theme in your phone's settings.", ThemeSetting.help(ThemeChoice.PHONE, 29))
        // Android 9 and older have no dark mode of their own, but some phones' makers add one.
        assertEquals("Light, unless your phone has a dark mode of its own.", ThemeSetting.help(ThemeChoice.PHONE, 28))
        assertNull(ThemeSetting.help(ThemeChoice.LIGHT, 34))
        assertNull(ThemeSetting.help(ThemeChoice.DARK, 23))
    }

    @Test fun anOverrideCarriesOnlyTheNightBit() {
        assertNull(ThemeSetting.overrideFor(ThemeChoice.PHONE))
        val dark = ThemeSetting.overrideFor(ThemeChoice.DARK)!!
        assertEquals(Configuration.UI_MODE_NIGHT_YES, dark.uiMode)
        // Undefined, so the phone's text size stays: before Android 8 a new Configuration says 1.
        assertEquals(0f, dark.fontScale, 0f)
        assertEquals(Configuration.UI_MODE_NIGHT_NO, ThemeSetting.overrideFor(ThemeChoice.LIGHT)!!.uiMode)
    }

    @Test fun android12IsHandedTheModeOrNone() {
        // Automatic takes the app's own mode away again.
        assertEquals(UiModeManager.MODE_NIGHT_AUTO, ThemeSetting.nightMode(ThemeChoice.PHONE))
        assertEquals(UiModeManager.MODE_NIGHT_NO, ThemeSetting.nightMode(ThemeChoice.LIGHT))
        assertEquals(UiModeManager.MODE_NIGHT_YES, ThemeSetting.nightMode(ThemeChoice.DARK))
    }
}
