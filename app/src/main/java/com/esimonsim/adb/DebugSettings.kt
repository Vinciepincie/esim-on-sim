package com.esimonsim.adb

import android.content.Context
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/** The phone settings card access depends on, read directly so the app never waits on a
 *  connection attempt to find out that one of them is off. */
object DebugSettings {
    /** [developerOptions] is null when Android won't say: Android 17 may show apps 0 for it whatever it is, and then
     *  only Wireless debugging being on proves it's on. [wirelessDebugging] is null when this Android version doesn't
     *  let apps read it, and on Android 10 and older ([legacy]), which don't have it.
     *  [usbDebugging] keeps Android's debugging service running with no Wi-Fi, and with it the app's background card
     *  helper; null when Android won't say (Android 17 again). On Android 10 and older card access needs it: the
     *  debugging port a computer turns on ("adb tcpip") only listens while it's on.
     *  [pairingExpires]: Android forgets pairings unused for 7 days, unless "Disable adb authorization timeout"
     *  is on in Developer options. Android 10 forgets allowed keys the same way; 9 and older keep them. */
    data class State(val developerOptions: Boolean?, val wifi: Boolean, val wirelessDebugging: Boolean?,
                     val usbDebugging: Boolean? = false, val pairingExpires: Boolean = true,
                     val legacy: Boolean = false) {
        /** Nothing known to be off: worth trying to connect. */
        val mayConnect get() = developerOptions != false &&
            if (legacy) usbDebugging != false else wirelessDebugging != false && (wifi || wirelessDebugging == true)
    }

    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled" // Settings.Global, hidden but readable
    /** Milliseconds a pairing may go unused before Android forgets it; 0 never. Hidden but readable. */
    private const val ADB_ALLOWED_CONNECTION_TIME = "adb_allowed_connection_time"

    fun read(context: Context): State = read(context, wifiNetworks(context).isNotEmpty())

    private fun read(context: Context, wifi: Boolean): State {
        val cr = context.contentResolver
        val dev = Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        val usb = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0) == 1
        // @Readable in AOSP, so apps may read them; null only if an OEM build refuses.
        val adbWifi = try { Settings.Global.getInt(cr, ADB_WIFI_ENABLED, 0) } catch (_: SecurityException) { null }
        val allowedTime = try { Settings.Global.getLong(cr, ADB_ALLOWED_CONNECTION_TIME, 1L) } catch (_: SecurityException) { null }
        return interpret(Build.VERSION.SDK_INT, dev, usb, adbWifi, allowedTime, wifi)
    }

    /** What the raw settings mean on Android [sdk]: [adbWifi] and [allowedTime] as read, null when unreadable. */
    internal fun interpret(sdk: Int, dev: Boolean, usb: Boolean, adbWifi: Int?, allowedTime: Long?, wifi: Boolean): State {
        // Android 10 is the first to forget allowed keys; it does so after 7 days unless the setting says 0.
        val expires = sdk >= 29 && allowedTime != 0L
        // No Wireless debugging: card access goes through the debugging port a computer turned on.
        if (sdk < 30) return State(dev, wifi, wirelessDebugging = null, usbDebugging = usb && dev, pairingExpires = expires,
            legacy = true)
        // Android 17 can show both as 0 to apps, whatever they are. A hidden value is always 0, so a 1 is real; and
        // Wireless debugging (never hidden) only stays on with Developer options on.
        val hidden = sdk >= 37 && !dev && !usb
        val developerOptions = when { !hidden -> dev; adbWifi == 1 -> true; else -> null }
        // Android switches Wireless debugging off without Developer options: off then, whatever its setting says.
        val wirelessDebugging = adbWifi?.let { it == 1 && developerOptions != false }
        return State(developerOptions, wifi, wirelessDebugging, usbDebugging = if (hidden) null else usb && dev,
            pairingExpires = expires)
    }

    private fun wifiNetworks(context: Context): List<Network> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        @Suppress("DEPRECATION") // allNetworks also sees Wi-Fi that isn't the default network (no internet)
        return cm.allNetworks.filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    /** Emits the current state, then again whenever one of the settings or the Wi-Fi connection changes. */
    fun observe(context: Context): Flow<State> = callbackFlow {
        val app = context.applicationContext
        val wifi = HashSet(wifiNetworks(app))
        fun emit() { trySend(read(app, synchronized(wifi) { wifi.isNotEmpty() })) }

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = emit()
        }
        val cr = app.contentResolver
        cr.registerContentObserver(Settings.Global.getUriFor(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED), false, observer)
        cr.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), false, observer)
        runCatching { cr.registerContentObserver(Settings.Global.getUriFor(ADB_WIFI_ENABLED), false, observer) }
        runCatching { cr.registerContentObserver(Settings.Global.getUriFor(ADB_ALLOWED_CONNECTION_TIME), false, observer) }

        val cm = app.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { synchronized(wifi) { wifi += network }; emit() }
            override fun onLost(network: Network) { synchronized(wifi) { wifi -= network }; emit() }
        }
        // Wireless debugging works on a local network without internet access too.
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        emit()
        cm?.registerNetworkCallback(request, callback)

        awaitClose {
            cr.unregisterContentObserver(observer)
            runCatching { cm?.unregisterNetworkCallback(callback) }
        }
    }.conflate().distinctUntilChanged()
}
