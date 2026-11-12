package com.esimonsim.adb;

import android.os.Binder;
import android.os.Build;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/** Runs only as ADB shell or root via app_process. No exported Android services. Requests arrive over the
 * authenticated ADB stream, or the input su or Shizuku gave it, or, started with "serve", over loopback connections
 * that proved they hold the app's key (see {@link HelperServer}). Channels belong to the stream or connection that
 * opened them. Works on Android 6 to 17, whose hidden telephony calls differ (see {@link Route}).
 */
public final class CardShell implements HelperServer.Session {
    private static final String TELEPHONY = "com.android.internal.telephony.";
    private static final String SHELL = "com.android.shell";
    private static final String SETTINGS = "/system/bin/settings";
    /** IccOpenLogicalChannelResponse.STATUS_MISSING_RESOURCE: what Android answers for a slot without a card. */
    private static final int STATUS_MISSING_RESOURCE = 2;
    /** IccOpenLogicalChannelResponse's other two failures: the card has no such applet, or failed some other way. */
    private static final int STATUS_NO_SUCH_ELEMENT = 3, STATUS_UNKNOWN_ERROR = 4;
    /** How long a helper waits for its first request. */
    private static final long UNCLAIMED_MS = 30_000;
    /** No subscription to reach a slot's card through (see {@link #subIdFor}). */
    private static final int NO_SUB = Integer.MIN_VALUE;
    /** UiccSlotInfo.CARD_STATE_INFO_ABSENT. */
    private static final int CARD_ABSENT = 1;
    private static final Pattern EID = Pattern.compile("[0-9]{32}");
    private static final Class<?> I = int.class, S = String.class;

    /** The eSIM manager IDs a request may open, the usual one first: most cards answer to it, and some makers' only
     * to their own. Kept here, not in the app's Lpa, as the helper shouldn't load what that brings with it. The last
     * is the whole standard ID in lower case: a card that won't take the prefix still selects ISD-R from it, and the
     * lower case still slips past the Android 13+ telephony check that keeps the full ID (compared as upper-case text)
     * for the system's own eSIM app. Tried last, so a card that takes the prefix keeps using it. */
    public static final List<String> ISD_R_AIDS = Collections.unmodifiableList(Arrays.asList(
            "A0000005591010FFFFFFFF89000001",     // the usual one, as a prefix: Lpa.ISD_R_OPEN
            "A0000005591010000000008900000300",   // eSIM.me
            "A0000005591010FFFFFFFF8900050500",   // 5ber
            "A0000005591010FFFFFFFF8900000177",   // XeSIM
            "A000000559104C696E6B736669656C64",   // LinksField
            "A06573746B6D65FFFF4953442D522030",   // ESTKme, SE0
            "A06573746B6D65FFFF4953442D522031",   // ESTKme, SE1
            "A06573746B6D65FFFFFFFF4953442D52",   // ESTKme, the older ID
            "a0000005591010ffffffff8900000100")); // the whole standard ID, lower case: Lpa.ISD_R_LOWER

    /** How channels name the card, newest first. Android 13+ takes an IccLogicalChannelRequest with the slot,
     * 10-12L the slot itself. 7-9 take the subscription Android has for the card, the only way they have to name
     * another slot. Android 6 always uses the first SIM's card. */
    private enum Route { REQUEST, SLOT, SUB, DEFAULT }

    /** What transmitting on an open channel and closing it need again. A non-null [seHal] marks a channel the Secure
     * Element HAL opened, which transmits and closes through that service instead of telephony, on its [reader]. */
    private static final class Channel {
        final Object request; // Android 13+
        final int subId;      // Android 7-9
        final Object seHal;   // the Secure Element HAL this channel belongs to, or null for a telephony channel
        final int reader;     // that HAL's reader, from 0 for SIM1; -1 for a telephony channel
        Channel(Object request, int subId, Object seHal, int reader) {
            this.request = request; this.subId = subId; this.seHal = seHal; this.reader = reader;
        }
    }

    /** Runs one of Android's own commands, such as settings, and returns what it printed; throws when it fails. */
    interface Commands { String run(String... command) throws Exception; }

    /** The Secure Element AIDL HAL (android.hardware.secure_element.ISecureElement), which the app asks for when the
     * phone's own telephony found no card: its [reader] (0 for SIM1), or null when the phone has none the helper's user
     * may reach. Remembered once found; a reader that isn't there, or refused the helper, is asked again after {@link
     * #HAL_RETRY_MS} (see {@link #secureElement}). */
    interface SecureElement { Object reader(int reader) throws Exception; }

    /** The slot's Secure Element HAL process went away, and every channel it had open with it. */
    static final class HalGoneException extends IllegalStateException {
        HalGoneException() { super("The card's secure element service went away"); }
    }

    /** Milliseconds that only ever go forward: when to look for the Secure Element HAL again. */
    interface Clock { long millis(); }

    /** How long a Secure Element HAL that wasn't there, or refused the helper, is left alone before it's asked again:
     * the phone's own secure-element app may not have been up yet, and a helper can run for days. */
    static final long HAL_RETRY_MS = 60_000;
    private static final String HAL = "android.hardware.secure_element.ISecureElement";
    /** Android's own secure-element app, which brings the HAL's Java interface with it. */
    private static final String SE_PACKAGE = "com.android.se";
    /** PackageManager.MATCH_SYSTEM_ONLY: an app installed later under the same name never counts. */
    private static final long MATCH_SYSTEM_ONLY = 0x00100000;
    /** An APK on one of Android's read-only partitions, the only place the helper loads code from. */
    private static final Pattern SYSTEM_APK =
            Pattern.compile("/(system|system_ext|product|vendor|odm|apex)/[A-Za-z0-9_.@+-]+(/[A-Za-z0-9_.@+-]+)*\\.apk");

    /** Runs [check] on a thread of its own every [ms], at most [times] times, until it returns true. */
    interface Repeat { void every(long ms, int times, Callable<Boolean> check); }

    /** The latest roaming watch (see {@link #keepRoamingOn}), in any session: an earlier one stops. */
    private static final AtomicInteger ROAMING_WATCH = new AtomicInteger();

