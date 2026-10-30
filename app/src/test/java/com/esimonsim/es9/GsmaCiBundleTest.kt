package com.esimonsim.es9

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** The roots TLS trusts for every SM-DP+. Lists copied from other eSIM apps carry the GSMA's test roots, whose
 *  private keys are public: one pasted back in here would let anyone on the network pass as any provider. */
class GsmaCiBundleTest {

    @Test fun onlyProductionRootsAreTrustedForTls() {
        val certs = File("src/main/res/raw/gsma_ci.pem").inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificates(it)
        }.map { it as X509Certificate }
        val ids = certs.map { c -> c.getExtensionValue("2.5.29.14").takeLast(20).joinToString("") { "%02X".format(it) } }
        assertEquals(setOf("81370F5125D0B1D408D4C3B232E6D25E795BEBFB", // GSMA RSP2 Root CI1
            "4C27967AD20C14B391E9601E41E604AD57C0222F", // OISTE GSMA CI G1
            "16704B7F351E3607F18C4B70005C3A003DFD414A"), ids.toSet()) // Entrust eSIM CA
    }
}
