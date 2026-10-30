package com.esimonsim.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** QR codes in pictures, e.g. a screenshot of the provider's email, decoded on the phone with ZXing. */
object QrImage {
    private const val MAX_SIDE = 2560 // keeps phone screenshots full size, halves camera photos
    private val HARDER = mapOf(DecodeHintType.TRY_HARDER to true)

    /** Blocking. Null when the picture holds no readable QR code. */
    fun decode(context: Context, uri: Uri): String? {
        val bmp = bitmap(context, uri) ?: return null
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        bmp.recycle()
        return decode(px, w, h)
    }

    /** The QR code in [w]x[h] pixels, as Bitmap.getPixels gives them (ARGB, not premultiplied). Changes [px]. */
    internal fun decode(px: IntArray, w: Int, h: Int): String? {
        // ZXing ignores alpha, so a transparent background reads as black, like the dark modules: a code saved
        // from an email is often dark on transparent. So try it on white first, then as it is (light codes).
        val raw = RGBLuminanceSource(w, h, px) // keeps its own copy of the pixels' brightness
        return if (flatten(px)) both(RGBLuminanceSource(w, h, px)) ?: both(raw) else both(raw)
    }

    /** Dark-mode emails can show the code light-on-dark, so also try it inverted. */
    private fun both(source: LuminanceSource) = read(source) ?: read(source.invert())

    /** Puts [px] on white, in place. False, with nothing changed, when no pixel is see-through. */
    private fun flatten(px: IntArray): Boolean {
        var any = false
        for (i in px.indices) {
            val p = px[i]
            val a = p ushr 24
            if (a == 0xFF) continue
            any = true
            val white = 0xFF * (0xFF - a)
            val r = ((p shr 16 and 0xFF) * a + white) / 0xFF
            val g = ((p shr 8 and 0xFF) * a + white) / 0xFF
            val b = ((p and 0xFF) * a + white) / 0xFF
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return any
    }

    /** One attempt at a QR code in [source]; [harder] trades speed for small or blurry codes. */
    fun read(source: LuminanceSource, harder: Boolean = true): String? = try {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), if (harder) HARDER else null).text
    } catch (_: ReaderException) { null }

    private fun bitmap(context: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // getPixels() needs a software bitmap
                d.setTargetSampleSize(sample(maxOf(info.size.width, info.size.height)))
            }
        }
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } // sizes only, returns null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sample(maxOf(bounds.outWidth, bounds.outHeight)) }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun sample(longest: Int) = if (longest > MAX_SIDE) (longest + MAX_SIDE - 1) / MAX_SIDE else 1
}
