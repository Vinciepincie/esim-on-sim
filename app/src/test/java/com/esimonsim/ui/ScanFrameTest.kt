package com.esimonsim.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class ScanFrameTest {
    private val code = "LPA:1\$smdp.example.com\$ABCD-1234-EFGH-5678\$1.3.6.1.4.1.31746\$1"

    /** A 1280x720 Y plane with padded rows, as cameras hand them out, holding the code in the middle. */
    private fun frame(dark: Int, light: Int, stride: Int = 1344): ByteBuffer {
        val w = 1280
        val h = 720
        val qr = QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, 360, 360)
        val bytes = ByteArray(stride * (h - 1) + w) { light.toByte() } // the last row has no padding
        val left = (w - qr.width) / 2
        val top = (h - qr.height) / 2
        for (y in 0 until qr.height) for (x in 0 until qr.width)
            if (qr[x, y]) bytes[(top + y) * stride + left + x] = dark.toByte()
        return ByteBuffer.wrap(bytes)
    }

    private fun decode(buffer: ByteBuffer, inverted: Boolean): String? {
        val square = centreSquare(buffer, 1344, 1280, 720, ByteArray(0))
        assertEquals(720 * 720, square.size)
        val source = PlanarYUVLuminanceSource(square, 720, 720, 0, 0, 720, 720, false)
        return QrImage.read(if (inverted) source.invert() else source)
    }

    @Test fun decodesTheCentreOfAPaddedFrame() {
        assertEquals(code, decode(frame(dark = 30, light = 220), inverted = false))
    }

    @Test fun lightOnDarkNeedsTheInvertedPass() {
        val buffer = frame(dark = 220, light = 30)
        assertNull(decode(buffer, inverted = false))
        assertEquals(code, decode(buffer, inverted = true))
    }

    @Test fun reusesTheBufferWhenTheSizeFits() {
        val into = ByteArray(720 * 720)
        val out = centreSquare(frame(30, 220), 1344, 1280, 720, into)
        assertEquals(true, out === into)
    }

    /** Where the stretched picture's corners land: left, top, right, bottom, and where its top-left corner went. */
    private fun place(vw: Float, vh: Float, display: Int, sensor: Int = 90): Pair<List<Float>, Pair<Float, Float>> {
        val m = previewMatrix(vw, vh, 1280, 720, sensor, display)
        fun map(x: Float, y: Float) = (m[0] * x + m[1] * y + m[2]) to (m[3] * x + m[4] * y + m[5])
        val corners = listOf(map(0f, 0f), map(vw, 0f), map(0f, vh), map(vw, vh))
        val box = listOf(corners.minOf { it.first }, corners.minOf { it.second }, corners.maxOf { it.first },
            corners.maxOf { it.second })
        return box to corners[0]
    }

    private fun assertCovers(vw: Float, vh: Float, box: List<Float>, shape: Float) {
        val (l, t, r, b) = box
        // Centred, covering the view, one side exactly, and in the picture's own shape.
        assertEquals(vw / 2, (l + r) / 2, 0.5f)
        assertEquals(vh / 2, (t + b) / 2, 0.5f)
        assertEquals(true, l <= 0.5f && t <= 0.5f && r >= vw - 0.5f && b >= vh - 0.5f)
        assertEquals(true, kotlin.math.abs(r - l - vw) < 0.5f || kotlin.math.abs(b - t - vh) < 0.5f)
        assertEquals(shape, (r - l) / (b - t), 0.001f)
    }

    @Test fun portraitPhoneKeepsThePictureUpright() {
        val (box, topLeft) = place(1080f, 2340f, display = 0)
        assertCovers(1080f, 2340f, box, 720f / 1280f)
        assertEquals(box[0], topLeft.first, 0.5f) // still top left
        assertEquals(box[1], topLeft.second, 0.5f)
    }

    @Test fun turnedLeftShowsThePhonesTopOnTheLeft() {
        // ROTATION_90: the phone turned anticlockwise, its top (the picture's top) now on the left.
        val (box, topLeft) = place(2340f, 1080f, display = 90)
        assertCovers(2340f, 1080f, box, 1280f / 720f)
        assertEquals(box[0], topLeft.first, 0.5f) // left, bottom
        assertEquals(box[3], topLeft.second, 0.5f)
    }

    @Test fun turnedRightShowsThePhonesTopOnTheRight() {
        val (box, topLeft) = place(2340f, 1080f, display = 270)
        assertCovers(2340f, 1080f, box, 1280f / 720f)
        assertEquals(box[2], topLeft.first, 0.5f) // right, top
        assertEquals(box[1], topLeft.second, 0.5f)
    }

    @Test fun upsideDownTurnsItRound() {
        val (box, topLeft) = place(1080f, 2340f, display = 180)
        assertCovers(1080f, 2340f, box, 720f / 1280f)
        assertEquals(box[2], topLeft.first, 0.5f) // right, bottom
        assertEquals(box[3], topLeft.second, 0.5f)
    }

    @Test fun landscapeTabletWithAnUnturnedSensor() {
        // A tablet whose natural orientation is landscape, sensor at 0°, in a tall split-screen window.
        val (box, topLeft) = place(800f, 1600f, display = 0, sensor = 0)
        assertCovers(800f, 1600f, box, 1280f / 720f)
        assertEquals(box[0], topLeft.first, 0.5f)
        assertEquals(box[1], topLeft.second, 0.5f)
    }
}
