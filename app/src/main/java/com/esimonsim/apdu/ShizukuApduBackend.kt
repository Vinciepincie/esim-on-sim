package com.esimonsim.apdu

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

/** Card access through Shizuku, a separate app that keeps Android's debugging tool (or root) running for the apps
 *  people allow in it. Its server starts the helper (adb/CardShell) as itself, and it answers over the same pipes as
 *  with su: nothing listens anywhere, and it ends with this app. Everything that touches Shizuku itself is in
 *  [ShizukuClient]. */
class ShizukuApduBackend internal constructor(private val apk: () -> String, private val gate: Gate) :
    PipeApduBackend(setOf(0, 2000)) {
    constructor(context: Context) : this({ context.applicationInfo.sourceDir }, ShizukuClient.of(context))

    /** What this uses of Shizuku; see [ShizukuClient]. */
    interface Gate {
        val installed: Boolean
        /** Its server is running and this app is attached to it. */
        val running: Boolean
        val allowed: Boolean
        /** [ask] is waiting for an answer. */
        val asking: Boolean
        /** Goes up whenever one of the above may have changed. */
        val changes: StateFlow<Int>
        /** Waits up to [ms] for [running]. */
        fun awaitRunning(ms: Long)
        /** Asks to allow this app and waits up to [seconds]. Null when no answer came. */
        fun ask(seconds: Long): Boolean?
        /** Ends [ask]'s wait; any thread. */
        fun cancelAsk()
        /** Runs [command] in a shell, as Shizuku's server. */
        fun start(command: String): Process
    }

    /** Why Shizuku can't be used now. */
    sealed class Failure(message: String, cause: Throwable? = null) : IOException(message, cause)
    class NotRunningException : Failure("Shizuku isn't running.")
    /** [answered]: Shizuku asked and people said no, rather than the question not being asked or answered. */
    class DeniedException(val answered: Boolean, message: String = "Shizuku didn't allow this app.") : Failure(message)
    /** It didn't start the helper. Nothing is ruled out: its server may be stuck, or too different from what this
     *  app knows. */
    class StartException(cause: Throwable) : Failure("Shizuku couldn't start this app's card helper.", cause)

    val installed: Boolean get() = gate.installed
    val running: Boolean get() = gate.running
    val allowed: Boolean get() = gate.allowed
    val asking: Boolean get() = gate.asking
    val changes: StateFlow<Int> get() = gate.changes

    /** Shizuku hands this app its connection a moment after the app starts, so not being [running] yet says little
     *  when it's installed. Nothing to wait for when it isn't. */
    fun awaitRunning(ms: Long) { if (!gate.running && gate.installed) gate.awaitRunning(ms) }

    /** Starts the helper. [ask]: Shizuku may ask people to allow this app, and that question gets [promptSeconds] to
     *  be answered. */
    fun connect(ask: Boolean, promptSeconds: Long = 60) {
        if (isReady()) return
        disconnect()
        if (!gate.running) throw NotRunningException()
        if (!gate.allowed) {
            if (!ask) throw DeniedException(answered = false)
            askToAllow(promptSeconds)
        }
        // Not "exec", unlike with su: when this app dies, Shizuku ends the process it started at once. That has to
        // be the shell, so the helper sees its input end, and closes the card's channels first. "exit" keeps the
        // shell from turning the command into an exec by itself.
        val process = try {
            gate.start("CLASSPATH=${AdbApduBackend.shellQuote(apk())} app_process /system/bin $HELPER_MAIN; exit \$?")
        } catch (_: SecurityException) { throw DeniedException(answered = false) } // no longer allowed
            catch (e: Exception) { throw StartException(e) }
        try { attach(process, START_SECONDS) } catch (e: Exception) { throw StartException(e) }
    }

    /** With direct access, which stays: asks Shizuku to allow this app, for what needs it. */
    fun allow(promptSeconds: Long = 60) {
        if (!gate.running) throw NotRunningException()
        if (!gate.allowed) askToAllow(promptSeconds)
    }

    private fun askToAllow(promptSeconds: Long) {
        when (gate.ask(promptSeconds)) {
            true -> return
            false -> throw DeniedException(answered = true)
            null -> {
                if (!gate.running) throw NotRunningException()
                throw DeniedException(answered = false, "No answer from Shizuku. Try again, and allow this app when it asks.")
            }
        }
    }

    /** Stops waiting for an answer, which then counts as none. Any thread. */
    fun cancelAsk() = gate.cancelAsk()

    private companion object {
        const val START_SECONDS = 15L
    }
}
