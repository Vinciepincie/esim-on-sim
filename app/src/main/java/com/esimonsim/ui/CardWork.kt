package com.esimonsim.ui

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.esimonsim.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Keeps the process going while an eSIM installs or switches. The app sends an install to the card piece by piece, so
 *  it can't be left to finish by itself: killed halfway (a swipe from Recents, say) the card is cut off mid-install, and
 *  frozen, a reply can seem to time out. Android spares a process with a foreground service from both, and the wake lock
 *  keeps it running with the screen off. Not a guarantee, as phone makers' task killers ignore this and Android 14+ gives
 *  the service only about 3 minutes, so "Keep the app open" stays on screen. Main thread. */
object CardWork {
    internal val active = MutableStateFlow(false)
    private var lock: PowerManager.WakeLock? = null

    /** [title] is what the notification says. Call [end] once the card work has ended, also when it failed. */
    fun begin(context: Context, title: String) {
        val app = context.applicationContext
        active.value = true
        lock = app.getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "esimonsim:card")
            ?.apply { setReferenceCounted(false); acquire(LOCK_MS) }
        // Android refuses this from the background, as when a rename ends after people left: the work goes on anyway.
        runCatching { ContextCompat.startForegroundService(app, Intent(app, CardWorkService::class.java).putExtra(CardWorkService.TITLE, title)) }
    }

    /** The service stops by itself: stopping it from here could beat its startForeground, which crashes the app. */
    fun end() {
        active.value = false
        lock?.release()
        lock = null
    }

    /** Returns once [end] has been called, or after [limit]: a card call that never returns mustn't keep the service up
     *  for good. Below Android 14 nothing else would end it, and while it's up a swipe can't clear the stuck process, so
     *  each reopened app would queue behind it. The wake lock's cap, so slow installs stay covered there too. */
    internal suspend fun ended(limit: Long = LOCK_MS) { withTimeoutOrNull(limit) { active.first { !it } } }

    /** Past anything an install takes, in case [end] never comes. */
    private const val LOCK_MS = 5 * 60_000L
}

/** The foreground service of [CardWork]. Short, as a change to the card is: Android 14+ gives it about 3 minutes, and
 *  older versions no limit, so it has its own ([CardWork.ended]). It never touches the work itself. No actions: installs
 *  can't be cancelled. No asking for notifications either: without them it works just the same, only unseen. */
class CardWorkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null
    private var last = 0

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        last = startId
        foreground(intent?.getStringExtra(TITLE).orEmpty()) // first, always: started with startForegroundService
        if (watch?.isActive != true) watch = scope.launch { CardWork.ended(); leave() }
        return START_NOT_STICKY
    }

    private fun foreground(title: String) {
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_sim)
            .setContentTitle(title)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        try {
            NotificationManagerCompat.from(this).createNotificationChannel(NotificationChannelCompat
                .Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW).setName("eSIM changes")
                .setDescription("Shows while an eSIM installs or switches, so Android keeps the app running.")
                .setShowBadge(false).build())
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            else startForeground(NOTIFICATION, n)
        } catch (_: Exception) { } // e.g. not allowed from the background: the work goes on without this
    }

    /** With [last], so a start that's still on its way keeps the service for the work that asked for it. */
    private fun leave() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf(last)
    }

    override fun onTimeout(startId: Int, fgsType: Int) = leave() // Android 15+
    @Deprecated("Android 14 only") override fun onTimeout(startId: Int) = leave()

    override fun onDestroy() { scope.cancel() }

    internal companion object {
        const val TITLE = "title"
        private const val CHANNEL = "card-work"
        private const val NOTIFICATION = 2 // 1 is pairing's
    }
}
