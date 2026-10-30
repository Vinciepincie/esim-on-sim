package com.esimonsim.adb;

import android.system.Os;
import android.system.OsConstants;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Background mode of the card helper: it keeps running after its ADB stream ends (Wi-Fi off, Wireless
 * debugging off), until the phone restarts, so the app reaches the card without Wireless debugging.
 *
 * <p>Started over the authenticated ADB stream with "serve": the app sends a fresh random key and its uid
 * on stdin, and gets back a port on 127.0.0.1. Each connection on that port proves it holds the key with
 * HMAC over two fresh nonces, and the helper proves it back, so neither side ever sends the key. A
 * connection from any uid but the app's is refused when Android reports it (/proc/net/tcp). Requests are
 * the same JSON lines as on the stream, and each connection owns the channels it opens.
 *
 * <p>Ends when the APK it runs from is gone (the app was updated or uninstalled), when the app asks
 * ("exit"), when a newer one starts, or with adbd: Android ends everything adbd started when adbd stops,
 * which happens when both USB debugging and Wireless debugging are off.
 */
final class HelperServer {
    /** Process name in ps, and how a new helper finds the one it replaces. */
    static final String NAME = "esimonsim-card";
    static final int VERSION = 2;
    private static final int MAX_CONNECTIONS = 4;
    private static final int HANDSHAKE_MS = 5000;
    private static final int APK_CHECK_MS = 30_000;
    /** How long "exit" waits for the card: less than the app waits for its answer, after which it holds no key. */
    private static final int EXIT_WAIT_MS = 2000;

    /** The card state one connection owns: its logical channels. */
    interface Session {
        JSONObject handle(JSONObject request) throws Exception;
        void close();
    }

    interface Sessions { Session open() throws Exception; }

    /** The uid on the other end of a loopback connection: -1 when Android doesn't say, -2 when not found. */
    interface Peers { int uidOf(Socket socket); }

    private final byte[] key;
    private final int appUid;
    private final Sessions sessions;
    private final Peers peers;
    /** The APK this helper runs from; null in tests. */
    private final File apk;
    /** Ends this helper; tests only take note. */
    private final Runnable exit;
    private final SecureRandom random = new SecureRandom();
    private final AtomicInteger connections = new AtomicInteger();
    /** One card request at a time, whichever connection it comes from. */
    private static final Object CARD = new Object();
    /** The app has connected at least once; a helper it never reaches doesn't stay behind. */
    private static volatile boolean used;
    private static final int UNUSED_EXIT_MS = 30_000;

    private HelperServer(byte[] key, int appUid, Sessions sessions, Peers peers, File apk, Runnable exit) {
        this.key = key;
        this.appUid = appUid;
        this.sessions = sessions;
        this.peers = peers;
        this.apk = apk;
        this.exit = exit;
    }

    /** Serves connections on [server] until it closes. [exit] runs when the app asks this helper to end. */
    static void serve(ServerSocket server, byte[] key, int appUid, Sessions sessions, Peers peers, Runnable exit) throws IOException {
        new HelperServer(key, appUid, sessions, peers, null, exit).accept(server);
    }

