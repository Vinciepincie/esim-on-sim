package com.esimonsim.lpa

/**
 * Minimal BER-TLV reader/writer sufficient for the SGP.22 ES10 commands we use.
 * Tags are represented as Int (1- or 2-byte tags, e.g. 0x5A, 0xBF3E, 0x9F70).
 */
class Tlv(val tag: Int, val value: ByteArray, val children: List<Tlv>) {

    /** Depth-first search for the first descendant with [tag]. */
    fun find(tag: Int): Tlv? {
        for (c in children) (if (c.tag == tag) c else c.find(tag))?.let { return it }
        return null
    }

    fun findAll(tag: Int): List<Tlv> = ArrayList<Tlv>().also { collect(tag, it) }

    private fun collect(tag: Int, out: MutableList<Tlv>) {
        for (c in children) { if (c.tag == tag) out += c; c.collect(tag, out) }
    }

    companion object {
        /** Well past the deepest structure SGP.22 has (an install result is 5 levels). Servers choose these
         *  bytes, before anything has checked them, and each level costs stack and a copy of the value. */
        private const val MAX_DEPTH = 16

        /** Parse a sequence of TLVs, recursing into constructed ones, [MAX_DEPTH] levels down at most. */
        fun parseList(data: ByteArray, start: Int = 0, end: Int = data.size, depth: Int = 0): List<Tlv> =
            RawTlv.read(data, start, end).map { r ->
                val value = data.copyOfRange(r.valueStart, r.end)
                val constructed = data[r.start].toInt() and 0x20 != 0
                Tlv(r.tag, value, if (constructed && depth < MAX_DEPTH) parseList(value, depth = depth + 1) else emptyList())
            }

        fun parse(data: ByteArray): Tlv? = parseList(data).firstOrNull()

        // --- builders ---
        private fun tagBytes(tag: Int): ByteArray = when {
            tag > 0xFFFF -> byteArrayOf((tag shr 16).toByte(), (tag shr 8).toByte(), tag.toByte())
            tag > 0xFF -> byteArrayOf((tag shr 8).toByte(), tag.toByte())
            else -> byteArrayOf(tag.toByte())
        }

        private fun lenBytes(len: Int): ByteArray = when {
            len < 0x80 -> byteArrayOf(len.toByte())
            len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
            else -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), len.toByte())
        }

        /** Build one TLV with raw [value]. */
        fun build(tag: Int, value: ByteArray): ByteArray = tagBytes(tag) + lenBytes(value.size) + value

        /** Build a constructed TLV whose value is the concatenation of [children]. */
        fun buildSeq(tag: Int, vararg children: ByteArray): ByteArray =
            build(tag, children.fold(ByteArray(0), ByteArray::plus))
    }
}

/**
 * Offset-preserving TLV node: keeps exact byte ranges into the original buffer,
 * so segments can be sliced byte-for-byte (critical for SCP03t-protected BPP
 * elements, whose bytes must not be re-encoded).
 */
class RawTlv(val tag: Int, val start: Int, val valueStart: Int, val end: Int) {
    companion object {
        /** Read the sequence of TLVs in buf[from, to). One level deep; stops at truncated data, or a length
         *  that isn't one: indefinite (SGP.22 is DER), or too long for an Int, which would run the reader backwards. */
        fun read(buf: ByteArray, from: Int, to: Int): List<RawTlv> {
            val out = ArrayList<RawTlv>()
            var i = from
            while (i < to) {
                val start = i
                var tag = buf[i++].toInt() and 0xFF
                if (tag and 0x1F == 0x1F) {
                    while (i < to) {
                        val b = buf[i++].toInt() and 0xFF
                        tag = (tag shl 8) or b
                        if (b and 0x80 == 0) break
                    }
                }
                if (i >= to) break
                var len = buf[i++].toInt() and 0xFF
                if (len and 0x80 != 0) {
                    var n = len and 0x7F
                    if (n == 0 || n > 4 || n > to - i) break
                    len = 0
                    while (n-- > 0) len = (len shl 8) or (buf[i++].toInt() and 0xFF)
                    if (len < 0) break
                }
                if (len > to - i) break
                val end = i + len
                out += RawTlv(tag, start, i, end)
                i = end
            }
            return out
        }

        fun find(buf: ByteArray, from: Int, to: Int, tag: Int): RawTlv? =
            read(buf, from, to).firstOrNull { it.tag == tag }
    }
}

object Hex {
    private const val DIGITS = "0123456789ABCDEF"

    fun encode(b: ByteArray): String {
        val c = CharArray(b.size * 2)
        for (i in b.indices) {
            val x = b[i].toInt()
            c[2 * i] = DIGITS[x shr 4 and 0xF]
            c[2 * i + 1] = DIGITS[x and 0xF]
        }
        return String(c)
    }

    fun decode(s: String): ByteArray {
        val clean = if (s.any(Char::isWhitespace)) s.filterNot(Char::isWhitespace) else s
        require(clean.length % 2 == 0) { "odd hex length" }
        return ByteArray(clean.length / 2) {
            (Character.digit(clean[it * 2], 16) shl 4 or Character.digit(clean[it * 2 + 1], 16)).toByte()
        }
    }

    /** BCD decode (EID / ICCID), optionally swapping nibbles. Filler nibbles come out as 'F'. */
    fun bcd(b: ByteArray, swap: Boolean): String {
        val c = CharArray(b.size * 2)
        for (i in b.indices) {
            val hi = DIGITS[b[i].toInt() shr 4 and 0xF]
            val lo = DIGITS[b[i].toInt() and 0xF]
            c[2 * i] = if (swap) lo else hi
            c[2 * i + 1] = if (swap) hi else lo
        }
        return String(c)
    }
}
