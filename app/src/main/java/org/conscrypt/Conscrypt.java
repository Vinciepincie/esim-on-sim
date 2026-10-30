package org.conscrypt;

import android.net.ssl.SSLSockets;
import android.os.Build;

import androidx.annotation.RequiresApi;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

/** What libadb's pairing calls by name once {@link OpenSSLProvider} exists. Public API from Android 12; on Android 11,
 *  the only other version with Wireless debugging, the same call is hidden. */
public final class Conscrypt {
    private Conscrypt() {}

    @RequiresApi(30)
    public static byte[] exportKeyingMaterial(SSLSocket socket, String label, byte[] context, int length) throws SSLException {
        if (Build.VERSION.SDK_INT >= 31) return SSLSockets.exportKeyingMaterial(socket, label, context, length);
        try {
            return (byte[]) HiddenApiBypass.invoke(Class.forName("com.android.org.conscrypt.Conscrypt"), null,
                    "exportKeyingMaterial", socket, label, context, length);
        } catch (ReflectiveOperationException e) {
            // Not a wrong code: Pairing.failureMessage has to be able to tell.
            throw new SSLException("Couldn't derive the pairing key", e);
        }
    }
}
