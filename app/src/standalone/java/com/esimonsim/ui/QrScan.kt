package com.esimonsim.ui

import android.content.Context
import androidx.compose.runtime.Composable

/** QR scanning in the standalone build: always the app's own camera screen ([ScanActivity]), no Google code. */
object QrScan {
    /** Nothing to add to what the setup screen says about the internet: the scanner never goes online. */
    const val NETWORK_NOTE: String = ""

    /** Nothing to fetch: the scanner is part of the app. (The google build's QrScan has the same API.) */
    @Suppress("UNUSED_PARAMETER")
    fun prefetch(context: Context) {}
}

/** A camera scan, to start from a click: [onResult] gets the code's text, [onError] a message to show. Closing the
 *  scanner calls neither. */
@Composable
fun rememberQrScanner(onResult: (String) -> Unit, onError: (String) -> Unit): () -> Unit =
    rememberScanActivity(onResult, onError)
