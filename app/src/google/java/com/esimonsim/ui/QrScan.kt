package com.esimonsim.ui

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.sdkinternal.MlKitContext
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/** QR scanning in the google build: Google Play services' scanner, which needs no camera permission, and the app's
 *  own ([ScanActivity]) wherever that one can't run: Play services missing or too old, microG, its scanner still
 *  downloading, or any other failure. */
object QrScan {
    /** Added to what the About page says about the internet: ML Kit, which the scanner comes with, uploads usage
     *  statistics from the app itself. */
    const val NETWORK_NOTE: String =
        " The QR scanner is Google Play services' scanner, which also sends Google usage statistics about it."

    /** startScan() fails at once (code 207) below Play services 22.15. */
    private const val MIN_VERSION = 221_500_000
    /** The Play services screen that ML Kit's own (invisible) activity starts. */
    private const val SCAN_ACTION = "com.google.android.gms.mlkit.ACTION_SCAN_BARCODE"
    /** How long Play services gets to put its scanner on screen. */
    private const val START_MS = 5_000L

    /** Play services' scanner failed in a way that lasts, or never showed up: the app's own scanner until the app
     *  restarts, or until Play services answers late after all. Main thread. */
    private var broken = false
    private val main = Handler(Looper.getMainLooper())

    private val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .enableAutoZoom()
        .build()

    private fun scanner(context: Context): GmsBarcodeScanner {
        // Started here, not by ML Kit's own content provider (removed in the manifest), which ran on every app start.
        MlKitContext.initializeIfNeeded(context.applicationContext)
        return GmsBarcodeScanning.getClient(context, options)
    }

    /** Real Play services, new enough. microG passes this too, hence the fallbacks in [scan]. */
    private fun available(context: Context) = GoogleApiAvailability.getInstance().run {
        isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS && getApkVersion(context) >= MIN_VERSION
    }

    /** Have Play services fetch the scanner UI ahead of the first scan; a no-op once installed. */
    fun prefetch(context: Context) {
        if (!available(context)) return
        ModuleInstall.getClient(context)
            .installModules(ModuleInstallRequest.newBuilder().addApi(scanner(context)).build())
    }

    /** One scan; [fallback] opens the app's own scanner. Backing out of either calls nothing. */
    fun scan(context: Context, lifecycle: Lifecycle, onResult: (String) -> Unit, onError: (String) -> Unit,
             fallback: () -> Unit) {
        // Without the scanner screen, ML Kit's activity would crash on start; microG has none.
        if (broken || !available(context) ||
            Intent(SCAN_ACTION).setPackage("com.google.android.gms").resolveActivity(context.packageManager) == null)
            return fallback()
        val attempt = object : Runnable {
            var settled = false
            /** The timer answered and opened the app's own scanner, but Play services may still answer: the scan
             *  can't be called off, so a slow Play services puts its scanner up over the app's own later on. */
            var timedOut = false

            /** True the first time: the one answer this scan gets. */
            fun settle(): Boolean {
                main.removeCallbacks(this)
                return !settled.also { settled = true }
            }

            /** True for Play services' answer after the timer's: slow, then, not hung. Only once. */
            fun late(): Boolean = timedOut.also { if (it) { timedOut = false; broken = false } }

            // Still on this screen, no scanner and no answer (seen with microG): use the app's own.
            override fun run() {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && settle()) {
                    timedOut = true
                    broken = true
                    fallback()
                }
            }
        }
        val task = try {
            scanner(context).startScan()
        } catch (_: RuntimeException) {
            broken = true
            return fallback()
        }
        main.postDelayed(attempt, START_MS)
        task.addOnSuccessListener {
            when {
                attempt.settle() -> {}
                // Scanned in Play services' scanner, which came up over the app's own: that one isn't needed now.
                attempt.late() -> ScanActivity.closeAll()
                else -> return@addOnSuccessListener
            }
            it.rawValue?.let(onResult) ?: onError("That QR code is empty.")
        }
            .addOnCanceledListener { if (!attempt.settle()) attempt.late() } // backed out
            .addOnFailureListener { e ->
                // After the timer, the app's own scanner is open already: only whether Play services' lasts counts.
                val first = attempt.settle()
                if (!first && !attempt.late()) return@addOnFailureListener
                when ((e as? MlKitException)?.errorCode) {
                    // Backed out, or a second tap while the first scan is still open.
                    MlKitException.CODE_SCANNER_CANCELLED, MlKitException.CODE_SCANNER_TASK_IN_PROGRESS -> {}
                    // Still downloading, or Play services may not use the camera: the app's own, this time.
                    MlKitException.CODE_SCANNER_UNAVAILABLE, MlKitException.CODE_SCANNER_CAMERA_PERMISSION_NOT_GRANTED ->
                        if (first) fallback()
                    else -> { broken = true; if (first) fallback() }
                }
            }
    }
}

/** A camera scan, to start from a click: [onResult] gets the code's text, [onError] a message to show. Closing the
 *  scanner calls neither. */
@Composable
fun rememberQrScanner(onResult: (String) -> Unit, onError: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val result by rememberUpdatedState(onResult)
    val error by rememberUpdatedState(onError)
    val camera = rememberScanActivity(onResult, onError)
    return remember(context, lifecycle, camera) {
        // Play services can answer after this screen has gone, and its launcher with it.
        val fallback: () -> Unit = { runCatching(camera) }
        val scan: () -> Unit = { QrScan.scan(context, lifecycle, { result(it) }, { error(it) }, fallback) }
        scan
    }
}
