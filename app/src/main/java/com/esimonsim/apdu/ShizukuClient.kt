package com.esimonsim.apdu

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Shizuku's client library, used nowhere else. One per process, because its listeners are: they're added once and
 *  never removed. Keep it that way: its remove*Listener calls need Android 7, and lint can't tell, so on Android 6
 *  they'd crash. Without those, the library runs on Android 6 as it is, without desugaring. */
class ShizukuClient private constructor(context: Context) : ShizukuApduBackend.Gate {
    private val packages = context.applicationContext.packageManager
    private val _changes = MutableStateFlow(0)
    override val changes: StateFlow<Int> = _changes.asStateFlow()

    private class Question { val answered = CountDownLatch(1); @Volatile var allowed: Boolean? = null }
    @Volatile private var question: Question? = null
    override val asking: Boolean get() = question != null

    init {
        // On the main thread. A connection that arrives or ends between two looks is picked up by the next look.
        Shizuku.addBinderReceivedListenerSticky { changed() }
        Shizuku.addBinderDeadListener { question?.answered?.countDown(); changed() }
        Shizuku.addRequestPermissionResultListener { _, result ->
            question?.let { it.allowed = result == PackageManager.PERMISSION_GRANTED; it.answered.countDown() }
            changed()
        }
    }

    private fun changed() = _changes.update { it + 1 }

    /** Worth a look, and what its button opens. Needs its <queries> entry in the manifest. */
    override val installed: Boolean get() = packages.getLaunchIntentForPackage(MANAGER) != null

    // The version: a connection that's alive isn't attached yet, and Shizuku refuses what isn't. Older servers lack
    // what this app uses. Alive, not pingBinder(): this runs on the main thread too, and that makes a binder call.
    override val running: Boolean get() = try {
        Shizuku.getBinder()?.isBinderAlive == true && !Shizuku.isPreV11() && Shizuku.getVersion() >= MIN_VERSION
    } catch (_: RuntimeException) { false }

    // Remembered across a stopped server, so only worth anything while it runs.
    override val allowed: Boolean get() = running && try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: RuntimeException) { false }

    override fun awaitRunning(ms: Long) {
        val until = SystemClock.elapsedRealtime() + ms
        while (!running && SystemClock.elapsedRealtime() < until) Thread.sleep(50)
    }

    /** Asks each time, also when an earlier question may still be around: it may be somewhere nobody can answer it.
     *  The time limit is all that ends this for sure: Shizuku doesn't time out, and may not manage to show the question
     *  at all. So the binder calls go on a thread of their own: a stuck server mustn't hold card access. */
    override fun ask(seconds: Long): Boolean? {
        val current = Question()
        question = current
        changed()
        Thread({
            try {
                Shizuku.requestPermission(REQUEST)
                // It sends no word when allowing goes another way, such as a question from before this app restarted.
                while (!current.answered.await(1, TimeUnit.SECONDS)) {
                    if (allowed) { current.allowed = true; current.answered.countDown() }
                }
            } catch (_: RuntimeException) { current.answered.countDown() }
        }, "card-shizuku-ask").apply { isDaemon = true }.start()
        try {
            current.answered.await(seconds, TimeUnit.SECONDS)
            return current.allowed
        } finally {
            current.answered.countDown()
            if (question === current) question = null
            changed()
        }
    }

    override fun cancelAsk() { question?.answered?.countDown() }

    /** Over binder, so not on the card thread: a stuck Shizuku server mustn't hold card access. The library keeps its
     *  own newProcess private and points to its UserService instead, but that crashes before any app code runs on
     *  MediaTek phones, Xiaomi's included. A server without this call answers null. */
    override fun start(command: String): Process {
        val starting = FutureTask {
            val service = IShizukuService.Stub.asInterface(Shizuku.getBinder()) ?: throw IOException("Shizuku stopped")
            // Env stays null: anything else replaces all of it.
            val remote = service.newProcess(arrayOf("/system/bin/sh", "-c", command), null, null)
                ?: throw IOException("Shizuku didn't start anything")
            try { ShizukuProcess(remote) } catch (e: Exception) { runCatching { remote.destroy() }; throw e }
        }
        Thread(starting, "card-shizuku-start").apply { isDaemon = true }.start()
        try { return starting.get(START_SECONDS, TimeUnit.SECONDS) }
        catch (e: ExecutionException) { throw e.cause ?: e }
        catch (e: TimeoutException) {
            // Should it still come, nobody wants it.
            Thread({ runCatching { starting.get().destroy() } }, "card-shizuku-late").apply { isDaemon = true }.start()
            throw IOException("Shizuku didn't answer", e)
        }
    }

    /** A process of Shizuku's server. Each stream can only be fetched once. */
    private class ShizukuProcess(private val remote: IRemoteProcess) : Process() {
        private val input = ParcelFileDescriptor.AutoCloseOutputStream(remote.outputStream)
        private val output = ParcelFileDescriptor.AutoCloseInputStream(remote.inputStream)
        private val errors = ParcelFileDescriptor.AutoCloseInputStream(remote.errorStream)

        override fun getOutputStream(): OutputStream = input
        override fun getInputStream(): InputStream = output
        override fun getErrorStream(): InputStream = errors
        override fun waitFor() = remote.waitFor()
        override fun exitValue() = remote.exitValue()

        /** Over binder too, so not waited for. */
        override fun destroy() {
            Thread({ runCatching { remote.destroy() } }, "card-shizuku-end").apply { isDaemon = true }.start()
        }
    }

    companion object {
        /** Shizuku's app, also from its forks. */
        const val MANAGER = ShizukuProvider.MANAGER_APPLICATION_ID
        /** The first server that ends what an app started when the app dies, and asks people itself. */
        private const val MIN_VERSION = 11
        private const val REQUEST = 1
        private const val START_SECONDS = 15L

        @Volatile private var instance: ShizukuClient? = null
        fun of(context: Context) = instance ?: synchronized(this) { instance ?: ShizukuClient(context).also { instance = it } }
    }
}

/** How Shizuku's server hands this app its connection. Without the library's start-up check for Sui, Shizuku's Magisk
 *  variant: that's two binder calls on the main thread at every start, for everyone, and rooted phones use su anyway. */
class ShizukuBinderProvider : ShizukuProvider() {
    override fun onCreate() = true
}