    /** Runs as the helper's main: reads the "serve" request from stdin, answers with the port, then serves
     * until one of the ends above. Never returns. */
    static void run(Sessions sessions) {
        try {
            JSONObject q = new JSONObject(readLine(System.in));
            if (!"serve".equals(q.optString("op"))) throw new IllegalArgumentException("Expected serve");
            byte[] key = unhex(q.getString("key"));
            int uid = q.getInt("uid");
            if (key.length != 32 || uid < 10_000) throw new IllegalArgumentException("Invalid serve request");
            // adbd hangs up on the helper when the stream ends; the app's start command makes it ignore that.
            if (!ignoresHangup()) throw new IllegalStateException("Hangup not ignored");
            stopOthers();
            ServerSocket server = new ServerSocket(0, MAX_CONNECTIONS, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}));
            reply(new JSONObject().put("ok", true).put("port", server.getLocalPort()).put("version", VERSION));
            daemon("stdin", HelperServer::detachWhenStreamEnds);
            daemon("apk", HelperServer::exitWhenApkGone);
            // The app connects right after the reply. If it never does (its start failed after all), nothing
            // else holds the key, so this helper would only sit there until the phone restarts.
            daemon("unused", () -> {
                try { Thread.sleep(UNUSED_EXIT_MS); } catch (InterruptedException e) { return; }
                if (!used) System.exit(0);
            });
            File apk = new File(System.getProperty("java.class.path", ""));
            new HelperServer(key, uid, sessions, HelperServer::peerUid, apk, () -> System.exit(0)).accept(server);
        } catch (Exception e) {
            // Do not log keys, requests or card data.
            try { reply(new JSONObject().put("ok", false).put("error", e.getClass().getSimpleName() + ": " + e.getMessage())); }
            catch (Exception ignored) { }
        }
        System.exit(0);
    }

    private void accept(ServerSocket server) throws IOException {
        while (true) {
            Socket socket = server.accept();
            // Other apps are turned away before they can hold a connection slot, when Android says who they are.
            // Only 127.0.0.1 itself: the rest of 127/8 reaches this socket too, and makes table rows ambiguous.
            int peer = isLocalhost(socket.getInetAddress()) ? peers.uidOf(socket) : -2;
            if (peer != -1 && peer != appUid) {
                closeQuietly(socket);
                continue;
            }
            if (connections.incrementAndGet() > MAX_CONNECTIONS) {
                connections.decrementAndGet();
                closeQuietly(socket);
                continue;
            }
            daemon("connection", () -> {
                try { serve(socket); }
                catch (Exception ignored) { }
                finally { closeQuietly(socket); connections.decrementAndGet(); }
            });
        }
    }

    private void serve(Socket socket) throws Exception {
        // Updated or removed meanwhile: serve nothing from old code; the app starts a new helper.
        if (apk != null && !apk.exists()) System.exit(0);
        long deadline = System.nanoTime() + HANDSHAKE_MS * 1_000_000L;
        InputStream in = new BufferedInputStream(socket.getInputStream());
        OutputStream out = socket.getOutputStream();
        String helperNonce = hex(nonce());
        send(out, new JSONObject().put("ok", true).put("hello", VERSION).put("nonce", helperNonce));
        JSONObject auth = new JSONObject(handshakeLine(socket, in, deadline));
        String appNonce = auth.getString("nonce");
        if (!"auth".equals(auth.optString("op")) || appNonce.length() != 32) throw new SecurityException("Bad handshake");
        if (!MessageDigest.isEqual(unhex(auth.getString("mac")), mac("app", helperNonce, appNonce))) {
            send(out, new JSONObject().put("ok", false).put("error", "SecurityException: Not allowed"));
            return;
        }
        send(out, new JSONObject().put("ok", true).put("mac", hex(mac("helper", appNonce, helperNonce)))
                .put("version", VERSION).put("apk", System.getProperty("java.class.path", "")));
        socket.setSoTimeout(0); // the app keeps its connection open while it runs

        Session session = sessions.open();
        used = true;
        try {
            String line;
            while ((line = readLine(in)) != null) {
                JSONObject request = new JSONObject(line);
                String op = request.optString("op");
                if ("exit".equals(op)) {
                    // A stuck card request can hold CARD for good, and "Stop card access" promises nothing keeps running.
                    daemon("exit", () -> {
                        try { Thread.sleep(EXIT_WAIT_MS); } catch (InterruptedException e) { return; }
                        exit.run();
                    });
                    synchronized (CARD) { session.close(); }
                    send(out, new JSONObject().put("ok", true));
                    exit.run();
                    return;
                }
                JSONObject response;
                // A ping never touches the card, so it answers even while another connection waits on a stuck one.
                try {
                    if ("ping".equals(op)) response = session.handle(request).put("ok", true);
                    else synchronized (CARD) { response = session.handle(request).put("ok", true); }
                }
                catch (Exception e) { response = error(e); }
                send(out, response);
            }
        } finally {
            synchronized (CARD) { session.close(); }
        }
    }

    private byte[] mac(String who, String first, String second) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal((who + ":" + first + ":" + second).getBytes(StandardCharsets.UTF_8));
    }

    private byte[] nonce() {
        byte[] n = new byte[16];
        random.nextBytes(n);
        return n;
    }

    static JSONObject error(Exception e) {
        Throwable cause = e;
        while (cause.getCause() != null) cause = cause.getCause();
        try {
            return new JSONObject().put("ok", false).put("error", cause.getClass().getSimpleName() + ": " + cause.getMessage());
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    /** The uid of the process on the other end of this connection from 127.0.0.1, as Android lists it in
     * /proc/net/tcp6 and tcp: the row of the peer's own socket, 127.0.0.1:peer port to 127.0.0.1:our port.
     * -1 when neither table can be read (the HMAC still has to match); -2 when no single row says. */
    static int peerUid(Socket socket) {
        boolean read = false;
        int found = -1;
        String[][] tables = {{"/proc/net/tcp6", LOOPBACK6}, {"/proc/net/tcp", LOOPBACK4}};
        for (String[] table : tables) {
            try (BufferedReader r = new BufferedReader(new FileReader(table[0]))) {
                read = true;
                int uid = uidIn(r, table[1], socket.getPort(), socket.getLocalPort());
                if (uid == -2 || (uid >= 0 && found >= 0 && uid != found)) return -2;
                if (uid >= 0) found = uid;
            } catch (Exception ignored) { }
        }
        if (!read) return -1;
        return found >= 0 ? found : -2; // readable but missing: never matches the app's uid
    }

    /** 127.0.0.1 as the tables write it: host byte order (little-endian), and IPv4-mapped in tcp6. */
    static final String LOOPBACK4 = "0100007F", LOOPBACK6 = "0000000000000000FFFF00000100007F";

    /** The uid column of the one row for [loopback]:[peerPort] connected to [loopback]:[ownPort]: -1 when
     * there is none, -2 when rows disagree. */
    static int uidIn(BufferedReader table, String loopback, int peerPort, int ownPort) throws IOException {
        String local = String.format("%s:%04X", loopback, peerPort), remote = String.format("%s:%04X", loopback, ownPort);
        int found = -1;
        String line = table.readLine(); // header
        while ((line = table.readLine()) != null) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 8 || !f[1].equalsIgnoreCase(local) || !f[2].equalsIgnoreCase(remote)) continue;
            int uid = Integer.parseInt(f[7]);
            if (found >= 0 && uid != found) return -2;
            found = uid;
        }
        return found;
    }

    /** Exactly 127.0.0.1, also when the socket reports it IPv4-mapped. */
    static boolean isLocalhost(InetAddress address) {
        byte[] b = address.getAddress();
        if (b.length == 16) {
            for (int i = 0; i < 10; i++) if (b[i] != 0) return false;
            if (b[10] != (byte) 0xFF || b[11] != (byte) 0xFF) return false;
            b = Arrays.copyOfRange(b, 12, 16);
        }
        return Arrays.equals(b, new byte[] {127, 0, 0, 1});
    }

    /** A handshake line, with [deadline] (System.nanoTime) for the whole of it: a peer trickling one byte at
     * a time can't hold a connection slot past the handshake time. */
    private static String handshakeLine(Socket socket, InputStream in, long deadline) throws IOException {
        StringBuilder line = new StringBuilder();
        while (true) {
            long left = (deadline - System.nanoTime()) / 1_000_000L;
            if (left <= 0) throw new SocketTimeoutException("Handshake too slow");
            socket.setSoTimeout((int) left);
            int c = in.read();
            if (c == -1 || c == '\n') return line.toString();
            if (line.length() >= 512) throw new IOException("Handshake too large");
            line.append((char) c);
        }
    }

    /** SIGHUP in this process's ignored-signal mask (/proc/self/status SigIgn, bit 0). */
    private static boolean ignoresHangup() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("SigIgn:")) return (Long.parseLong(line.substring(7).trim(), 16) & 1) != 0;
            }
        } catch (Exception ignored) { }
        return false;
    }

    /** One helper at a time: a new one replaces any left running, e.g. after the app's data was cleared. */
    private static void stopOthers() {
        int self = android.os.Process.myPid();
        File[] all = new File("/proc").listFiles();
        if (all == null) return;
        for (File dir : all) {
            int pid;
            try { pid = Integer.parseInt(dir.getName()); } catch (NumberFormatException e) { continue; }
            if (pid == self) continue;
            try (InputStream in = new FileInputStream(new File(dir, "cmdline"))) {
                byte[] cmdline = new byte[64]; // the process name comes first, and ours is short
                int length = Math.max(in.read(cmdline), 0);
                int end = 0;
                while (end < length && cmdline[end] != 0) end++;
                if (NAME.equals(new String(cmdline, 0, end, StandardCharsets.UTF_8))) Os.kill(pid, OsConstants.SIGKILL);
            } catch (Exception ignored) { }
        }
    }

    /** The app closes the ADB stream once it has the port. From then on nothing reads or writes stdio, which
     * points at /dev/null so a late write can't fail on the closed stream. */
    private static void detachWhenStreamEnds() {
        try { while (System.in.read() != -1) { } } catch (IOException ignored) { }
        try {
            FileDescriptor nul = Os.open("/dev/null", OsConstants.O_RDWR, 0);
            Os.dup2(nul, 0);
            Os.dup2(nul, 1);
            Os.dup2(nul, 2);
        } catch (Exception ignored) { }
    }

    /** An updated or removed app deletes the APK this helper's code comes from: the next one starts fresh. */
    private static void exitWhenApkGone() {
        File apk = new File(System.getProperty("java.class.path", ""));
        while (true) {
            try { Thread.sleep(APK_CHECK_MS); } catch (InterruptedException e) { return; }
            if (!apk.exists()) System.exit(0);
        }
    }

    private static void daemon(String name, Runnable body) {
        Thread t = new Thread(body, "helper-" + name);
        t.setDaemon(true);
        t.start();
    }

    private static void reply(JSONObject response) {
        System.out.print("EOS:" + response + "\n");
        System.out.flush();
    }

    private static void send(OutputStream out, JSONObject response) throws IOException {
        out.write(("EOS:" + response + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** One request line, at most 8 KB; null at the end of the stream. */
    static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return line.toString();
            if (line.length() >= 8192) throw new IOException("Request too large");
            line.append((char) c);
        }
        return line.length() == 0 ? null : line.toString();
    }

    private static void closeQuietly(Socket socket) {
        try { socket.close(); } catch (IOException ignored) { }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[2 * i] = HEX[(bytes[i] >> 4) & 0xF];
            out[2 * i + 1] = HEX[bytes[i] & 0xF];
        }
        return new String(out);
    }

    static byte[] unhex(String s) {
        if (s.length() % 2 != 0 || s.length() > 128) throw new IllegalArgumentException("Bad hex");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16), lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("Bad hex");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
