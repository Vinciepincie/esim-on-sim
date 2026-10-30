package com.esimonsim.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import com.esimonsim.R
import com.esimonsim.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Pairing in one place, without split screen. Wireless debugging's pairing dialog closes as soon as
 * Settings leaves the screen, so the six-digit code is typed into a notification instead, while the
 * dialog stays open. The dialog's port is found over mDNS ([Pairing.port]); nobody has to type it. On Android 17
 * that needs "Nearby devices", which the app asks for before [start]: the service can't ask while Settings is open.
 *
 * With the notification, [PairingService] does the work: Android freezes background apps, and a
 * short foreground service keeps this one running while people are in Settings. Without it (the
 * code typed into the app in split screen), the app is on screen and looks for the port itself.
 *
 * Wireless debugging is Android 11+: so is everything here that pairs or notifies.
 */
object PairingFlow {
    data class State(
        /** Looking for the pairing dialog / accepting a code. */
        val active: Boolean = false,
        /** The pairing dialog is open and its port is known. */
        val port: Int? = null,
        val pairing: Boolean = false,
        val error: String? = null,
        /** Bumped on each successful pairing, so observers can react once. */
        val pairedCount: Int = 0,
    )

    private const val CHANNEL = "pairing"
    internal const val NOTIFICATION = 1
    internal const val KEY_CODE = "code"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var watch: Job? = null

    /** Start looking for the pairing dialog. With [notify], the code is typed into a notification. */
    @Synchronized
    @RequiresApi(30)
    fun start(context: Context, notify: Boolean) {
        val app = context.applicationContext
        _state.update { it.copy(active = true, error = null) }
        if (notify) {
            watch?.cancel(); watch = null // the service looks instead
            app.startForegroundService(Intent(app, PairingService::class.java))
        } else {
            // Switching from the notification to typing the code here: the service's timer would end this too.
            app.stopService(Intent(app, PairingService::class.java))
            manager(app)?.cancel(NOTIFICATION)
            if (watch?.isActive != true)
                watch = scope.launch { Pairing.port(app).collect(::setPort) }
        }
    }

    @Synchronized
    fun stop(context: Context) {
        val app = context.applicationContext
        watch?.cancel()
        watch = null
        app.stopService(Intent(app, PairingService::class.java))
        _state.update { it.copy(active = false, port = null, pairing = false, error = null) }
        manager(app)?.cancel(NOTIFICATION)
    }

    /** A newly opened pop-up clears the last error: it's what that error asked people to do. */
    internal fun setPort(port: Int?) = _state.update {
        it.copy(port = port, error = if (port != null && it.port == null) null else it.error)
    }

    /** From the notification's Cancel button. */
    internal fun cancelled(app: Context) {
        synchronized(this) { watch?.cancel(); watch = null }
        _state.update { it.copy(active = false, port = null, pairing = false, error = null) }
    }

    @RequiresApi(30)
    internal fun timedOut(app: Context) {
        _state.update { it.copy(active = false, port = null, pairing = false,
            error = "Pairing timed out. Tap \"Start pairing\" to try again.") }
        post(app, title = "Pairing timed out", text = "Open eSIM On-SIM to try again.", reply = false, ongoing = false)
    }

    /** Pair with [code]. Blocking; returns true when paired. [port] skips the search when already known. */
    @RequiresApi(30)
    fun submit(context: Context, code: String, port: Int? = null): Boolean {
        val app = context.applicationContext
        val digits = code.filter(Char::isDigit)
        if (digits.length != 6) return fail(app, "Enter all 6 digits of the pairing code.")
        // Active again in a process restarted for this reply, so its notification follows along.
        _state.update { it.copy(active = true, pairing = true, error = null) }
        refresh(app)
        // A restarted process (e.g. after the app was closed) hasn't found the dialog yet.
        val target = port ?: _state.value.port ?: Pairing.findPort(app, 4000)
            ?: return fail(app, if (!Pairing.canDiscover(app)) "Couldn't look for the pairing pop-up: this app " +
                "isn't allowed \"Nearby devices\". Enter the code in this app instead, with the port the pop-up shows."
                else "Couldn't find the pairing pop-up. In \"Wireless debugging\", tap " +
                "\"Pair device with pairing code\", keep it open, and enter the new code it shows.")
        return try {
            Pairing.pair(app, target, digits)
            // Together: the pop-up closing now makes the service's watcher call refresh, which mustn't undo "Paired".
            synchronized(this) {
                watch?.cancel(); watch = null
                _state.update { State(pairedCount = it.pairedCount + 1) }
                post(app, title = "Paired", text = "Go back to eSIM On-SIM to see your card.", reply = false, ongoing = false)
            }
            true
        } catch (e: Exception) {
            fail(app, e.message ?: "Pairing didn't work. Try again.")
        }
    }

