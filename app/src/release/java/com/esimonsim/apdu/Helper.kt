package com.esimonsim.apdu

import android.content.Context

/** The class app_process starts as the card helper. */
internal const val HELPER_MAIN = "com.esimonsim.adb.CardShell"

/** Direct card access goes through the phone's own SIM readers only. */
@Suppress("UNUSED_PARAMETER")
internal fun simulatedReaders(context: Context): OmapiApduBackend.Readers? = null
