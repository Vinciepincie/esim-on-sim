package com.esimonsim.lpa

import com.esimonsim.es9.Es9Client
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Asn1Test {

    @Test fun hexRoundTrips() {
        val b = byteArrayOf(0, 0x7F, 0x80.toByte(), 0xFF.toByte())
        assertEquals("007F80FF", Hex.encode(b))
        assertArrayEquals(b, Hex.decode("007f80ff"))
        assertArrayEquals(byteArrayOf(0xAB.toByte(), 0xCD.toByte()), Hex.decode("AB CD\n"))
    }

    @Test fun bcdKeepsFillerNibbleAsF() {
        // 19-digit ICCID, nibble-swapped with an F pad (it used to render as "...03715").
        assertEquals("8944478600000000037F", Hex.bcd(Hex.decode("984474680000000030F7"), swap = true))
        assertEquals("89049032000000000000000000012345",
            Hex.bcd(Hex.decode("89049032000000000000000000012345"), swap = false))
    }

    @Test fun lengthsUseShortAndLongForm() {
        assertEquals("8001FF", Hex.encode(Tlv.build(0x80, byteArrayOf(-1))))
        assertEquals("048181", Hex.encode(Tlv.build(0x04, ByteArray(0x81))).take(6))
        assertEquals("BF2D820100", Hex.encode(Tlv.build(0xBF2D, ByteArray(0x100))).take(10))
        assertEquals(300, Tlv.parse(Tlv.build(0x04, ByteArray(300)))!!.value.size)
    }

    @Test fun parseFindsNestedTags() {
        val e3 = Tlv.buildSeq(0xE3, Tlv.build(0x5A, byteArrayOf(1)), Tlv.build(0x9F70, byteArrayOf(1)))
        val root = Tlv.parse(Tlv.build(0xBF2D, Tlv.buildSeq(0xA0, e3, e3)))!!
        assertEquals(0xBF2D, root.tag)
        assertEquals(2, root.findAll(0xE3).size)
        assertEquals(1, root.find(0x9F70)!!.value[0].toInt())
        assertNull(root.find(0x4F))
    }

    @Test fun truncatedInputIsIgnored() {
        assertTrue(Tlv.parseList(Hex.decode("BF2D05A003")).isEmpty())
    }

    @Test fun lengthsThatAreNotOnesStopTheParse() {
        // 0484FFFFFFFA used to overflow to a negative length and run the reader backwards, for ever.
        for (hex in listOf("0484FFFFFFFA", "0484FFFFFFFF", "04847FFFFFFF", "0488000000010000000000", "0482", "3080020101"))
            assertTrue(hex, Tlv.parseList(Hex.decode(hex)).isEmpty())
        // What comes before a bad one is kept, as with truncated data.
        assertEquals(listOf(0x80), Tlv.parseList(Hex.decode("8001FF0484FFFFFFFA")).map { it.tag })
    }

    @Test fun longFormLengthsStillWork() {
        val big = Hex.decode("0483011170") + ByteArray(70_000)
        assertEquals(70_000, Tlv.parse(big)!!.value.size)
        assertEquals(1, Tlv.parseList(Hex.decode("0400")).size)
    }

    @Test fun deepNestingStopsAtALimit() {
        // Far deeper than any stack; each header goes in front, so the test itself stays linear.
        val levels = 50_000
        val nest = java.io.ByteArrayOutputStream()
        for (i in 0 until levels) nest.write(header(6 * (levels - i - 1)))
        var node = Tlv.parse(nest.toByteArray())!!
        var depth = 0
        while (node.children.isNotEmpty()) { node = node.children.single(); depth++ }
        assertTrue("$depth levels", depth in 5..64)
    }

    /** 30 and a long-form length: the start of a constructed TLV holding [size] bytes. */
    private fun header(size: Int) = byteArrayOf(0x30, 0x84.toByte(), (size shr 24).toByte(), (size shr 16).toByte(),
        (size shr 8).toByte(), size.toByte())

    @Test fun anInstallResultIsWithinTheLimit() {
        val result = Tlv.buildSeq(0xBF37, Tlv.buildSeq(0xBF27, Tlv.buildSeq(0xA2, Tlv.buildSeq(0xA1,
            Tlv.build(0x80, byteArrayOf(4)), Tlv.build(0x81, byteArrayOf(10))))))
        assertEquals(10, Tlv.parse(result)!!.find(0xA1)!!.find(0x81)!!.value[0].toInt())
    }

    @Test fun rawTlvKeepsOffsets() {
        val buf = Tlv.build(0xA1, Tlv.build(0x88, ByteArray(2))) // A1 04 88 02 00 00
        val r = RawTlv.read(buf, 0, buf.size).single()
        assertEquals(listOf(0xA1, 0, 2, 6), listOf(r.tag, r.start, r.valueStart, r.end))
    }

    @Test fun displayNameFallsBackThroughFields() {
        val p = EuiccProfile("8944", "A0", false, nickname = " ", providerName = "Truphone", profileName = "", profileClass = 2)
        assertEquals("Truphone", p.displayName)
        assertEquals("ICCID 8944", p.copy(providerName = null).displayName)
    }

    @Test fun aSpecialESimsKindIsNamedInPlainWords() {
        val p = EuiccProfile("8944", "A0", false, nickname = null, providerName = null, profileName = null, profileClass = 0)
        assertEquals("Test", p.classLabel)
        assertEquals("Setup", p.copy(profileClass = 1).classLabel)
    }

    // ---- activation codes ----

    @Test fun parsesQrText() {
        val ac = ActivationCode.parse("LPA:1\$rsp.truphone.com\$5C-25MMPY-193YHU8")!!
        assertEquals(ActivationCode("rsp.truphone.com", "5C-25MMPY-193YHU8"), ac)
        assertFalse(ac.ccRequired)
    }

    @Test fun toleratesMissingPrefixCaseAndLineBreaks() {
        val want = ActivationCode("smdp.example.com", "ABC")
        assertEquals(want, ActivationCode.parse("1\$smdp.example.com\$ABC"))
        assertEquals(want, ActivationCode.parse(" lpa:1\$smdp.example.com\$A\nBC "))
    }

    @Test fun readsConfirmationCodeFlagAndEmptyMatchingId() {
        val cc = ActivationCode.parse("LPA:1\$smdp.example.com\$ABC\$1.3.6.1.4.1.31746\$1")!!
        assertTrue(cc.ccRequired)
        assertEquals(cc, ActivationCode.parse(cc.toString())) // canonical form keeps the flag
        assertEquals("", ActivationCode.parse("LPA:1\$smdp.example.com\$")!!.matchingId)
    }

    @Test fun findsCodeInsideLinksAndLabels() {
        val want = ActivationCode("smdp.example.com", "ABC-123")
        assertEquals(want, ActivationCode.parse(
            "https://esimsetup.apple.com/esim_qrcode_provisioning?carddata=LPA:1\$smdp.example.com\$ABC-123"))
        assertEquals(want, ActivationCode.parse(
            "https://example.com/install?carddata=LPA%3A1%24smdp.example.com%24ABC-123&x=1"))
        assertEquals(want, ActivationCode.parse("Activation code: LPA:1\$smdp.example.com\$ABC-123"))
    }

    @Test fun findsCodeInSharedTextAndLinks() {
        val want = ActivationCode("smdp.example.com", "ABC-123")
        // A shared email: the words after the code must not join it (parse() drops all whitespace).
        assertEquals(want, ActivationCode.find("Your eSIM:\nLPA:1\$smdp.example.com\$ABC-123\nThanks for your order"))
        assertEquals(want, ActivationCode.find("lpa:1\$smdp.example.com\$ABC-123"))
        assertEquals(want, ActivationCode.find("lpa:1%24smdp.example.com%24ABC-123")) // a link with its $ encoded
        assertEquals(want, ActivationCode.find(
            "https://esimsetup.apple.com/esim_qrcode_provisioning?carddata=LPA:1\$smdp.example.com\$ABC-123"))
        assertEquals(want, ActivationCode.find("<a href=\"LPA:1\$smdp.example.com\$ABC-123\">Install</a>"))
        assertEquals(ActivationCode("smdp.example.com", "A+B"), ActivationCode.find("LPA:1%24smdp.example.com%24A+B"))
        assertEquals(want, ActivationCode.find(" 1\$smdp.example.com\$ABC-123 ")) // a bare code still works
        // Sentence punctuation and chat markup around a code.
        assertEquals(want, ActivationCode.find("Your code is LPA:1\$smdp.example.com\$ABC-123."))
        assertEquals(want, ActivationCode.find("*LPA:1\$smdp.example.com\$ABC-123*"))
        assertEquals(want, ActivationCode.find("(LPA:1\$smdp.example.com\$ABC-123), valid for 30 days"))
        assertNull(ActivationCode.find("Thanks for your order"))
    }

    @Test fun wordsAfterACodeNeverJoinIt() {
        val want = ActivationCode("smdp.example.com", "ABC-123")
        for (after in listOf("\nThanks for your order", " is your code.", "\n\$12.99 paid", "\nICCID\n8944", "\r\nThanks"))
            assertEquals(after, want, ActivationCode.find("Activation code: LPA:1\$smdp.example.com\$ABC-123$after"))
    }

    @Test fun aLineBreakIsMendedOnlyWhereItCanBeNothingElse() {
        val want = ActivationCode("smdp.example.com", "ABC-123")
        assertEquals(want, ActivationCode.find("LPA:1\$smdp.example.com\$ABC-\n123")) // after a hyphen
        assertEquals(want, ActivationCode.find("LPA:1\$smdp.example.com\$ABC-\r\n  123."))
        assertEquals(ActivationCode("smdp.example.com", "AB-CD-EF"), ActivationCode.find("LPA:1\$smdp.example.com\$AB-\nCD-\nEF"))
        assertEquals(want, ActivationCode.find("LPA:1\$smdp.example.com\n\$ABC-123")) // before the second "$"
        assertEquals(ActivationCode("rsp.truphone.com", "ABC"), ActivationCode.find("LPA:1\$rsp.tru\nphone.com\$ABC")) // inside the host
        // A plain wrap looks just like one word on the next line.
        assertEquals(ActivationCode("smdp.example.com", "ABC"), ActivationCode.find("LPA:1\$smdp.example.com\$ABC\nDEF"))
        // A gap is a new paragraph, whatever's before it.
        assertEquals(ActivationCode("smdp.example.com", "ABC-"), ActivationCode.find("LPA:1\$smdp.example.com\$ABC-\n\nThanks"))
    }

    @Test fun aPlusInALinkStaysAPlus() {
        val link = "https://example.com/i?carddata=LPA:1%24smdp.example.com%24A+B"
        val want = ActivationCode("smdp.example.com", "A+B")
        assertEquals(want, ActivationCode.parse(link))
        assertEquals(want, ActivationCode.find(link))
    }

    @Test fun rejectsNonCodes() {
        for (s in listOf("", "hello", "ABC-123", "LPA:2\$smdp.example.com\$X", "LPA:1\$\$X", "LPA:1\$a.com/x\$Y",
            "LPA:1\$smdp.example.com@evil.example\$X", "LPA:1\$smdp.example.com:443@evil.example\$X",
            "LPA:1\$smdp.example.com?evil.example\$X", "LPA:1\$smdp.example.com#evil.example\$X",
            "LPA:1\$smdp.example.com\\evil.example\$X"))
            assertNull(s, ActivationCode.parse(s))
        assertEquals("smdp.example.com:8443", ActivationCode.of("smdp.example.com:8443", "X")!!.smdp) // a port is fine
    }

    @Test fun buildsFromManualFields() {
        assertEquals(ActivationCode("smdp.example.com", "ABC-123"),
            ActivationCode.of(" https://smdp.example.com/ ", " ABC-123 "))
        assertNull(ActivationCode.of("", "ABC"))
        assertNull(ActivationCode.of("smdp.example.com", "A\$B"))
    }

    @Test fun es9RejectionsAreExplained() {
        assertTrue(Es9Client.describe("8.2.6", "3.8").startsWith("The provider refused this activation code"))
        assertEquals("The card doesn't have room for this eSIM. Delete an eSIM you no longer use, then try again.",
            Es9Client.describe("8.1", "4.8"))
        assertEquals("The eSIM provider refused the request. Ask your provider for help and quote this code.",
            Es9Client.describe("8.9", "1.1"))
        assertArrayEquals(byteArrayOf(1, 2, 3), Es9Client.unb64("AQ\nID"))
    }
}
