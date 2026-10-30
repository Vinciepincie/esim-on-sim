package com.esimonsim.lpa

import java.net.URLDecoder

data class EuiccProfile(
    val iccid: String,
    val isdpAid: String,
    val enabled: Boolean,
    val nickname: String?,
    val providerName: String?,
    val profileName: String?,
    val profileClass: Int,
) {
    val displayName: String
        get() = nickname.orNull() ?: profileName.orNull() ?: providerName.orNull() ?: "ICCID $iccid"

    /** The network name the eSIM carries, which can differ from the brand it was bought from
     *  (Saily eSIMs say 1GLOBAL). Null when it is already the [displayName]. */
    val carrierName: String?
        get() = (providerName.orNull() ?: profileName.orNull())?.takeIf { it != displayName }

    val hasNickname: Boolean get() = nickname.orNull() != null

    val classLabel: String
        get() = when (profileClass) {
            0 -> "Test"
            1 -> "Setup"
            2 -> "Operational"
            else -> "—"
        }
}

private fun String?.orNull() = this?.takeIf { it.isNotBlank() }

private val Spaces = Regex("\\s+") // compiled once: nameKey runs per eSIM in composition, on every keystroke

/** Names compared the way people read them: case, and spaces around and between words, don't count. */
fun nameKey(name: String) = name.trim().replace(Spaces, " ").lowercase()

/** The provider is about to send an eSIM whose ICCID is already on the card. */
class AlreadyOnCardException(val iccid: String) : Exception("This eSIM is already on your card.")

/** The provider wants a confirmation code and none was given, which the activation code needn't have said. Any
 *  session is cancelled first, so the same code can be tried again along with the confirmation code. */
class ConfirmationCodeNeededException : Exception("This eSIM needs the confirmation code from your provider.")

/**
 * SGP.22 activation code, `LPA:1$<SM-DP+ address>$<matching ID>[$<OID>[$<CC required>]]`,
 * as encoded in an eSIM QR code.
 */
data class ActivationCode(val smdp: String, val matchingId: String, val ccRequired: Boolean = false) {
    /** Canonical form; keeps the confirmation-code flag (empty OID field before it). */
    override fun toString() = "LPA:1\$$smdp\$$matchingId" + if (ccRequired) "\$\$1" else ""

    companion object {
        /** Parse QR or pasted text, with or without the `LPA:` prefix. Also finds a code wrapped in
         *  a link (e.g. `…esim_qrcode_provisioning?carddata=LPA:1$…`) or behind a label. */
        fun parse(input: String): ActivationCode? {
            var s = input.filterNot(Char::isWhitespace)
            if (!s.startsWith("LPA:", ignoreCase = true) && '%' in s)
                s = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)
            val at = s.indexOf("LPA:", ignoreCase = true)
            if (at > 0) s = s.substring(at).substringBefore('&')
            val f = (if (s.startsWith("LPA:", ignoreCase = true)) s.substring(4) else s).split('$')
            if (f.size < 2 || f[0] != "1") return null
            return of(f[1], f.getOrElse(2) { "" }, ccRequired = f.getOrNull(4) == "1")
        }

        private val TRAILING = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '*', '_', '~', '`')
        private val InText = Regex("LPA:1\\$[^\\s\"'<>&]+", RegexOption.IGNORE_CASE)
        /** The first word on the next line. */
        private val NextLine = Regex("[ \\t]*\\r?\\n[ \\t]*([^\\s\"'<>&]+)")

        /** [code] with what line breaks cut off it, where a break can't be anything else: after a hyphen, or with
         *  the code's second "$" on the next line. A plain wrap ("…$ABC\nDEF") can't be told from one word on a line
         *  of its own, like "ICCID" under the code, so it stays cut: the provider then refuses a short code, not
         *  one with someone's words glued on. */
        private fun rejoined(text: String, code: MatchResult): String {
            var whole = code.value
            var at = code.range.last + 1
            while (true) {
                val next = NextLine.matchAt(text, at) ?: return whole
                val word = next.groupValues[1]
                if (!whole.endsWith('-') && (whole.count { it == '$' } >= 2 || '$' !in word)) return whole
                whole += word
                at = next.range.last + 1
            }
        }

        /** The first activation code anywhere in [text]: a shared message or email, an lpa: link, or an
         *  "Install on iPhone" link (…?carddata=LPA:1$…). Falls back to [parse] for a bare code. */
        fun find(text: String): ActivationCode? {
            // Links may encode the code's "$"s; a "+" stays a plus.
            val decoded = if ('%' in text) runCatching { URLDecoder.decode(text.replace("+", "%2B"), "UTF-8") }
                .getOrDefault(text) else text
            // Sentence punctuation and chat markup ("*LPA:1$…*", "…$ABC.") never end a code.
            return InText.find(decoded)?.let { parse(rejoined(decoded, it).trimEnd(*TRAILING)) } ?: parse(decoded)
        }

        /** Build from the separate "SM-DP+ address" and "activation code" that providers list for manual entry. */
        fun of(smdp: String, matchingId: String, ccRequired: Boolean = false): ActivationCode? {
            val host = smdp.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
            val id = matchingId.filterNot(Char::isWhitespace)
            // "@": what's before it would show as the provider while the connection goes to what's after.
            if ('.' !in host || host.any { it.isWhitespace() || it in "/\$@?#\\" } || '$' in id) return null
            return ActivationCode(host, id, ccRequired)
        }
    }
}
