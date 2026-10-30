package com.esimonsim.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrImageTest {
    private val code = "LPA:1\$smdp.example.com\$ABCD-1234-EFGH-5678"
    private val side = 300

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private val transparent = 0
    private val black = argb(0xFF, 0, 0, 0)
    private val white = argb(0xFF, 0xFF, 0xFF, 0xFF)

    /** The code as Bitmap.getPixels would give it: [module] for the dark modules, [background] around them. */
    private fun pixels(module: Int, background: Int): IntArray {
        val qr = QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, side, side)
        return IntArray(side * side) { if (qr[it % side, it / side]) module else background }
    }

    private fun decode(px: IntArray) = QrImage.decode(px, side, side)

    /** What decoding the pixels as they are, with the inverted pass, gives. */
    private fun asIs(px: IntArray) =
        RGBLuminanceSource(side, side, px).let { QrImage.read(it) ?: QrImage.read(it.invert()) }

    @Test fun darkOnTransparentReadsOnWhite() {
        val px = pixels(black, transparent)
        assertNull(asIs(px)) // transparent reads as black, the same as the modules
        assertEquals(code, decode(px))
    }

    @Test fun greyOnTransparent() {
        assertEquals(code, decode(pixels(argb(0xFF, 0x33, 0x33, 0x33), transparent)))
    }

    @Test fun halfTransparentModulesBlendWithWhite() {
        assertEquals(code, decode(pixels(argb(0x80, 0, 0, 0), transparent)))
    }

    @Test fun lightOnTransparentStillReads() {
        // On white it would vanish; as it is, the inverted pass reads it.
        assertEquals(code, decode(pixels(white, transparent)))
    }

    @Test fun transparentModulesOnWhite() {
        // Cut out of a white square: only the pixels as they are show the modules.
        assertEquals(code, decode(pixels(transparent, white)))
    }

    @Test fun opaqueCodesAsBefore() {
        assertEquals(code, decode(pixels(black, white)))
        assertEquals(code, decode(pixels(white, black))) // a dark-mode email
    }

    @Test fun opaquePixelsAreLeftAlone() {
        val px = pixels(black, white)
        val before = px.copyOf()
        decode(px)
        assertEquals(before.toList(), px.toList())
    }
}
