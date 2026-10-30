package com.esimonsim.apdu

import android.content.Context
import java.io.IOException

/** Card access on rooted phones: the helper that otherwise runs over Wireless debugging (adb/CardShell)
 *  starts with su instead, and answers over su's own input and output. It ends with this app, so nothing
 *  keeps running, and no setup is needed beyond allowing root once. */
class RootApduBackend internal constructor(private val apk: () -> String, private val start: (String) -> Process) :
    PipeApduBackend(setOf(0)) {
    constructor(context: Context) : this({ context.applicationInfo.sourceDir }, { ProcessBuilder("su", "-c", it).start() })

    /** This phone has no su this app may run: not rooted, or the root app hides it from apps it wasn't allowed. */
    class NotRootedException(cause: Throwable? = null) : IOException("This phone isn't rooted.", cause)

    /** su ran, but root wasn't granted (declined, or the request timed out), or the helper didn't start. */
    class DeniedException : IOException("Root access wasn't granted to this app.")

    /** Starts the helper as root. The first time, the phone's root app asks whether to allow this app, and
     *  that question gets [promptSeconds] to be answered. */
    fun connect(promptSeconds: Long = 60) {
        if (isReady()) return
        disconnect()
        val process = try { start("CLASSPATH=${AdbApduBackend.shellQuote(apk())} exec app_process /system/bin $HELPER_MAIN") }
            catch (e: IOException) { throw NotRootedException(e) } // no su, or not one this app may run
        try { attach(process, promptSeconds) } catch (_: Exception) { throw DeniedException() }
    }
}
