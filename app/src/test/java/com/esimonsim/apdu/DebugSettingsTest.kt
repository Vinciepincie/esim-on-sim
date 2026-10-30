package com.esimonsim.apdu

import com.esimonsim.adb.DebugSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the phone's settings mean for card access, by Android version: Wireless debugging from 11, the computer's
 *  debugging port before that, and Android 17 hiding two of the settings from apps. */
class DebugSettingsTest {
    private fun read(sdk: Int, dev: Boolean = true, usb: Boolean = false, adbWifi: Int? = 0, allowedTime: Long? = 1L,
                     wifi: Boolean = true) = DebugSettings.interpret(sdk, dev, usb, adbWifi, allowedTime, wifi)

    @Test fun android11To16ReadAsBefore() {
        val on = read(36, adbWifi = 1, usb = true)
        assertEquals(DebugSettings.State(true, true, true, usbDebugging = true, pairingExpires = true), on)
        assertTrue(on.mayConnect)
        // Wireless debugging needs Wi-Fi (or to be on already), and goes off with Developer options.
        assertFalse(read(30, adbWifi = 0).mayConnect)
        assertTrue(read(30, adbWifi = 1, wifi = false).mayConnect) // on: Wi-Fi is there, even if not seen yet
        assertFalse(read(30, adbWifi = null, wifi = false).mayConnect)
        val devOff = read(33, dev = false, adbWifi = 1)
        assertEquals(false, devOff.wirelessDebugging)
        assertFalse(devOff.mayConnect)
        // An OEM build that won't say: worth trying on Wi-Fi.
        assertNull(read(31, adbWifi = null).wirelessDebugging)
        assertTrue(read(31, adbWifi = null).mayConnect)
        assertFalse(read(31, allowedTime = 0L).pairingExpires)
    }

    @Test fun android10AndOlderNeedUsbDebuggingNotWifi() {
        val s = read(29, usb = true, adbWifi = 0, wifi = false)
        assertTrue(s.legacy)
        assertNull(s.wirelessDebugging) // there is none
        assertTrue(s.mayConnect)
        assertFalse(read(29, usb = false).mayConnect)
        assertFalse(read(23, dev = false, usb = true).mayConnect) // USB debugging goes off with Developer options
        assertEquals(false, read(23, dev = false, usb = true).usbDebugging)
        // Android 10 forgets allowed keys after 7 days; 9 and older keep them.
        assertTrue(read(29, usb = true).pairingExpires)
        assertFalse(read(29, usb = true, allowedTime = 0L).pairingExpires)
        assertFalse(read(28, usb = true).pairingExpires)
        assertFalse(read(23, usb = true).pairingExpires)
    }

    @Test fun android17HiddenSettingsAreNotTakenForOff() {
        // Wireless debugging on: Developer options must be on, whatever Android shows; USB debugging can't be told.
        val wd = read(37, dev = false, usb = false, adbWifi = 1)
        assertEquals(true, wd.developerOptions)
        assertEquals(true, wd.wirelessDebugging)
        assertNull(wd.usbDebugging)
        assertTrue(wd.mayConnect)
        // Both 0 and Wireless debugging off: Developer options off, or only Wireless debugging. Can't tell which.
        val off = read(37, dev = false, usb = false, adbWifi = 0)
        assertNull(off.developerOptions)
        assertEquals(false, off.wirelessDebugging)
        assertNull(off.usbDebugging)
        assertFalse(off.mayConnect)
        // A 1 is never hidden: then both are real.
        val shown = read(37, dev = true, usb = false, adbWifi = 0)
        assertEquals(true, shown.developerOptions)
        assertEquals(false, shown.usbDebugging)
        assertEquals(true, read(37, dev = true, usb = true, adbWifi = 1).usbDebugging)
        // The same zeros on Android 16 still mean off.
        assertEquals(false, read(36, dev = false, usb = false, adbWifi = 0).developerOptions)
    }
}
