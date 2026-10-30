package com.esimonsim.apdu

/** The class app_process starts as the card helper. Debug builds start the simulated card
 *  (adb/FakeCardShell) instead while /data/local/tmp/fakecard.json exists, for testing on an
 *  emulator. The shell decides, since only it can see that folder. */
internal const val HELPER_MAIN =
    "\$([ -f /data/local/tmp/fakecard.json ] && echo com.esimonsim.adb.FakeCardShell || echo com.esimonsim.adb.CardShell)"
