package com.esimonsim.apdu

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellQuoteTest {
    @Test fun apkPathsRemainOneLiteralShellArgument() {
        assertEquals("'/data/app/a b/base.apk'", AdbApduBackend.shellQuote("/data/app/a b/base.apk"))
        assertEquals("'a'\\''b'", AdbApduBackend.shellQuote("a'b"))
        assertEquals("'\$(id);`id`'", AdbApduBackend.shellQuote("\$(id);`id`"))
    }
}