    /** Input the app checked before pairing, shown where pairing errors show. */
    @RequiresApi(30)
    fun reject(context: Context, message: String) { fail(context.applicationContext, message) }

    @RequiresApi(30)
    private fun fail(app: Context, message: String): Boolean {
        _state.update { it.copy(pairing = false, error = message) }
        refresh(app)
        return false
    }

    // ---------------- notification ----------------

    private fun manager(app: Context) = app.getSystemService(NotificationManager::class.java)

    private fun shown(app: Context) = manager(app)?.activeNotifications?.any { it.id == NOTIFICATION } == true

    /** Update the pairing notification, if it is up, to match [state]. With [alert] it drops down over Settings
     *  again, its "Enter code" button showing: in the shade it can sit collapsed under other notifications. */
    @RequiresApi(30)
    internal fun refresh(app: Context, alert: Boolean = false) = synchronized(this) {
        if (_state.value.active && shown(app)) manager(app)?.notify(NOTIFICATION, notification(app, alert))
    }

    /** The notification for the current [state]; the foreground service shows it too. */
    @RequiresApi(30)
    internal fun notification(app: Context, alert: Boolean = false): Notification {
        val s = _state.value
        val (title, text) = when {
            s.pairing -> "Pairing…" to "This takes a few seconds."
            s.error != null -> "That didn't work" to s.error
            s.port != null -> "Enter the pairing code" to "Tap \"Enter code\" and type the 6 digits shown in " +
                "Settings. Stay in Settings: leaving it closes the pop-up and changes the code."
            else -> "Waiting for the pairing pop-up" to
                "Open \"Wireless debugging\" (tap its name, not the switch), then tap \"Pair device with pairing code\". " +
                    "Then tap \"Enter code\" below."
        }
        return build(app, title, text, reply = !s.pairing, ongoing = true, alert = alert)
    }

    @RequiresApi(30)
    private fun post(app: Context, title: String, text: String, reply: Boolean, ongoing: Boolean) {
        runCatching { manager(app)?.notify(NOTIFICATION, build(app, title, text, reply, ongoing)) }
    }

    /** Created once per process: each call is a round trip to the system's notification service. */
    @Volatile private var channelReady = false

