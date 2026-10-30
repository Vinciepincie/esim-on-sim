package com.esimonsim.es9

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.util.Log
import com.esimonsim.R
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS trust for SM-DP+ (ES9+) servers. Their certificates chain to the GSMA RSP
 * CI roots, which Android's public CA store does not carry. We trust the system
 * CAs OR the bundled GSMA CI roots. (Profile authenticity is enforced by the
 * card<->SM-DP+ certificate exchange, independent of TLS; this only fixes
 * transport trust so the ES9+ HTTPS call can complete.) Production roots only:
 * the private keys of the GSMA's test roots are public, and SGP.26 forbids them
 * in commercial products, so trusting one would let anyone on the network pass.
 */
object CiTls {
    @Volatile private var res: Resources? = null

    /** Built on first use, on the download thread: reading the bundled GSMA CI roots and parsing the
     *  system CA store both stay out of app start-up. Null -> default TLS. */
    val socketFactory: SSLSocketFactory? by lazy { res?.let(::build) }

    /** Only keeps the app's resources: the roots are read with the rest of the TLS setup, on first use. */
    fun init(context: Context) { res = context.applicationContext.resources }

    @SuppressLint("CustomX509TrustManager") // every check delegates to a platform TrustManager
    private fun build(res: Resources): SSLSocketFactory? = try {
        val pem = res.openRawResource(R.raw.gsma_ci).use { it.readBytes() }
        val ciStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        CertificateFactory.getInstance("X.509").generateCertificates(pem.inputStream())
            .forEachIndexed { i, cert -> ciStore.setCertificateEntry("ci$i", cert) }
        val ciTm = trustManager(ciStore)
        val sysTm = trustManager(null)
        // Keep this a plain X509TrustManager with only the 2-argument checks. Conscrypt looks up 3-argument
        // checkServerTrusted methods (with the socket, or the host name) by reflection, by name, on any trust
        // manager; given one, it passes the host name, and with targetSdk 37 that turns on Certificate Transparency.
        // SM-DP+ certificates under the GSMA CI roots carry no SCTs, so those downloads would then fail.
        val composite = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                sysTm.checkClientTrusted(chain, authType)
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                try { sysTm.checkServerTrusted(chain, authType) }
                catch (e: Exception) { ciTm.checkServerTrusted(chain, authType) }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = sysTm.acceptedIssuers + ciTm.acceptedIssuers
        }
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(composite), null) }.socketFactory
    } catch (e: Throwable) {
        Log.w("ES9", "GSMA CI trust unavailable, using default TLS", e)
        null
    }

    private fun trustManager(store: KeyStore?): X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()
}
