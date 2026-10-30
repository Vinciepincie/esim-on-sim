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
    /** How long a helper waits for its first request. */
    private static final long UNCLAIMED_MS = 30_000;
    /** No subscription to reach a slot's card through (see {@link #subIdFor}). */
    private static final int NO_SUB = Integer.MIN_VALUE;
    private static final Class<?> I = int.class, S = String.class;

    /** The eSIM manager IDs a request may open, the usual one first: most cards answer to it, and some makers' only
     * to their own. Kept here, not in the app's Lpa, as the helper shouldn't load what that brings with it. */
    public static final List<String> ISD_R_AIDS = Collections.unmodifiableList(Arrays.asList(
            "A0000005591010FFFFFFFF89000001",     // the usual one, as a prefix: Lpa.ISD_R_OPEN
            "A0000005591010000000008900000300",   // eSIM.me
            "A0000005591010FFFFFFFF8900050500",   // 5ber
            "A0000005591010FFFFFFFF8900000177",   // XeSIM
            "A000000559104C696E6B736669656C64",   // LinksField
            "A06573746B6D65FFFF4953442D522030",   // ESTKme, SE0
            "A06573746B6D65FFFF4953442D522031",   // ESTKme, SE1
            "A06573746B6D65FFFFFFFF4953442D52")); // ESTKme, the older ID

    /** How channels name the card, newest first. Android 13+ takes an IccLogicalChannelRequest with the slot,
     * 10-12L the slot itself. 7-9 take the subscription Android has for the card, the only way they have to name
     * another slot. Android 6 always uses the first SIM's card. */
    private enum Route { REQUEST, SLOT, SUB, DEFAULT }

    /** What transmitting on an open channel and closing it need again. */
    private static final class Channel {
        final Object request; // Android 13+
        final int subId;      // Android 7-9
        Channel(Object request, int subId) { this.request = request; this.subId = subId; }
    }

    /** Runs one of Android's own commands, such as settings, and returns what it printed; throws when it fails. */
    interface Commands { String run(String... command) throws Exception; }

    /** Runs [check] on a thread of its own every [ms], at most [times] times, until it returns true. */
    interface Repeat { void every(long ms, int times, Callable<Boolean> check); }

    /** The latest roaming watch (see {@link #keepRoamingOn}), in any session: an earlier one stops. */
    private static final AtomicInteger ROAMING_WATCH = new AtomicInteger();

    private final Class<?> api;
    private final Callable<Object> phones;
    private final Class<?> requestType;
    private final Class<?> subApi;
    private final Callable<Object> subs;
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
    private final Map<String, Channel> channels = new HashMap<>();
    private Object phone;
    private Object sub;
    private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]*");

    private CardShell() throws Exception {
        this(Class.forName(TELEPHONY + "ITelephony"), () -> service("phone", "ITelephony", "Telephony"),
                optionalClass(TELEPHONY + "IccLogicalChannelRequest"), Class.forName(TELEPHONY + "ISub"),
                () -> service("isub", "ISub", "Subscription"), CardShell::exec, Build.VERSION.SDK_INT,
                CardShell::inBackground);
    }

    /** [phones] gives what implements [api] and [subs] what implements [subApi], once they're needed, and again after
     * the phone process restarted. Tests pass stand-ins with the methods of Android version [sdk], and run [repeat]'s
     * checks themselves. */
    CardShell(Class<?> api, Callable<Object> phones, Class<?> requestType, Class<?> subApi, Callable<Object> subs,
              Commands commands, int sdk, Repeat repeat) {
        this.api = api;
        this.phones = phones;
        this.requestType = requestType;
        this.subApi = subApi;
        this.subs = subs;
        this.commands = commands;
        this.sdk = sdk;
        this.repeat = repeat;
        // Matched by name and parameter types: counts alone are ambiguous, as Android 13's request calls take one
        // parameter like Android 6's. A way in counts only with all three calls.
        Route r = null;
        Method[] found = null;
        if (requestType != null) { // Android 13+
            Method t = find(api, "iccTransmitApduLogicalChannelByPort", I, I, I, I, I, I, I, I, S);
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
        byPort = route == Route.REQUEST && transmit.getParameterTypes().length == 9;
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
        int slot = bounded(q, "slot", 7), port = bounded(q, "port", 7);
        if (!byPort && port != 0) throw new IllegalArgumentException("Only port zero is supported");
        if (op.equals("open")) {
            String aid = q.getString("aid");
            if (!ISD_R_AIDS.contains(aid)) throw new IllegalArgumentException("Unsupported card applet");
            if (route == null) throw new UnsupportedOperationException("This phone has no known way to reach the card");
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
                    if (subId == NO_SUB) return missing(out);
                    int n = open.getParameterTypes().length;
                    args = n == 4 ? new Object[] {subId, SHELL, aid, 0}
                            : n == 3 ? new Object[] {subId, aid, 0} : new Object[] {subId, aid};
                    break;
                default:
                    if (slot != 0) return missing(out);
                    args = new Object[] {aid};
            }
            Object response = open.invoke(phone(), args);
            if (response == null) throw new IllegalStateException("Channel open returned no response");
            int ch = (int) response.getClass().getMethod("getChannel").invoke(response);
            int status = (int) response.getClass().getMethod("getStatus").invoke(response);
            byte[] select = (byte[]) response.getClass().getMethod("getSelectResponse").invoke(response);
            if (ch > 0) {
                if (request != null) requestType.getField("channel").setInt(request, ch);
                channels.put(slot + ":" + port + ":" + ch, new Channel(request, subId));
            }
            return out.put("channel", ch).put("status", status).put("select", hex(select));
        }
        int channel = bounded(q, "channel", 255);
        String key = slot + ":" + port + ":" + channel;
        if (!channels.containsKey(key)) throw new IllegalArgumentException("Channel is not owned by this session");
        if (op.equals("close")) {
            // Forget it even when closing fails: after a card restart the channel no longer exists.
            try { closeChannel(slot, port, channel, channels.get(key)); }
            finally { channels.remove(key); }
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
        Object result = transmit.invoke(phone(),
                concat(where(slot, port, channels.get(key)), channel, cla, ins, p1, p2, p3, data));
        // Some versions answer a channel they don't know with no status word at all: an error, as the app needs one.
        if (!(result instanceof String) || ((String) result).length() < 4)
            throw new IllegalStateException("The card gave no answer");
        return out.put("response", result);
    }

    /** The open's answer for a slot this Android version can't reach: no card there, as far as the app goes. */
    private static JSONObject missing(JSONObject out) throws Exception {
        return out.put("channel", 0).put("status", STATUS_MISSING_RESOURCE).put("select", "");
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

    /** Read-only: whether each physical slot holds an eUICC, and whether Android counts it removable. Empty when
     * Android doesn't say: 6-8 have no such list, and 9 lets only root read it. */
    private JSONObject slots(JSONObject out) throws Exception {
        JSONArray list = new JSONArray();
        try {
            Object[] all;
            try { all = (Object[]) phoneCall("getUiccSlotsInfo", SHELL); } // Android 13+
            catch (NoSuchMethodException e) { all = (Object[]) phoneCall("getUiccSlotsInfo"); } // Android 9-12L
            for (int i = 0; all != null && i < all.length; i++) {
                Object s = all[i];
                JSONObject slot = new JSONObject();
                if (s != null) {
                    slot.put("euicc", (boolean) s.getClass().getMethod("getIsEuicc").invoke(s));
                    // Android 9 doesn't say. Left out, the app counts the slot removable, so it isn't moved to the
                    // end of the search on a guess.
                    Method removable = find(s.getClass(), "isRemovable");
                    if (removable != null) slot.put("removable", (boolean) removable.invoke(s));
                }
                list.put(slot);
            }
        } catch (Exception e) {
            if (phoneDied(e)) throw e; // the next open then reaches the new process
            list = new JSONArray();
        }
        return out.put("slots", list);
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

    /** Whether [arg] can be passed as [type]: the only primitives used are int and boolean. */
    private static boolean fits(Class<?> type, Object arg) {
        if (arg == null) return !type.isPrimitive();
        if (type == int.class) return arg instanceof Integer;
        if (type == boolean.class) return arg instanceof Boolean;
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
            default: return byPort ? new Object[] {slot, port} : new Object[] {slot};
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
            try { closeChannel(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), entry.getValue()); }
            catch (Exception ignored) { }
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
