package com.esimonsim.adb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.IOException
import java.net.ConnectException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One-time pairing with Wireless debugging. The port of the "Pair device with pairing code"
 *  dialog is found over mDNS while that dialog is open, so people only type the six-digit code.
 *  On Android 10 and older, which have no Wireless debugging, the first connection the phone allows
 *  ("Allow USB debugging?") counts as the pairing. */
object Pairing {
    private const val PREFS = "card-access"
    private const val PAIRED = "paired"
    private const val PORT = "connect-port"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isPaired(context: Context): Boolean = prefs(context).getBoolean(PAIRED, false)

    /** After pairing, or a connection on [port]: Wireless debugging keeps that port until it's switched off, and
     *  the debugging port a computer turned on (Android 10 and older) stays until the phone restarts. */
    internal fun markPaired(context: Context, port: Int = 0) = prefs(context).edit {
        putBoolean(PAIRED, true)
        if (port in 1..65535) putInt(PORT, port)
    }

    /** The connection port when card access last worked. */
    internal fun lastPort(context: Context): Int? = prefs(context).getInt(PORT, 0).takeIf { it in 1..65535 }

    internal fun forget(context: Context) = prefs(context).edit { putBoolean(PAIRED, false) }

    /** Blocking. Throws [IOException] with a message people can act on. */
    fun pair(context: Context, port: Int, code: String) {
        require(port in 1..65535) { "Enter the port shown in the pairing pop-up." }
        require(code.length == 6 && code.all(Char::isDigit)) { "Enter the 6-digit pairing code." }
        val app = context.applicationContext
        // Own thread and manager, so a stalled platform pairing handshake can't block anything else.
        val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "adb-pair").apply { isDaemon = true } }
        val result = worker.submit(Callable { AdbIdentity(app).use { it.pair("127.0.0.1", port, code) } })
        try {
            check(result.get(20, TimeUnit.SECONDS))
            markPaired(app)
        } catch (e: Exception) {
            result.cancel(true)
            throw IOException(failureMessage(e), e)
        } finally { worker.shutdownNow() }
    }

    /** Both sides send their details encrypted with the code before reading the other's, so a wrong code always ends
     *  the same way: the phone's reply won't decrypt, and libadb throws a bare IOException. Anything else (TLS, the key
     *  export, a lost connection, the timeout, an unreadable identity) isn't about the digits: blaming them would have
     *  people retype a right code, over and over, on a phone where that can never work. */
    internal fun failureMessage(e: Throwable): String {
        val failure = (e as? ExecutionException)?.cause ?: e
        return when {
            // Nothing listening on the port: the pop-up was closed (it closes when Settings leaves the screen).
            generateSequence(failure) { it.cause }.any { it is ConnectException } -> "The pairing pop-up closed, which " +
                "happens when you leave Settings. Tap \"Pair device with pairing code\" again and enter the new code " +
                "without leaving Settings."
            failure.javaClass == IOException::class.java && failure.cause == null ->
                "That code didn't work. Check the 6 digits in the pop-up and try again."
            else -> "Pairing stopped before your code could be checked. Turn \"Wireless debugging\" off and on, tap " +
                "\"Pair device with pairing code\" again and enter the new code."
        }
    }

    /** Whether this app may look for Wireless debugging's ports over mDNS. Android 17 asks people first ("Nearby
     *  devices"); without that, Android could put a picker of its own over Settings, which closes the pairing
     *  pop-up, or fail without a word. Connections over 127.0.0.1 need no permission. */
    fun canDiscover(context: Context): Boolean = Build.VERSION.SDK_INT < 37 ||
        context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    /** The pairing dialog's port while it is open on this phone, or null. Always null without [canDiscover]. */
    fun port(context: Context): Flow<Int?> = callbackFlow {
        trySend(null)
        if (!canDiscover(context)) { awaitClose(); return@callbackFlow }
        val mdns = AdbMdns(context.applicationContext, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { _, port ->
            trySend(port.takeIf { it in 1..65535 }) // -1 when the dialog closes
        }
        mdns.start()
        awaitClose { mdns.stop() }
    }.distinctUntilChanged()

    /** Blocking: waits up to [timeoutMs] for the pairing dialog to be found. Null at once without [canDiscover]. */
    fun findPort(context: Context, timeoutMs: Long): Int? = find(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING, timeoutMs)

    /** Blocking: Wireless debugging's connection port, which changes each time it is switched on. */
    fun findConnectPort(context: Context, timeoutMs: Long): Int? = find(context, AdbMdns.SERVICE_TYPE_TLS_CONNECT, timeoutMs)

    /** libadb's AdbMdns only reports services on this phone's own addresses with a listening port. */
    private fun find(context: Context, type: String, timeoutMs: Long): Int? {
        if (!canDiscover(context)) return null
        val found = AtomicInteger(-1)
        val latch = CountDownLatch(1)
        val mdns = AdbMdns(context.applicationContext, type) { _, port ->
            if (port in 1..65535) { found.set(port); latch.countDown() }
        }
        mdns.start()
        // Give up as soon as Wireless debugging or Wi-Fi goes off: nothing will be found then.
        val until = System.currentTimeMillis() + timeoutMs
        try {
            while (!latch.await(250, TimeUnit.MILLISECONDS)) {
                if (System.currentTimeMillis() >= until || !DebugSettings.read(context).mayConnect) break
            }
        } finally { mdns.stop() }
        return found.get().takeIf { it > 0 }
    }
}
