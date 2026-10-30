package com.esimonsim.adb

import org.conscrypt.Conscrypt
import org.conscrypt.OpenSSLProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.security.NoSuchAlgorithmException
import java.security.NoSuchProviderException
import java.security.Provider
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket

/** libadb reaches both classes by name, so nothing but these tests notices a rename before a phone fails to pair. */
class TlsShimTest {
    @Test fun libadbFindsTheProvider() {
        val provider = Class.forName("org.conscrypt.OpenSSLProvider").getDeclaredConstructor().newInstance() as Provider
        assertEquals(OpenSSLProvider.Spi::class.java.name, provider.getService("SSLContext", "TLSv1.3").className)
    }

    @Test fun libadbFindsTheKeyExport() {
        val export = Class.forName("org.conscrypt.Conscrypt").getMethod("exportKeyingMaterial",
            SSLSocket::class.java, String::class.java, ByteArray::class.java, Int::class.javaPrimitiveType)
        assertTrue(Modifier.isStatic(export.modifiers))
    }

    @Test fun onlyAndroidsOwnTlsWillDo() {
        // This JVM has its own TLSv1.3, and no AndroidOpenSSL: the provider must not settle for whatever is there.
        val e = assertThrows(NoSuchAlgorithmException::class.java) { SSLContext.getInstance("TLSv1.3", OpenSSLProvider()) }
        assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it is NoSuchProviderException })
    }

    @Test fun aFailedKeyExportIsNotAWrongCode() {
        // Below Android 12 it needs Android's internal Conscrypt class, which a JVM doesn't have either.
        val e = assertThrows(SSLException::class.java) { Conscrypt.exportKeyingMaterial(null, "adb-label", null, 64) }
        assertFalse(Pairing.failureMessage(SSLException(InvocationTargetException(e))).startsWith("That code didn't work"))
    }
}
