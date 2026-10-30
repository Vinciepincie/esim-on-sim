# app_process loads this entry point by name from the installed APK.
-keep class com.esimonsim.adb.CardShell {
    public static void main(java.lang.String[]);
}
# libadb finds these by name: the shim that points it at Android's own TLS.
-keep class org.conscrypt.OpenSSLProvider { <init>(); }
-keep class org.conscrypt.OpenSSLProvider$Spi { <init>(); }
-keep class org.conscrypt.Conscrypt {
    public static byte[] exportKeyingMaterial(javax.net.ssl.SSLSocket, java.lang.String, byte[], int);
}