    private final Class<?> api;
    private final Callable<Object> phones;
    private final Class<?> requestType;
    private final Class<?> subApi;
    private final Callable<Object> subs;
    /** Gives the Secure Element HAL's interface for reflection, or null where the phone has no such AIDL HAL (before
     * Android 14, or not declared). Asked only once the app asks for the HAL: see {@link #seApi()}. {@link #se} finds
     * each reader. */
    private final Callable<Class<?>> seApis;
    private final SecureElement se;
    private final Clock clock;
    /** The HAL's interface once found, and when to look for it again while it isn't. */
    private Class<?> seApi;
    private long seApiRetry = Long.MIN_VALUE;
    /** A slot's Secure Element reader once {@link #secureElement} found one, and until when the slots it found none for
     * (or whose HAL refused the helper) are left alone. */
    private final Map<Integer, Object> seReaders = new HashMap<>();
    private final Map<Integer, Long> seAbsent = new HashMap<>();
    private final Commands commands;
    /** Android's API level. */
    private final int sdk;
    private final Repeat repeat;
    /** Null when this Android has none of the known channel calls: opening one then says so. */
    private final Route route;
    /** Looked up once instead of on each APDU: an install sends hundreds. */
    private final Method open, transmit, close;
    /** Android 13+ transmits by slot and port; earlier versions only reach port 0. */
    private final boolean byPort;
    /** Android 17 QPR2's transmit by port also takes the calling package, after the slot and port. */
    private final boolean transmitNamesCaller;
    private final Map<String, Channel> channels = new HashMap<>();
    private Object phone;
    private Object sub;
    private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]*");

    private CardShell() throws Exception {
        this(Class.forName(TELEPHONY + "ITelephony"), () -> service("phone", "ITelephony", "Telephony"),
                optionalClass(TELEPHONY + "IccLogicalChannelRequest"), Class.forName(TELEPHONY + "ISub"),
                () -> service("isub", "ISub", "Subscription"), CardShell::exec, Build.VERSION.SDK_INT,
                CardShell::inBackground, CardShell::secureElementApi, CardShell::secureElementReader,
                android.os.SystemClock::elapsedRealtime);
    }

    /** [phones] gives what implements [api] and [subs] what implements [subApi], once they're needed, and again after
     * the phone process restarted. [seApis] gives the Secure Element HAL interface (null where there is none) and [se]
     * a reader, both only once the app asks for the HAL. Tests pass stand-ins with the methods of Android version [sdk],
     * run [repeat]'s checks themselves, and move [clock] by hand. */
    CardShell(Class<?> api, Callable<Object> phones, Class<?> requestType, Class<?> subApi, Callable<Object> subs,
              Commands commands, int sdk, Repeat repeat, Callable<Class<?>> seApis, SecureElement se, Clock clock) {
        this.api = api;
        this.phones = phones;
        this.requestType = requestType;
        this.subApi = subApi;
        this.subs = subs;
        this.seApis = seApis;
        this.se = se;
        this.clock = clock;
        this.commands = commands;
        this.sdk = sdk;
        this.repeat = repeat;
        // Matched by name and parameter types: counts alone are ambiguous, as Android 13's request calls take one
        // parameter like Android 6's. A way in counts only with all three calls.
        Route r = null;
        Method[] found = null;
        if (requestType != null) { // Android 13+
            Method t = find(api, "iccTransmitApduLogicalChannelByPort", I, I, I, I, I, I, I, I, S);
            // Android 17 QPR2 adds the calling package after the slot and port.
            if (t == null) t = find(api, "iccTransmitApduLogicalChannelByPort", I, I, S, I, I, I, I, I, I, S);
            if (t == null) t = find(api, "iccTransmitApduLogicalChannelBySlot", I, I, I, I, I, I, I, S);
            found = all(find(api, "iccOpenLogicalChannel", requestType), t,
                    find(api, "iccCloseLogicalChannel", requestType));
            r = Route.REQUEST;
        }
        if (found == null) { // Android 10-12L
            found = all(find(api, "iccOpenLogicalChannelBySlot", I, S, S, I),
                    find(api, "iccTransmitApduLogicalChannelBySlot", I, I, I, I, I, I, I, S),
                    find(api, "iccCloseLogicalChannelBySlot", I, I));
            r = Route.SLOT;
        }
        if (found == null) {
            Method o = find(api, "iccOpenLogicalChannel", I, S, S, I);  // Android 8.1-9: subId, callingPackage, AID, p2
            if (o == null) o = find(api, "iccOpenLogicalChannel", I, S, I); // Android 8.0: subId, AID, p2
            if (o == null) o = find(api, "iccOpenLogicalChannel", I, S);    // Android 7.x: subId, AID
            found = all(o, find(api, "iccTransmitApduLogicalChannel", I, I, I, I, I, I, I, S),
                    find(api, "iccCloseLogicalChannel", I, I));
            r = Route.SUB;
        }
        if (found == null) { // Android 6
            found = all(find(api, "iccOpenLogicalChannel", S),
                    find(api, "iccTransmitApduLogicalChannel", I, I, I, I, I, I, S),
                    find(api, "iccCloseLogicalChannel", I));
            r = Route.DEFAULT;
        }
        route = found == null ? null : r;
        open = found == null ? null : found[0];
        transmit = found == null ? null : found[1];
        close = found == null ? null : found[2];
        int transmitArgs = transmit == null ? 0 : transmit.getParameterTypes().length;
        byPort = route == Route.REQUEST && transmitArgs >= 9;
        transmitNamesCaller = route == Route.REQUEST && transmitArgs == 10;
    }

    public static void main(String[] args) {
        // Both hold the telephony permissions the card needs; root also passes the calling-package checks. Shizuku's
        // server runs as either.
        int uid = android.os.Process.myUid();
        if (uid != 2000 && uid != 0) {
            System.err.println("Card helper stopped: ADB shell or root required");
            System.exit(1);
        }
        Looper.prepareMainLooper();
        if (args.length > 0 && args[0].equals("serve")) {
            // Background mode is for Wireless debugging only: su and Shizuku use a pipe, and end with the app. Some
            // phones' debugging runs as root, and nothing should outlive the app as root.
            if (uid != 2000) {
                System.err.println("Card helper stopped: background mode needs ADB shell");
                System.exit(1);
            }
            HelperServer.run(CardShell::new);
        }
        // Whoever started this writes at once. Under Shizuku an app that dies in that moment leaves nothing to end this:
        // its input would just stay open, as root too.
        Thread unclaimed = new Thread(() -> {
            try { Thread.sleep(UNCLAIMED_MS); } catch (InterruptedException e) { return; }
            System.exit(0);
        }, "unclaimed");
        unclaimed.setDaemon(true);
        unclaimed.start();
        CardShell server = null;
        try {
            server = new CardShell();
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = readLine(input)) != null) {
                unclaimed.interrupt();
                JSONObject response;
                try { response = server.handle(new JSONObject(line)).put("ok", true); }
                catch (Exception e) {
                    Throwable cause = e;
                    while (cause.getCause() != null) cause = cause.getCause();
                    response = new JSONObject().put("ok", false)
                            .put("error", cause.getClass().getSimpleName() + ": " + cause.getMessage());
                }
                System.out.print("EOS:" + response + "\n"); // one write: println sends the newline on its own
                System.out.flush();
            }
        } catch (Exception e) {
            // Do not log commands, responses, card identifiers, or activation material.
            System.err.println("Card helper stopped: " + e.getClass().getSimpleName());
        } finally {
            if (server != null) server.closeAll();
            System.exit(0); // Binder threads otherwise keep app_process alive after stream EOF.
        }
    }

    private static String readLine(BufferedReader input) throws Exception {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = input.read()) != -1) {
            if (c == '\n') return line.toString();
            if (line.length() >= 8192) throw new IllegalArgumentException("Request too large");
            line.append((char) c);
        }
        return null;
    }

    @Override public void close() { closeAll(); }

    @Override public JSONObject handle(JSONObject q) throws Exception {
        try { return answer(q); }
        catch (Exception e) {
            if (phoneDied(e)) forgetPhone();
            throw e;
        }
    }

    /** Whether [e] comes from the phone process having gone: it restarts by itself when it crashes, and every call to
     * the one this holds fails from then on. */
    private static boolean phoneDied(Throwable e) {
        for (; e != null; e = e.getCause()) if (e instanceof DeadObjectException) return true;
        return false;
    }

    /** After {@link #phoneDied}: the next request fetches the new process, for both services, as Android's SIM list
     * lives there too. So "Try again" works, and the helper, which may take Wireless debugging to start again, stays.
     * The request that found out still fails: it may have reached the card, and nothing here repeats one. Its channels
     * went with the old process. */
    private void forgetPhone() {
        closeAll();
        phone = null;
        sub = null;
    }

    private JSONObject answer(JSONObject q) throws Exception {
        String op = q.getString("op");
        JSONObject out = new JSONObject();
        if (op.equals("ping")) return out.put("version", 1).put("uid", android.os.Process.myUid());
        if (op.equals("useSim")) return useSim(q, out);
        if (op.equals("slots")) return slots(out);
        if (op.equals("cardRecords")) return cardRecords(q.getString("eid"), out);
        int slot = bounded(q, "slot", 7), port = bounded(q, "port", 7);
        if (!byPort && port != 0) throw new IllegalArgumentException("Only port zero is supported");
        if (op.equals("open")) {
            String aid = q.getString("aid");
            if (!ISD_R_AIDS.contains(aid)) throw new IllegalArgumentException("Unsupported card applet");
            // "hal": that Secure Element HAL [reader] only (the app asks once telephony found nothing). Otherwise
            // telephony only: 0.5's automatic fallback on each miss read a Pixel's own chip as the card (issue #1).
            String via = q.optString("via", "telephony");
            if (via.equals("hal")) {
                int reader = q.has("reader") ? bounded(q, "reader", 7) : slot;
                Opened s = openViaSecureElement(reader, aid);
                if (s.channel > 0) {
                    if (channels.containsKey(slot + ":" + port + ":" + s.channel)) {
                        // A telephony channel on this slot has that number: given back, as one key can't name both.
                        try { halCall(reader, s.seHal, "closeChannel", (byte) s.channel); } catch (Exception ignored) { }
                        throw new IllegalStateException("Channel number already in use");
                    }
                    storeSecureElement(slot, port, reader, s);
                }
                return s.json(out);
            }
            if (!via.equals("telephony")) throw new IllegalArgumentException("Unsupported route");
            if (route == null) throw new UnsupportedOperationException("This phone has no known way to reach the card");
            Opened t = openViaTelephony(slot, port, aid);
            if (t.channel > 0) storeTelephony(slot, port, t);
            return t.json(out);
        }
        int channel = bounded(q, "channel", 255);
        String key = slot + ":" + port + ":" + channel;
        Channel owned = channels.get(key);
        if (owned == null) throw new IllegalArgumentException("Channel is not owned by this session");
        if (op.equals("close")) {
            // Forget it even when closing fails: after a card restart the channel no longer exists.
            try {
                if (owned.seHal != null) halCall(owned.reader, owned.seHal, "closeChannel", (byte) channel);
                else closeChannel(slot, port, channel, owned);
            } finally { channels.remove(key); }
            return out;
        }
        if (!op.equals("transmit")) throw new IllegalArgumentException("Unsupported operation");
        int cla = bounded(q, "cla", 255), ins = bounded(q, "ins", 255);
        int p1 = bounded(q, "p1", 255), p2 = bounded(q, "p2", 255), p3 = bounded(q, "p3", 255);
        String data = q.getString("data");
        if (data.length() > 510 || data.length() % 2 != 0 || !HEX.matcher(data).matches())
            throw new IllegalArgumentException("Invalid APDU data");
        if (ins != 0xE2 && ins != 0xC0) throw new IllegalArgumentException("Only ES10 and GET RESPONSE are supported");
        if (ins == 0xE2 && data.length() != p3 * 2) throw new IllegalArgumentException("Invalid APDU length");
        if (owned.seHal != null) {
            // The HAL sends the APDU straight to the card, so the channel goes in the class byte here, the way Android's
            // own secure-element service sets it (ISO 7816-4). Telephony takes the channel as its own argument instead.
            byte[] answer;
            try { answer = (byte[]) halCall(owned.reader, owned.seHal, "transmit", (Object) apdu(channel, cla, ins, p1, p2, p3, data)); }
            catch (Exception e) {
                // The HAL's process restarting takes its channels with it, and the number can go to another of its
                // users, Android's own secure-element app among them: forgotten, so nothing here closes that one later.
                // Any other failure keeps it, so the app's close gives it back, as Android's own secure-element app does.
                if (e instanceof HalGoneException) channels.remove(key);
                throw e;
            }
            if (answer == null || answer.length < 2) throw new IllegalStateException("The card gave no answer");
            // 6881: the card says this channel isn't open, as after it restarted. Forgotten too, for the same reason.
            if (answer[answer.length - 2] == 0x68 && answer[answer.length - 1] == (byte) 0x81) channels.remove(key);
            return out.put("response", hex(answer));
        }
        Object result = transmit.invoke(phone(),
                concat(where(slot, port, owned), channel, cla, ins, p1, p2, p3, data));
        // Some versions answer a channel they don't know with no status word at all: an error, as the app needs one.
        if (!(result instanceof String) || ((String) result).length() < 4)
            throw new IllegalStateException("The card gave no answer");
        return out.put("response", result);
    }

    /** One open attempt's answer, from either transport. [request]/[subId] carry a telephony channel into transmit and
     * close; [seHal] non-null marks a Secure Element HAL channel. */
    private static final class Opened {
        int channel;
        int status;
        String select = "";
        Object request;
        int subId = -1;
        Object seHal;
        JSONObject json(JSONObject out) throws Exception {
            return out.put("channel", channel).put("status", status).put("select", select);
        }
        /** A slot this transport can't reach: no card there, as far as the app goes. */
        static Opened missing() { return failed(STATUS_MISSING_RESOURCE); }
        /** No channel, for the reason telephony's [status] gives. */
        static Opened failed(int status) {
            Opened o = new Opened();
            o.status = status;
            return o;
        }
    }

    /** Opens [aid] on [slot]/[port] through the phone's telephony, as each Android version allows (see {@link Route}),
     * or {@link Opened#missing} for a slot this version can't reach. */
    private Opened openViaTelephony(int slot, int port, String aid) throws Exception {
        Object request = null;
        int subId = -1;
        Object[] args;
        switch (route) {
            case REQUEST:
                request = requestType.getConstructor().newInstance();
                requestType.getField("aid").set(request, aid);
                requestType.getField("callingPackage").set(request, SHELL);
                requestType.getField("slotIndex").setInt(request, slot);
                requestType.getField("portIndex").setInt(request, port);
                requestType.getField("subId").setInt(request, -1);
                requestType.getField("p2").setInt(request, 0);
                requestType.getField("binder").set(request, new Binder());
                args = new Object[] {request};
                break;
            case SLOT:
                args = new Object[] {slot, SHELL, aid, 0};
                break;
            case SUB:
                subId = subIdFor(slot);
                if (subId == NO_SUB) return Opened.missing();
                int n = open.getParameterTypes().length;
                args = n == 4 ? new Object[] {subId, SHELL, aid, 0}
                        : n == 3 ? new Object[] {subId, aid, 0} : new Object[] {subId, aid};
                break;
            default:
                if (slot != 0) return Opened.missing();
                args = new Object[] {aid};
        }
        Object response = open.invoke(phone(), args);
        if (response == null) throw new IllegalStateException("Channel open returned no response");
        Opened o = new Opened();
        o.channel = (int) response.getClass().getMethod("getChannel").invoke(response);
        o.status = (int) response.getClass().getMethod("getStatus").invoke(response);
        o.select = hex((byte[]) response.getClass().getMethod("getSelectResponse").invoke(response));
        o.request = request;
        o.subId = subId;
        return o;
    }

    private void storeTelephony(int slot, int port, Opened o) throws Exception {
        if (o.request != null) requestType.getField("channel").setInt(o.request, o.channel);
        channels.put(slot + ":" + port + ":" + o.channel, new Channel(o.request, o.subId, null, -1));
    }

    /** Opens [aid] on the Secure Element HAL's [reader] (0 for SIM1), where the phone has one the helper's user may
     * reach. The HAL opens the logical channel and selects the applet in one call, below the OMAPI app check telephony
     * enforces. Never inits or resets the HAL: it is shared with Android's own secure-element service and other apps'
     * channels, and works only once that service has set it up. Answers as telephony would: no card ({@link
     * Opened#missing}) where there is no such reader, it refused the helper, or it has no card; no such applet, so the
     * app tries its next ID, where opening failed. Which slot's card a reader reaches is for the app to check. */
    private Opened openViaSecureElement(int reader, String aid) { return openViaSecureElement(reader, aid, true); }

    /** [again]: a reader whose process turned out to have restarted is looked up afresh and asked once more, as the new
     * one is usually up by then. */
    private Opened openViaSecureElement(int reader, String aid, boolean again) {
        Object hal = secureElement(reader);
        if (hal == null) return Opened.missing();
        try {
            Boolean present = (Boolean) halCall(reader, hal, "isCardPresent");
            if (present != null && !present) return Opened.missing();
            Object response = halCall(reader, hal, "openLogicalChannel", bytes(aid), (byte) 0);
            if (response == null) return Opened.failed(STATUS_UNKNOWN_ERROR);
            int ch = ((Number) response.getClass().getField("channelNumber").get(response)).intValue() & 0xFF;
            if (ch == 0) return Opened.missing();
            if (ch > 19) {
                // The HAL promises 1-19, the channels a class byte can name: any other is given back unused.
                try { halCall(reader, hal, "closeChannel", (byte) ch); } catch (Exception ignored) { }
                return Opened.failed(STATUS_UNKNOWN_ERROR);
            }
            Opened o = new Opened();
            o.channel = ch;
            o.select = hex((byte[]) response.getClass().getField("selectResponse").get(response));
            o.seHal = hal;
            return o;
        } catch (HalGoneException e) {
            return again ? openViaSecureElement(reader, aid, false) : Opened.failed(STATUS_UNKNOWN_ERROR);
        } catch (Exception e) {
            // No such applet or no free channel. A refusal leaves the reader alone for a while (see halCall), so the
            // next ID finds no reader: no card, which ends the app's look there.
            return Opened.failed(STATUS_NO_SUCH_ELEMENT);
        }
    }

    private void storeSecureElement(int slot, int port, int reader, Opened o) {
        channels.put(slot + ":" + port + ":" + o.channel, new Channel(null, -1, o.seHal, reader));
    }

    /** The HAL's interface, looked up the first time the app asks for the HAL, so a phone telephony serves never pays
     * for it. Where it can't be found it's looked for again after {@link #HAL_RETRY_MS}. */
    private Class<?> seApi() {
        if (seApi != null || seApis == null) return seApi;
        long now = clock.millis();
        if (now < seApiRetry) return null;
        try { seApi = seApis.call(); } catch (Throwable t) { seApi = null; } // a missing class is an Error
        if (seApi == null) seApiRetry = now + HAL_RETRY_MS;
        return seApi;
    }

    /** The Secure Element HAL's [reader] (0 for SIM1), found once and remembered; null where the phone has none the
     * helper's user may reach, and then not looked for again for {@link #HAL_RETRY_MS}. */
    private Object secureElement(int reader) {
        if (se == null || reader < 0 || seApi() == null) return null;
        Object hal = seReaders.get(reader);
        if (hal != null) return hal;
        long now = clock.millis();
        Long retry = seAbsent.get(reader);
        if (retry != null && now < retry) return null;
        try { hal = se.reader(reader); } catch (Throwable t) { hal = null; }
        if (hal == null) { seAbsent.put(reader, now + HAL_RETRY_MS); return null; }
        seAbsent.remove(reader);
        seReaders.put(reader, hal);
        return hal;
    }

    /** A call into the Secure Element HAL's [reader]. Java gives a DeadObjectException both when that HAL's process went
     * away and when SELinux keeps the helper's user from calling it (the usual case for ADB shell: it may look the HAL
     * up, not call it), the same exception as for the phone process dying. So it's said as something else, which
     * {@link #handle} doesn't take for the phone process, whose channels would go with it. The two are told apart by
     * the HAL's binder: dead after its process went, when the next open looks it up afresh; alive after a refusal,
     * when that reader is left alone for {@link #HAL_RETRY_MS}. */
    private Object halCall(int reader, Object hal, String name, Object... args) throws Exception {
        try { return call(seApi, hal, name, args); }
        catch (Exception e) {
            if (!phoneDied(e)) throw e;
            seReaders.remove(reader);
            if (!binderAlive(hal)) throw new HalGoneException();
            seAbsent.put(reader, clock.millis() + HAL_RETRY_MS);
            throw new IllegalStateException("The card's secure element service refused this helper");
        }
    }

    /** Whether [hal]'s binder is still alive. Where that can't be told, it's taken for alive: a refusal, the case
     * that keeps the helper from asking again at once. */
    private static boolean binderAlive(Object hal) {
        try { return ((IBinder) hal.getClass().getMethod("asBinder").invoke(hal)).isBinderAlive(); }
        catch (Exception e) { return true; }
    }

    /** The APDU bytes for the Secure Element HAL: header and data, with the logical channel in the class byte. */
    private static byte[] apdu(int channel, int cla, int ins, int p1, int p2, int p3, String data) {
        byte[] body = bytes(data);
        byte[] apdu = new byte[5 + body.length];
        apdu[0] = classByte(cla, channel);
        apdu[1] = (byte) ins;
        apdu[2] = (byte) p1;
        apdu[3] = (byte) p2;
        apdu[4] = (byte) p3;
        System.arraycopy(body, 0, apdu, 5, body.length);
        return apdu;
    }

    /** [cla] with the logical [channel] encoded in it, the way Android's own secure-element service does (ISO 7816-4):
     * channels 0-3 in the low two bits, 4-19 in the lower nibble with bit 7 set. Telephony needs none of this, as the
     * channel is a separate argument there. */
    static byte classByte(int cla, int channel) {
        if (channel < 4) return (byte) ((cla & 0xBC) | channel);
        if (channel < 20) {
            int b = (cla & 0xB0) | 0x40 | (channel - 4);
            if ((cla & 0x0C) != 0) b |= 0x20; // carry secure messaging across to the further-channel coding
            return (byte) b;
        }
        throw new IllegalArgumentException("Channel number out of range");
    }

    /** Hex string to bytes; the string is already checked to be hex of even length. */
    private static byte[] bytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    /** The subscription Android 7-9 reach a slot's card through, looked up on each open: it changes when the card
     * switches eSIMs. Slot 0 can always use -1, which these versions send to the first SIM's card. Another slot
     * needs an active subscription, which a card has only while one of its eSIMs is on; -1 would reach slot 0's
     * card instead, so without one it's {@link #NO_SUB}. */
    private int subIdFor(int slot) throws Exception {
        try {
            Object info = activeSubInfo(slot);
            if (info != null) return subIdOf(info);
        } catch (Exception e) { if (phoneDied(e)) throw e; } // the plain list below may still say
        try {
            int[] ids = (int[]) subCall("getSubId", slot);
            // Negative ones are placeholders Android keeps for a slot without a subscription.
            for (int i = 0; ids != null && i < ids.length; i++) if (ids[i] >= 0) return ids[i];
        } catch (Exception e) { if (phoneDied(e)) throw e; }
        return slot == 0 ? -1 : NO_SUB;
    }

    /** Android's active subscription in a SIM slot (SubscriptionInfo), or null. Android 14+ throws
     * IllegalArgumentException for a slot past the phone's last; earlier versions return null. */
    private Object activeSubInfo(int slot) throws Exception {
        try { return subCall("getActiveSubscriptionInfoForSimSlotIndex", slot, SHELL, null); } // Android 11+
        catch (NoSuchMethodException e) {
            return subCall("getActiveSubscriptionInfoForSimSlotIndex", slot, SHELL); // Android 6-10
        }
    }

    /** Phone settings for the given eSIM, once Android has it active: makes it the mobile data SIM and/or
     * turns on its data roaming. "applied" is false while the phone hasn't picked the eSIM up yet, which takes
     * a few seconds after a switch. "roamingMayReset" (only ever true) says Android may turn the roaming back
     * off on its own later: only its own roaming switch turns it on for good there.
     */
    private JSONObject useSim(JSONObject q, JSONObject out) throws Exception {
        String iccid = digits(q.getString("iccid"));
        Object info = activeSub(iccid);
        if (info == null) return out.put("applied", false);
        int subId = subIdOf(info);
        boolean mayReset = false;
        if (q.getBoolean("roaming")) {
            try { phoneCall("setDataRoamingEnabled", subId, true); } // Android 10+
            catch (NoSuchMethodException e) {
                // Android 6-9 only note it in their SIM list. Their data connection follows a setting, kept for each
                // SIM on phones with more than one.
                subCall("setDataRoaming", 1, subId);
                int sims = (int) subCall("getActiveSubInfoCountMax");
                putGlobal(sims > 1 ? "data_roaming" + subId : "data_roaming", "1");
                // One-SIM Android 8.1-9 put the carrier's default back each time the carrier's settings load, unless
                // roaming was last changed with Android's own switch: a few seconds after a switch, and after
                // every restart. keepRoamingOn catches the first; the app tells the user about the second.
                if (sims == 1 && (sdk == 27 || sdk == 28)) {
                    keepRoamingOn(iccid, subId);
                    mayReset = true;
                }
            }
        }
        if (q.getBoolean("data")) {
            subCall("setDefaultDataSubId", subId);
            // That SIM's own Mobile data switch (reason 0: the user's).
            try { phoneCall("setDataEnabledForReason", subId, 0, true, SHELL); } // Android 13+
            catch (NoSuchMethodException e) {
                try { phoneCall("setDataEnabledForReason", subId, 0, true); } // Android 12-12L
                catch (NoSuchMethodException e2) {
                    try { phoneCall("setUserDataEnabled", subId, true); } // Android 9-11
                    catch (NoSuchMethodException e3) { phoneCall("setDataEnabled", subId, true); } // Android 6-8.1
                }
            }
        }
        if (mayReset) out.put("roamingMayReset", true);
        return out.put("applied", true);
    }

    /** Android's active subscription (SubscriptionInfo) for the eSIM [iccid], or null while there is none. Looked
     * for in each of Android's SIM slots: the card's slot number is a physical one, which some phones (single-SIM
     * mode, a remapped slot) number differently from these. */
    private Object activeSub(String iccid) throws Exception {
        for (int s = 0; s < 4 && !iccid.isEmpty(); s++) {
            Object found;
            try { found = activeSubInfo(s); }
            catch (IllegalArgumentException e) { break; } // past this phone's last SIM slot; anything else is an error
            String id = found == null ? null : (String) found.getClass().getMethod("getIccId").invoke(found);
            if (id != null && digits(id).equals(iccid)) return found;
        }
        return null;
    }

    private static int subIdOf(Object info) throws Exception {
        return (int) info.getClass().getMethod("getSubscriptionId").invoke(info);
    }

    /** After {@link #useSim} turned roaming on for one-SIM Android 8.1-9: the carrier's settings for a newly
     * switched-on eSIM load a few seconds after Android has it active, and may turn roaming off again. Checks the
     * setting each second for 10 seconds and turns roaming on once more if it went off, unless another eSIM is on
     * by then. On a thread of its own: the helper runs one card request at a time, and this mustn't hold others
     * up. Ends with the helper, as it may when the app closes. */
    private void keepRoamingOn(String iccid, int subId) {
        int watch = ROAMING_WATCH.incrementAndGet();
        repeat.every(1000, 10, () -> {
            if (watch != ROAMING_WATCH.get()) return true; // a newer switch watches instead
            if (!commands.run(SETTINGS, "get", "global", "data_roaming").trim().equals("0")) return false;
            Object info = activeSub(iccid);
            if (info != null && subIdOf(info) == subId) {
                subCall("setDataRoaming", 1, subId);
                putGlobal("data_roaming", "1");
            }
            return true; // once: the phone's own change has happened, and isn't fought over
        });
    }

    /** Changes a global setting with Android's settings command, then reads it back: on older versions the
     * command ends normally even when the change failed. */
    private void putGlobal(String key, String value) throws Exception {
        commands.run(SETTINGS, "put", "global", key, value);
        if (!commands.run(SETTINGS, "get", "global", key).trim().equals(value))
            throw new IllegalStateException("The phone didn't keep the " + key + " setting");
    }

    /** {@link Repeat} on a daemon thread, which never keeps the helper running. A check that fails ends it: the
     * change it watches over is made already. */
    static void inBackground(long ms, int times, Callable<Boolean> check) {
        Thread thread = new Thread(() -> {
            try {
                for (int i = 0; i < times; i++) {
                    Thread.sleep(ms);
                    if (check.call()) return;
                }
            } catch (Exception ignored) { }
        }, "helper-roaming");
        thread.setDaemon(true);
        thread.start();
    }

    /** Read-only: what Android says of each physical slot, from what it holds in memory: no card is touched. Empty when
     * Android doesn't say: 6-8 have no such list, and 9 lets only root read it. */
    private JSONObject slots(JSONObject out) throws Exception {
        JSONArray list = new JSONArray();
        try {
            Object[] all;
            try { all = (Object[]) phoneCall("getUiccSlotsInfo", SHELL); } // Android 13+
            catch (NoSuchMethodException e) { all = (Object[]) phoneCall("getUiccSlotsInfo"); } // Android 9-12L
            Map<Integer, String> eids = all == null ? Collections.emptyMap() : cardEids();
            for (int i = 0; all != null && i < all.length; i++) {
                Object s = all[i];
                JSONObject slot = new JSONObject();
                if (s != null) {
                    slot.put("euicc", (boolean) s.getClass().getMethod("getIsEuicc").invoke(s));
                    // Android 9 doesn't say. Left out, the app counts the slot removable, so it isn't moved to the
                    // end of the search on a guess.
                    Method removable = find(s.getClass(), "isRemovable");
                    if (removable != null) slot.put("removable", (boolean) removable.invoke(s));
                    describe(slot, s, eids.get(i));
                }
                list.put(slot);
            }
        } catch (Exception e) {
            if (phoneDied(e)) throw e; // the next open then reaches the new process
            list = new JSONArray();
        }
        return out.put("slots", list);
    }

    /** The rest of what a slot says, field by field: each is left out if Android doesn't have it or its getter fails, as
     * one bad getter on some phone mustn't cost the whole list, and the app reads a missing field as unknown. */
    private static void describe(JSONObject slot, Object s, String cardEid) throws Exception {
        Object state = get(s, "getCardStateInfo");
        if (state instanceof Integer) slot.put("present", (int) state != CARD_ABSENT);
        Object ports = get(s, "getPorts");
        if (ports instanceof Iterable) { // Android 13+, where the slot's own two getters below throw
            for (Object port : (Iterable<?>) ports) {
                Object on = get(port, "isActive");
                if (on instanceof Boolean && !slot.optBoolean("active")) {
                    slot.put("active", on);
                    if ((boolean) on) putLogical(slot, get(port, "getLogicalSlotIndex"));
                }
            }
        } else {
            Object on = get(s, "getIsActive");
            if (on instanceof Boolean) {
                slot.put("active", on);
                if ((boolean) on) putLogical(slot, get(s, "getLogicalSlotIdx"));
            }
        }
        // The EID when Android has it, but also just the active eSIM's ICCID, or an ordinary SIM's: never sent as it is.
        Object id = get(s, "getCardId");
        String cardId = id instanceof String ? (String) id : "";
        String eid = cardEid != null ? cardEid : slot.optBoolean("euicc") ? cardId : "";
        if (EID.matcher(eid).matches()) slot.put("eid", eid);
        if (!cardId.isEmpty()) slot.put("token", hex(Arrays.copyOf(
                MessageDigest.getInstance("SHA-256").digest(cardId.getBytes(StandardCharsets.UTF_8)), 8)));
    }

    private static void putLogical(JSONObject slot, Object logical) throws Exception {
        if (logical instanceof Integer && (int) logical >= 0) slot.put("logical", logical);
    }

    /** The card IDs Android 10+ has read, by physical slot. Not vital, so never a reason to give up the list. */
    private Map<Integer, String> cardEids() throws Exception {
        Map<Integer, String> eids = new HashMap<>();
        try {
            Object cards = phoneCall("getUiccCardsInfo", SHELL);
            if (cards instanceof Iterable) for (Object card : (Iterable<?>) cards) {
                Object slot = get(card, "getSlotIndex"), eid = get(card, "getEid");
                if (slot instanceof Integer && eid instanceof String) eids.put((Integer) slot, (String) eid);
            }
        } catch (Exception e) { if (phoneDied(e)) throw e; }
        return eids;
    }

    /** Read-only: what Android remembers of the SIM profiles it has seen on the card with [eid], on and off, from its
     * own records: no card is touched. Each with its ICCID, the name Android shows and where that came from, Android's
     * number for it and whether it's in use. Only that card's: other SIMs' records never leave the helper. Android ties
     * records to a card by its EID from 9 on; before that, or where it couldn't read the EID, there are none. */
    private JSONObject cardRecords(String eid, JSONObject out) throws Exception {
        if (!EID.matcher(eid).matches()) throw new IllegalArgumentException("Invalid EID");
        JSONArray list = new JSONArray();
        Object all;
        try { all = subCall("getAllSubInfoList", SHELL, null); } // Android 11+
        catch (NoSuchMethodException e) { all = subCall("getAllSubInfoList", SHELL); } // 6-10
        if (all instanceof Iterable) for (Object s : (Iterable<?>) all) {
            Object card = get(s, "getCardString"); // Android 10+
            if (card == null) card = get(s, "getCardId"); // 9, where it's a String (an int from 10 on)
            if (!(card instanceof String) || !eid.equalsIgnoreCase((String) card)) continue;
            Object iccid = get(s, "getIccId"), name = get(s, "getDisplayName"), id = get(s, "getSubscriptionId");
            Object source = get(s, "getDisplayNameSource"); // Android 14+
            if (source == null) source = get(s, "getNameSource"); // before
            Object slot = get(s, "getSimSlotIndex");
            if (!(iccid instanceof String) || name == null || !(id instanceof Integer)) continue;
            JSONObject r = new JSONObject().put("iccid", iccid).put("name", name.toString()).put("id", id)
                    .put("inUse", slot instanceof Integer && (int) slot >= 0);
            // Before Android 10 the source says nothing: names read from the card were marked as Android's own too.
            if (source instanceof Integer && sdk >= 29) r.put("source", source);
            list.put(r);
        }
        return out.put("records", list);
    }

    /** [target]'s getter, or null when there's no such getter or it fails. */
    private static Object get(Object target, String getter) {
        try {
            Method method = find(target.getClass(), getter);
            return method == null ? null : method.invoke(target);
        } catch (Exception e) { return null; }
    }

    private Object phone() throws Exception {
        if (phone == null) phone = phones.call();
        return phone;
    }

    private Object sub() throws Exception {
        if (sub == null) sub = subs.call();
        return sub;
    }

    private Object phoneCall(String name, Object... args) throws Exception { return call(api, phone(), name, args); }

    private Object subCall(String name, Object... args) throws Exception { return call(subApi, sub(), name, args); }

    /** Hidden interfaces change between Android versions, and some phones add methods of the same name: matched
     * by name and argument types. NoSuchMethodException when [api] has no such method; otherwise the call's own
     * exception, as calling it directly would throw it. */
    static Object call(Class<?> api, Object target, String name, Object... args) throws Exception {
        outer:
        for (Method method : api.getMethods()) {
            Class<?>[] types = method.getParameterTypes(); // not getParameterCount(), which is Android 8+
            if (!method.getName().equals(name) || types.length != args.length) continue;
            for (int i = 0; i < types.length; i++) if (!fits(types[i], args[i])) continue outer;
            try { return method.invoke(target, args); }
            catch (InvocationTargetException e) {
                // An Error stays wrapped: reported as a failed request, it doesn't end the helper.
                if (e.getCause() instanceof Exception) throw (Exception) e.getCause();
                throw e;
            }
        }
        throw new NoSuchMethodException(name);
    }

    /** Whether [arg] can be passed as [type]: the primitives used are int, boolean, (the Secure Element HAL's) byte and
     * (the package manager's) long. A byte[] is an object, so {@link Class#isInstance} already covers it. */
    private static boolean fits(Class<?> type, Object arg) {
        if (arg == null) return !type.isPrimitive();
        if (type == int.class) return arg instanceof Integer;
        if (type == boolean.class) return arg instanceof Boolean;
        if (type == byte.class) return arg instanceof Byte;
        if (type == long.class) return arg instanceof Long;
        return type.isInstance(arg);
    }

    /** [api]'s method with exactly these parameter types, or null. */
    private static Method find(Class<?> api, String name, Class<?>... types) {
        try { return api.getMethod(name, types); }
        catch (NoSuchMethodException e) { return null; }
    }

    private static Method[] all(Method open, Method transmit, Method close) {
        return open != null && transmit != null && close != null ? new Method[] {open, transmit, close} : null;
    }

    private static Class<?> optionalClass(String name) {
        try { return Class.forName(name); }
        catch (ClassNotFoundException e) { return null; }
    }

    private static Object service(String name, String type, String what) throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, name);
        Object proxy = Class.forName(TELEPHONY + type + "$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        if (proxy == null) throw new IllegalStateException(what + " service unavailable");
        return proxy;
    }

    /** Where the HAL's interface was loaded from, once it has been: the same for every session of this process. */
    private static volatile ClassLoader halLoader;

    /** The Secure Element AIDL HAL interface, or null where the phone has none: before Android 14, which has only the
     * older HIDL one, or no secure-element app. The interface isn't among the classes every process has: it comes in
     * Android's own secure-element app (com.android.se), so it's loaded from that app's APK, found through the package
     * manager rather than by starting a command. Only ever from a read-only system partition (see [systemApk]). */
    static Class<?> secureElementApi() throws Exception {
        if (Build.VERSION.SDK_INT < 34) return null;
        ClassLoader loader = halLoader;
        if (loader == null) {
            String apk = systemApk(appPath(SE_PACKAGE));
            if (apk == null) return null;
            loader = new dalvik.system.PathClassLoader(apk, CardShell.class.getClassLoader());
        }
        Class<?> api = Class.forName(HAL, true, loader); // throws where this phone's app has no AIDL HAL interface
        halLoader = loader;
        return api;
    }

    /** Where the system package [name]'s APK is, as the package manager says; null where there's no such system package. */
    private static String appPath(String name) throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "package");
        if (binder == null) return null;
        Class<?> api = Class.forName("android.content.pm.IPackageManager");
        Object pm = Class.forName("android.content.pm.IPackageManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        Object info = call(api, pm, "getApplicationInfo", name, MATCH_SYSTEM_ONLY, 0); // Android 13+: flags are a long
        return info == null ? null : (String) info.getClass().getField("sourceDir").get(info);
    }

    /** [path], resolved, when it's an APK on one of Android's read-only partitions; otherwise null. The helper runs as
     * shell or root, so it never loads code from anywhere an app could write: an updated copy in /data doesn't count. */
    static String systemApk(String path) {
        if (path == null) return null;
        try {
            java.io.File file = new java.io.File(path);
            return acceptedApk(file.getCanonicalPath(), file.isFile()); // /system_ext, say, can be a link into /system
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** [real], a resolved path, when it's an APK on a read-only partition and [isFile] says it's there; else null. */
    static String acceptedApk(String real, boolean isFile) {
        return isFile && onSystemPartition(real) ? real : null;
    }

    /** Whether [path] names an APK on one of Android's read-only partitions, with no ".." or "." in it. */
    static boolean onSystemPartition(String path) {
        if (!SYSTEM_APK.matcher(path).matches()) return false;
        for (String part : path.split("/")) if (part.equals("..") || part.equals(".")) return false;
        return true;
    }

    /** The Secure Element HAL's [reader] (SIM1 for 0, SIM2 for 1, ...), or null where none is registered. Android
     * doesn't say which slot's card a reader reaches: it can follow the phone's SIM numbers rather than its trays, so
     * the app asks for the one it expects and checks the card's EID. Non-blocking, and never starts a HAL: one that isn't
     * up counts as absent, so discovery never waits on it. A HAL the helper's user may look up but not call is found
     * here, and refuses at the first call. */
    private static Object secureElementReader(int reader) throws Exception {
        Class<?> api = secureElementApi();
        if (api == null) return null;
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, HAL + "/SIM" + (reader + 1));
        if (binder == null) return null;
        return Class.forName(HAL + "$Stub", true, api.getClassLoader())
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    /** Runs [command] as this helper's user, with its error output mixed in. */
    private static String exec(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            process.getOutputStream().close();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream in = process.getInputStream();
            byte[] buffer = new byte[1024];
            for (int n; (n = in.read(buffer)) != -1; ) if (out.size() < 65536) out.write(buffer, 0, n);
            int code = process.waitFor();
            if (code != 0) throw new IllegalStateException(command[0] + " " + command[1] + " failed (" + code + ")");
            return out.toString("UTF-8");
        } finally {
            process.destroy();
        }
    }

    /** ICCIDs compared without the F filler some sources keep. */
    private static String digits(String iccid) { return iccid.replaceAll("[^0-9]", ""); }

    /** The arguments before the channel number that say where an open channel is, for transmit and close. */
    private Object[] where(int slot, int port, Channel c) {
        switch (route) {
            case SUB: return new Object[] {c.subId};
            case DEFAULT: return new Object[0];
            default:
                if (!byPort) return new Object[] {slot};
                return transmitNamesCaller ? new Object[] {slot, port, SHELL} : new Object[] {slot, port};
        }
    }

    private static Object[] concat(Object[] first, Object... rest) {
        Object[] all = new Object[first.length + rest.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(rest, 0, all, first.length, rest.length);
        return all;
    }

    private void closeChannel(int slot, int port, int ch, Channel c) throws Exception {
        Object result = route == Route.REQUEST ? close.invoke(phone(), c.request)
                : close.invoke(phone(), concat(where(slot, port, c), ch));
        if (Boolean.FALSE.equals(result)) throw new IllegalStateException("Channel close failed");
    }

    private void closeAll() {
        for (Map.Entry<String, Channel> entry : channels.entrySet()) {
            String[] parts = entry.getKey().split(":");
            int slot = Integer.parseInt(parts[0]), port = Integer.parseInt(parts[1]), ch = Integer.parseInt(parts[2]);
            Channel c = entry.getValue();
            try {
                if (c.seHal != null) call(seApi, c.seHal, "closeChannel", (byte) ch);
                else closeChannel(slot, port, ch, c);
            } catch (Exception ignored) { }
        }
        channels.clear();
    }

    private static int bounded(JSONObject q, String name, int max) throws Exception {
        int value = q.getInt(name);
        if (value < 0 || value > max) throw new IllegalArgumentException("Invalid " + name);
        return value;
    }

    private static String hex(byte[] bytes) {
        if (bytes == null) return "";
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[2 * i] = HEX_DIGITS[(bytes[i] >> 4) & 0xF];
            out[2 * i + 1] = HEX_DIGITS[bytes[i] & 0xF];
        }
        return new String(out);
    }

    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();
}
