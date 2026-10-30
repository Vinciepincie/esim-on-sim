package com.esimonsim

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.esimonsim.apdu.AdbApduBackend
import com.esimonsim.apdu.ApduException
import com.esimonsim.lpa.Lpa
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real-device tests. Only select/read/close commands; never change profiles. */
@RunWith(AndroidJUnit4::class)
class AdbCardIntegrationTest {
    @Test fun pairedTransportReadsCardAndReconnects() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires explicit real-card test opt-in", args.getString("readCard") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val port = args.getString("connectPort")?.toInt()
        val backend = AdbApduBackend(context)
        try {
            args.getString("pairPort")?.let {
                backend.pair(it.toInt(), requireNotNull(args.getString("pairCode")))
                assertTrue(backend.wasPaired)
            }
            backend.connect(port)
            assertTrue(backend.isReady())
            backend.ping()
            val lpa = Lpa(backend)
            val eid = lpa.discover()
            assertTrue("Expected a 32-digit EID", eid.matches(Regex("[0-9]{32}")))
            val profiles = lpa.getProfiles().map { it.iccid to it.enabled }
            try {
                backend.transmit(lpa.slot, 0, 255, 0, 0xC0, 0, 0, 0, "")
                fail("Helper must reject channels not owned by this session")
            } catch (expected: ApduException) {
                assertTrue(expected.message.orEmpty().contains("not owned"))
            }
            try {
                backend.openChannel(lpa.slot, 0, "A000000151000000")
                fail("Helper must reject unrelated applets")
            } catch (expected: ApduException) {
                assertTrue(expected.message.orEmpty().contains("Unsupported card applet"))
            }
            // Leave a logical channel open, then disconnect: helper EOF must release it.
            val open = backend.openChannel(lpa.slot, 0, Lpa.ISD_R_OPEN)
            assertTrue(open.channel > 0)
            backend.disconnect()
            assertFalse(backend.isReady())
            backend.connect(port)
            assertEquals(eid, lpa.discover())
            assertEquals(profiles, lpa.getProfiles().map { it.iccid to it.enabled })
        } finally { backend.close() }
        // Reconstruct identity from disk, as after app process death.
        AdbApduBackend(context).use {
            it.connect(port)
            assertTrue(it.isReady())
            assertTrue(Lpa(it).discover().matches(Regex("[0-9]{32}")))
        }
    }
}
