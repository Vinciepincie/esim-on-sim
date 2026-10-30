package com.esimonsim.adb

import org.json.JSONException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.net.ConnectException
import java.net.SocketException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/** A wrong code and a broken connection need opposite advice: only the first is fixed by retyping the digits. */
class PairingFailureTest {
    private fun fromWorker(cause: Throwable) = Pairing.failureMessage(ExecutionException(cause))

    private fun blamesTheCode(message: String) = message.startsWith("That code didn't work")

    @Test fun aRejectedCodeIsAWrongCode() {
        // What libadb throws when the phone's reply won't decrypt.
        assertTrue(blamesTheCode(fromWorker(IOException("Could not exchange peer info."))))
    }

    @Test fun aClosedPopUpSaysSo() {
        val message = fromWorker(IOException(ConnectException("Connection refused")))
        assertTrue(message, message.startsWith("The pairing pop-up closed"))
    }

    @Test fun tlsFailuresNeverBlameTheCode() {
        assertFalse(blamesTheCode(fromWorker(SSLHandshakeException("Handshake failed"))))
        assertFalse(blamesTheCode(fromWorker(SSLException("Read error"))))
        // Handshake failures can carry a bare IOException as their cause.
        assertFalse(blamesTheCode(fromWorker(SSLHandshakeException("connection closed").apply { initCause(IOException()) })))
    }

    @Test fun aFailedKeyExportNeverBlamesTheCode() {
        // How Android 11's hidden call comes back through libadb's reflection.
        val shim = SSLException("Couldn't derive the pairing key", ClassNotFoundException())
        assertFalse(blamesTheCode(fromWorker(SSLException(InvocationTargetException(shim)))))
    }

    @Test fun otherFailuresNeverBlameTheCode() {
        assertFalse(blamesTheCode(Pairing.failureMessage(TimeoutException())))
        assertFalse(blamesTheCode(Pairing.failureMessage(IllegalStateException("Check failed."))))
        assertFalse(blamesTheCode(fromWorker(EOFException())))
        assertFalse(blamesTheCode(fromWorker(SocketException("Connection reset"))))
        assertFalse(blamesTheCode(fromWorker(JSONException("Unterminated object"))))
        assertFalse(blamesTheCode(fromWorker(UnsatisfiedLinkError("spake2"))))
        // libadb's "Unable to initialize pairing cipher": a bare IOException, but with a cause.
        assertFalse(blamesTheCode(fromWorker(IOException().apply { initCause(IllegalArgumentException()) })))
    }
}
