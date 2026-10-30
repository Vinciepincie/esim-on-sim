package com.esimonsim.ui

import com.esimonsim.adb.DebugSettings
import com.esimonsim.apdu.AccessMethod
import com.esimonsim.lpa.EuiccProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the card screen says to turn on USB debugging before travelling. */
class UsbTipTest {
    private val off = EuiccProfile("1", "a", enabled = false, nickname = "Travel", providerName = null, profileName = null, profileClass = 2)
    private val on = off.copy(iccid = "2", isdpAid = "b", enabled = true)

    /** Connected through the background helper, with Wireless debugging on and USB debugging off. */
    private fun ui(sdk: Int = 33, dev: Boolean = true, usb: Boolean = false, wirelessDebugging: Int = 1,
                   method: AccessMethod = AccessMethod.ADB, background: Boolean = true, profiles: List<EuiccProfile> = listOf(on, off)) =
        LpaUi(settings = DebugSettings.interpret(sdk, dev, usb, wirelessDebugging, 1L, wifi = true), method = method,
            background = background, profiles = profiles)

    @Test fun helperThatWouldStopWithWiFiIsWarnedAbout() = assertTrue(ui().usbTip)

    @Test fun android17HidingTheSettingsIsWarnedAboutToo() = assertTrue(ui(sdk = 37, dev = false).usbTip)

    @Test fun nothingToSayOnceUsbDebuggingIsOn() = assertFalse(ui(usb = true).usbTip)

    @Test fun android10AndOlderHaveNoWirelessDebuggingToLose() = assertFalse(ui(sdk = 29).usbTip)

    @Test fun noHelperNoWarning() = assertFalse(ui(background = false).usbTip)

    @Test fun otherWaysInDontDependOnIt() {
        for (method in listOf(AccessMethod.ROOT, AccessMethod.DIRECT, AccessMethod.SHIZUKU))
            assertFalse(method.name, ui(method = method).usbTip)
    }

    @Test fun aHelperAliveWithWirelessDebuggingOffNeedsNoWarning() = assertFalse(ui(wirelessDebugging = 0).usbTip)

    @Test fun nothingToSwitchToNothingToWarnAbout() {
        assertFalse(ui(profiles = listOf(on)).usbTip)
        assertFalse(ui(profiles = emptyList()).usbTip)
    }
}