    @RequiresApi(30)
    private fun build(app: Context, title: String, text: String, reply: Boolean, ongoing: Boolean,
                      alert: Boolean = false): Notification {
        if (!channelReady) manager(app)?.let { nm ->
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Pairing", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Lets you enter the pairing code without leaving Settings."
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                })
            channelReady = true
        }
        return Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_sim)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(!alert) // silent either way: the channel has no sound or vibration
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setCategory(Notification.CATEGORY_STATUS)
            // While pairing, a tap would open this app, which takes Settings off screen and closes the
            // pop-up. Android 14+ lets people swipe even ongoing notifications away, so it comes back.
            .apply {
                if (ongoing) setDeleteIntent(dismissedIntent(app))
                else { setContentIntent(openIntent(app)); setTimeoutAfter(120_000) }
            }
            .apply { if (reply) { addAction(replyAction(app)); addAction(cancelAction(app)) } }
            .build()
    }

    private fun openIntent(app: Context) = PendingIntent.getActivity(app, 0,
        Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    @RequiresApi(30)
    private fun dismissedIntent(app: Context) = PendingIntent.getService(app, 3,
        Intent(app, PairingService::class.java).setAction(PairingService.ACTION_DISMISSED),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Swiped away while pairing: shown again, since the code is typed into it. False once pairing is over. */
    @RequiresApi(30)
    internal fun dismissed(app: Context): Boolean {
        if (!_state.value.active) return false
        runCatching { manager(app)?.notify(NOTIFICATION, notification(app)) }
        return true
    }

    @RequiresApi(30)
    private fun cancelAction(app: Context): Notification.Action {
        val cancel = PendingIntent.getService(app, 2,
            Intent(app, PairingService::class.java).setAction(PairingService.ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(null, "Cancel", cancel).build()
    }

    @RequiresApi(30)
    private fun replyAction(app: Context): Notification.Action {
        // RemoteInput needs a mutable PendingIntent. It is explicit, so only this app's service receives it.
        // The port rides along, in case Android restarted the process since it was found.
        val intent = Intent(app, PairingService::class.java).setAction(PairingService.ACTION_REPLY)
            .putExtra(PairingService.EXTRA_PORT, _state.value.port ?: -1)
        val reply = PendingIntent.getForegroundService(app, 1, intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val input = RemoteInput.Builder(KEY_CODE).setLabel("6-digit code").build()
        return Notification.Action.Builder(null, "Enter code", reply).addRemoteInput(input).build()
    }
}

/** Keeps pairing alive while people are in Settings: looks for the pairing dialog, takes the code
 *  typed into the notification, and pairs. A short service: Android stops it after about 3 minutes. */
@RequiresApi(30)
class PairingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val timeout = Handler(Looper.getMainLooper())
    private var watch: Job? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) { // a plain start (getService), so no foreground promise to keep
            PairingFlow.cancelled(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_DISMISSED) { // a plain start too
            if (!PairingFlow.dismissed(this)) stopSelf()
            return START_NOT_STICKY
        }
        foreground() // first, always: started with startForegroundService
        if (intent?.action == ACTION_REPLY) {
            val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PairingFlow.KEY_CODE)?.toString()
            val port = intent.getIntExtra(EXTRA_PORT, -1).takeIf { it in 1..65535 }
            scope.launch {
                val paired = code != null && PairingFlow.submit(this@PairingService, code, port)
                if (paired) finish() else ensureWatching()
            }
        } else ensureWatching()
        timeout.removeCallbacksAndMessages(null)
        timeout.postDelayed(::timedOut, 170_000) // shortService allows about 3 minutes
        return START_NOT_STICKY
    }

    private fun foreground() {
        val n = PairingFlow.notification(this)
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(PairingFlow.NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            else startForeground(PairingFlow.NOTIFICATION, n)
        } catch (_: Exception) { // e.g. not allowed from the background: the notification still works
            runCatching { getSystemService(NotificationManager::class.java)?.notify(PairingFlow.NOTIFICATION, n) }
        }
    }

    private fun ensureWatching() {
        if (watch?.isActive == true) return
        watch = scope.launch {
            Pairing.port(this@PairingService).collect { port ->
                // The pop-up just opened: the notification drops down with "Enter code" right there.
                val opened = port != null && PairingFlow.state.value.port == null
                PairingFlow.setPort(port)
                PairingFlow.refresh(this@PairingService, alert = opened)
            }
        }
    }

    private fun timedOut() {
        PairingFlow.timedOut(this)
        finish()
    }

    /** Leave the last notification (paired / timed out) up, and stop. */
    private fun finish() {
        timeout.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) = timedOut() // Android 15+
    @Deprecated("Android 14 only") override fun onTimeout(startId: Int) = timedOut()

    override fun onDestroy() {
        timeout.removeCallbacksAndMessages(null)
        scope.cancel()
    }

    companion object {
        const val ACTION_REPLY = "com.esimonsim.action.PAIRING_REPLY"
        const val ACTION_CANCEL = "com.esimonsim.action.PAIRING_CANCEL"
        const val ACTION_DISMISSED = "com.esimonsim.action.PAIRING_DISMISSED"
        const val EXTRA_PORT = "port"
    }
}
