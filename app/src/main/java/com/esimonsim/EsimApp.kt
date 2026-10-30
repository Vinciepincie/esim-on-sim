package com.esimonsim

import android.app.Application

class EsimApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Trust the GSMA CI roots for SM-DP+ (ES9+) TLS.
        com.esimonsim.es9.CiTls.init(this)
    }
}
