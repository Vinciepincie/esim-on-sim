package com.esimonsim.adb

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import android.util.Base64
import com.esimonsim.lpa.Tlv
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** One persistent identity per app installation. Never exported or included in backups.
 *
 *  [authorize]: someone tapped Connect, on Android 10 or older. There the phone allows keys with its "Allow USB
 *  debugging?" question, which only comes up when the key is offered: this does, and waits [ALLOW_SECONDS] for the
 *  answer. Otherwise a key the phone doesn't know fails at once (AdbAuthenticationFailedException), so nothing asks
 *  people a question they didn't start. Wireless debugging (TLS, Android 11+) never asks: it pairs instead. */
class AdbIdentity(context: Context, authorize: Boolean = false) : AbsAdbConnectionManager() {
    private val signingKey: PrivateKey
    private val cert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        setTimeout(if (authorize) ALLOW_SECONDS else 10, TimeUnit.SECONDS)
        setThrowOnUnauthorised(!authorize)
        val (key, certificate) = load(context)
        signingKey = key
        cert = certificate
    }

    override fun getPrivateKey(): PrivateKey = signingKey
    override fun getCertificate(): Certificate = cert
    // One word: Settings lists a paired device by the name's first word.
    override fun getDeviceName(): String = "eSIM-On-SIM"

    companion object {
        /** How long the phone's "Allow USB debugging?" question may take: reading the fingerprint takes a moment. */
        const val ALLOW_SECONDS = 120L

        /** The button that allows a key on the phone's "Allow USB debugging?" question: "Allow" from Android 10,
         *  "OK" before (SystemUI's UsbDebuggingActivity). [sdk]: the phone's API level. */
        fun allowButton(sdk: Int = Build.VERSION.SDK_INT) = if (sdk >= 29) "Allow" else "OK"

        /** Only one key per installation: two first connections at once must not each make and save one. */
        private val lock = Any()

        /** What Android shows as "The computer's RSA key fingerprint" when it asks to allow this app. Blocking:
         *  reads the identity file, and the first time makes the key. */
        fun fingerprint(context: Context): String = fingerprint(load(context).second.publicKey as RSAPublicKey)

        private fun load(context: Context): Pair<PrivateKey, Certificate> = synchronized(lock) {
            val file = AtomicFile(File(context.noBackupFilesDir, "adb-identity.json"))
            if (file.baseFile.exists()) {
                val saved = JSONObject(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
                return KeyFactory.getInstance("RSA").generatePrivate(
                    PKCS8EncodedKeySpec(Base64.decode(saved.getString("key"), Base64.NO_WRAP))) to
                    CertificateFactory.getInstance("X.509").generateCertificate(
                        Base64.decode(saved.getString("cert"), Base64.NO_WRAP).inputStream())
            }
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(selfSigned(pair).inputStream())
            val data = JSONObject()
                .put("key", Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP))
                .put("cert", Base64.encodeToString(cert.encoded, Base64.NO_WRAP))
            val output = file.startWrite()
            try {
                output.write(data.toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(output)
            } catch (e: Exception) { file.failWrite(output); throw e }
            return pair.private to cert
        }

        /** A self-signed X.509 v3 certificate for [pair], as DER. TLS needs one, and adbd only reads the key out of it,
         *  so a few lines of the app's own DER writer are cheaper than an X.509 library that runs once per install. */
        internal fun selfSigned(pair: KeyPair): ByteArray {
            val sha256WithRsa = Tlv.buildSeq(0x30, Tlv.build(0x06, byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0x86.toByte(),
                0xF7.toByte(), 0x0D, 1, 1, 0x0B)), Tlv.build(0x05, ByteArray(0)))
            val name = Tlv.buildSeq(0x30, Tlv.buildSeq(0x31, Tlv.buildSeq(0x30, Tlv.build(0x06, byteArrayOf(0x55, 4, 3)),
                Tlv.build(0x0C, "eSIM On-SIM".toByteArray()))))
            val utc = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val now = System.currentTimeMillis()
            val tbs = Tlv.buildSeq(0x30, Tlv.build(0xA0, Tlv.build(0x02, byteArrayOf(2))),
                Tlv.build(0x02, BigInteger(63, SecureRandom()).toByteArray()), sha256WithRsa, name,
                Tlv.buildSeq(0x30, Tlv.build(0x17, utc.format(Date(now - 86_400_000L)).toByteArray()),
                    Tlv.build(0x17, utc.format(Date(now + 10L * 365 * 86_400_000L)).toByteArray())),
                name, pair.public.encoded)
            val signature = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(tbs) }.sign()
            return Tlv.buildSeq(0x30, tbs, sha256WithRsa, Tlv.build(0x03, byteArrayOf(0) + signature))
        }

        /** As Android shows it: MD5 of the key in adb's own format, in colon-separated upper-case hex. */
        internal fun fingerprint(key: RSAPublicKey): String =
            MessageDigest.getInstance("MD5").digest(androidPubkey(key)).joinToString(":") { "%02X".format(it) }

        /** The public key in adb's binary format (libadb's AndroidPubkey.encode, which libadb keeps to itself):
         *  what it sends to the phone, base64 and named, to be allowed. A 2048-bit key only. */
        internal fun androidPubkey(key: RSAPublicKey): ByteArray {
            val size = 2048 / 8
            require(key.modulus.bitLength() == size * 8) { "Not a 2048-bit key" }
            val r32 = BigInteger.ONE.shiftLeft(32)
            // Montgomery parameters, as adb's android_pubkey.cpp precomputes them: -1 / n mod 2^32, and R^2 mod n.
            val n0inv = r32.subtract(key.modulus.mod(r32).modInverse(r32))
            val rr = BigInteger.ONE.shiftLeft(size * 8).modPow(BigInteger.valueOf(2), key.modulus)
            return ByteBuffer.allocate(3 * 4 + 2 * size).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(size / 4)
                putInt(n0inv.toInt())
                put(littleEndian(key.modulus, size))
                put(littleEndian(rr, size))
                putInt(key.publicExponent.toInt())
            }.array()
        }

        private fun littleEndian(n: BigInteger, size: Int): ByteArray {
            val big = n.toByteArray() // big-endian, with a leading 0 when the top bit is set
            return ByteArray(size) { i -> if (i < big.size) big[big.size - 1 - i] else 0 }
        }
    }
}
