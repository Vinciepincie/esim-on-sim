package com.esimonsim.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit

/** The certificate is hand-written DER: a real X.509 parser must accept it, as Android's and adbd's do. */
class SelfSignedTest {
    private fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun parse(der: ByteArray) =
        CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate

    @Test fun parsesAndVerifies() {
        val pair = rsa()
        val cert = parse(AdbIdentity.selfSigned(pair))
        cert.verify(pair.public)
        cert.checkValidity()
        assertEquals(3, cert.version)
        assertEquals("SHA256withRSA", cert.sigAlgName)
        assertEquals("CN=eSIM On-SIM", cert.subjectX500Principal.name)
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        assertEquals(pair.public, cert.publicKey)
    }

    @Test fun validForTenYearsFromYesterday() {
        val cert = parse(AdbIdentity.selfSigned(rsa()))
        val now = System.currentTimeMillis()
        assertTrue(cert.notBefore.before(Date(now)))
        assertEquals(TimeUnit.DAYS.toMillis(1).toDouble(), (now - cert.notBefore.time).toDouble(), 60_000.0)
        assertEquals(TimeUnit.DAYS.toMillis(3650).toDouble(), (cert.notAfter.time - now).toDouble(), 60_000.0)
    }

    @Test fun serialsDiffer() {
        val pair = rsa()
        val serial = parse(AdbIdentity.selfSigned(pair)).serialNumber
        assertTrue(serial.signum() >= 0)
        assertNotEquals(serial, parse(AdbIdentity.selfSigned(pair)).serialNumber)
    }
}
