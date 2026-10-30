package org.conscrypt;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Provider;
import java.security.SecureRandom;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLContextSpi;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

/** libadb looks this class up by name and asks it for "TLSv1.3", expecting a Conscrypt bundled with the app. Android's
 *  own TLS answers instead: the largest library the app could ship without, and it gets Android's security updates. */
public final class OpenSSLProvider extends Provider {
    public OpenSSLProvider() {
        super("EsimOnSimTls", 1.0, "Android's own TLS, for libadb");
        put("SSLContext.TLSv1.3", Spi.class.getName());
    }

    public static final class Spi extends SSLContextSpi {
        private final SSLContext platform;

        public Spi() throws NoSuchAlgorithmException, NoSuchProviderException {
            // By name: SSLSockets only works on this provider's sockets, and an app can install another one first.
            platform = SSLContext.getInstance("TLSv1.3", "AndroidOpenSSL");
        }

        @Override protected void engineInit(KeyManager[] km, TrustManager[] tm, SecureRandom random) throws KeyManagementException {
            platform.init(km, tm, random);
        }
        @Override protected SSLSocketFactory engineGetSocketFactory() { return platform.getSocketFactory(); }
        @Override protected SSLServerSocketFactory engineGetServerSocketFactory() { return platform.getServerSocketFactory(); }
        @Override protected SSLEngine engineCreateSSLEngine() { return platform.createSSLEngine(); }
        @Override protected SSLEngine engineCreateSSLEngine(String host, int port) { return platform.createSSLEngine(host, port); }
        @Override protected SSLSessionContext engineGetServerSessionContext() { return platform.getServerSessionContext(); }
        @Override protected SSLSessionContext engineGetClientSessionContext() { return platform.getClientSessionContext(); }
    }
}
