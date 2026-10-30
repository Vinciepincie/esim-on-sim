package com.esimonsim.adb;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/** Debug builds only: a simulated eSIM card for testing on an emulator, speaking {@link CardShell}'s
 * protocol. The app starts it instead of CardShell while /data/local/tmp/fakecard.json exists (see
 * apdu/Helper.kt in the debug source set). That file sets the faults and is re-read on every request,
 * so they can change mid-test ({@code adb shell 'echo {...} > /data/local/tmp/fakecard.json'}):
 * <pre>
 *   slot             the slot the card is in (default 1); absent: no card anywhere
 *   aid              the one eSIM manager ID the card answers to, as on some makers' cards (default: the usual one)
 *   startDelayMs     before the helper answers at all (a slow app_process start)
 *   delayMs, jitterMs  per APDU, plus up to jitterMs more (a slow card)
 *   openDelayMs      per channel open
 *   failRate         share of APDUs answered 6F00, 0..1 (a flaky card)
 *   hangAt           the Nth APDU never answers, so the app times out; removed once used
 *   exitAt           the helper dies at the Nth APDU, so card access drops; removed once used
 *   refreshMs        how long the card is gone after a switch (default 3000): channels drop, opens fail
 *   dropEnableReply  the reply to turning an eSIM on or off is lost, as on some real cards
 *   useSimDelayMs    how long after a switch the phone takes to pick the eSIM up (default 2000)
 *   useSimRefuse     the phone refuses SIM settings changes
 *   defaultDp        the card's default SM-DP+ address; rootDs its SM-DS (default lpa.ds.gsma.com)
 *   slots            what the "slots" request reports, e.g. [{"euicc":true,"removable":false}, ...]
 * </pre>
 * The card itself (EID, eSIMs, notices) persists in /data/local/tmp/fakecard-state.json; delete
 * that file to start over with three eSIMs.
 */
public final class FakeCardShell implements HelperServer.Session {
    private static final File CONFIG = new File("/data/local/tmp/fakecard.json");
    private static final File STATE = new File("/data/local/tmp/fakecard-state.json");
    private static final String GSMA_KEY = "81370F5125D0B1D408D4C3B232E6D25E795BEBFB";
    private static final String OTHER_KEY = "A1B2C3D4E5F60718293A4B5C6D7E8F9001122334";
    private static final int INSTALL = 0, ENABLE = 1, DISABLE = 2, DELETE = 3;

    private final Random random = new Random();
    private final Map<Integer, Channel> channels = new HashMap<>();
    private JSONObject cfg = new JSONObject();
    private JSONObject card;
    private int nextChannel = 1;
    private int apdus;

    private static final class Channel {
        final ByteArrayOutputStream command = new ByteArrayOutputStream();
        byte[] reply = new byte[0];
        int replyAt;
        boolean dead; // the card restarted since it was opened
    }

    public static void main(String[] args) {
        // Background mode, as CardShell's: the same card, reached over loopback after the stream ends.
        if (args.length > 0 && args[0].equals("serve") && android.os.Process.myUid() == 2000)
            HelperServer.run(FakeCardShell::new);
        try {
            int uid = android.os.Process.myUid();
            if (uid != 2000 && uid != 0) throw new SecurityException("ADB shell or root required");
            FakeCardShell shell = new FakeCardShell();
            shell.cfg = read(CONFIG, new JSONObject());
            Thread.sleep(shell.cfg.optLong("startDelayMs", 0));
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = input.readLine()) != null) {
                JSONObject response;
                try { response = shell.handle(new JSONObject(line)).put("ok", true); }
                catch (Exception e) {
                    response = new JSONObject().put("ok", false).put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                System.out.println("EOS:" + response);
                System.out.flush();
            }
        } catch (Exception e) {
            System.err.println("Fake card stopped: " + e);
        }
        System.exit(0);
    }

    @Override public void close() { channels.clear(); }

    @Override public JSONObject handle(JSONObject q) throws Exception {
        cfg = read(CONFIG, new JSONObject());
        card = read(STATE, null);
        if (card == null) card = newCard();
        JSONObject out = new JSONObject();
        switch (q.getString("op")) {
            case "ping": return out.put("version", 1).put("uid", android.os.Process.myUid());
            case "useSim": return useSim(q, out);
            // As the POCO F6 reports a 5ber card: an ordinary SIM tray, then the card as a fixed eUICC.
            case "slots": return out.put("slots", cfg.optJSONArray("slots") != null ? cfg.getJSONArray("slots")
                : new JSONArray().put(new JSONObject().put("euicc", false).put("removable", true))
                    .put(new JSONObject().put("euicc", true).put("removable", false)));
            case "open": return open(q.getInt("slot"), q.getString("aid"), out);
            case "close": {
                Channel ch = channels.remove(q.getInt("channel"));
                if (ch == null) throw new IllegalArgumentException("Channel is not owned by this session");
                if (ch.dead) throw new IllegalStateException("Channel close failed");
                return out;
            }
            case "transmit": return out.put("response", transmit(q));
            default: throw new IllegalArgumentException("Unsupported operation");
        }
    }

    private boolean restarting() { return System.currentTimeMillis() < card.optLong("refreshUntil"); }

    private JSONObject open(int slot, String aid, JSONObject out) throws Exception {
        if (!CardShell.ISD_R_AIDS.contains(aid)) throw new IllegalArgumentException("Unsupported card applet");
        Thread.sleep(cfg.optLong("openDelayMs", 0));
        boolean here = !cfg.optBoolean("absent") && slot == cfg.optInt("slot", 1)
            && aid.equals(cfg.optString("aid", CardShell.ISD_R_AIDS.get(0)));
        // IccOpenLogicalChannelResponse: INVALID_CHANNEL, and STATUS_NO_SUCH_ELEMENT or STATUS_UNKNOWN_ERROR.
        if (!here) return out.put("channel", -1).put("status", 3).put("select", "");
        if (restarting()) return out.put("channel", -1).put("status", 4).put("select", "");
        int ch = nextChannel;
        nextChannel = nextChannel % 19 + 1;
        channels.put(ch, new Channel());
        return out.put("channel", ch).put("status", 1).put("select", "6F108410A0000005591010FFFFFFFF89000001009000");
    }

    private String transmit(JSONObject q) throws Exception {
        Channel ch = channels.get(q.getInt("channel"));
        if (ch == null) throw new IllegalArgumentException("Channel is not owned by this session");
        if (ch.dead || restarting()) { ch.dead = true; throw new IllegalStateException("The card is restarting"); }
        long jitter = cfg.optLong("jitterMs", 0);
        Thread.sleep(cfg.optLong("delayMs", 0) + (jitter > 0 ? (long) (random.nextDouble() * jitter) : 0));
        apdus++;
        if (cfg.has("hangAt") && apdus >= cfg.getInt("hangAt")) { cfg.remove("hangAt"); write(CONFIG, cfg); Thread.sleep(120_000); }
        if (cfg.has("exitAt") && apdus >= cfg.getInt("exitAt")) { cfg.remove("exitAt"); write(CONFIG, cfg); System.exit(1); }
        if (random.nextDouble() < cfg.optDouble("failRate", 0)) return "6F00";
        int ins = q.getInt("ins");
        if (ins == 0xC0) return chunk(ch, q.getInt("p3"));
        if (ins != 0xE2) return "6D00";
        ch.command.write(unhex(q.getString("data")));
        if ((q.getInt("p1") & 0x80) == 0) return "9000"; // more blocks follow
        byte[] command = ch.command.toByteArray();
        ch.command.reset();
        byte[] reply = es10(command);
        write(STATE, card);
        if (reply == null) return "6A88";
        ch.reply = reply;
        ch.replyAt = 0;
        return chunk(ch, 0);
    }

    /** Up to 256 bytes of the pending reply, then 61xx while more remains. */
    private static String chunk(Channel ch, int max) {
        int n = Math.min(max == 0 ? 256 : max, ch.reply.length - ch.replyAt);
        String data = hex(Arrays.copyOfRange(ch.reply, ch.replyAt, ch.replyAt + n));
        ch.replyAt += n;
        int left = ch.reply.length - ch.replyAt;
        return data + (left > 0 ? String.format("61%02X", Math.min(left, 256) & 0xFF) : "9000");
    }

    // ---------------- ES10 ----------------

    private byte[] es10(byte[] command) throws Exception {
        Tlv req = Tlv.parse(command, 0);
        switch (req.tag) {
            case 0xBF3E: return tlv(0xBF3E, tlv(0x5A, unhex(card.getString("eid"))));
            case 0xBF2D: return profiles();
            case 0xBF22: return tlv(0xBF22, tlv(0x81, bytes(2, 3, 0)), tlv(0x82, bytes(2, 2, 2)), tlv(0x83, bytes(4, 2, 1)),
                tlv(0x84, tlv(0x81, bytes(card.getJSONArray("profiles").length())), tlv(0x82, bytes(0x01, 0xF4, 0x00))),
                tlv(0xA9, tlv(0x04, unhex(GSMA_KEY)), tlv(0x04, unhex(OTHER_KEY))));
            case 0xBF20: return tlv(0xBF20, tlv(0x82, bytes(2, 2, 2)), tlv(0xA9, tlv(0x04, unhex(GSMA_KEY))),
                tlv(0xAA, tlv(0x04, unhex(GSMA_KEY))));
            case 0xBF2E: {
                byte[] challenge = new byte[16];
                random.nextBytes(challenge);
                return tlv(0xBF2E, tlv(0x80, challenge));
            }
            case 0xBF3C: {
                String dp = cfg.optString("defaultDp", "");
                return tlv(0xBF3C, dp.isEmpty() ? new byte[0] : tlv(0x80, utf8(dp)),
                    tlv(0x81, utf8(cfg.optString("rootDs", "lpa.ds.gsma.com"))));
            }
            // A made-up AuthenticateServerResponse: real servers check its signature, so they refuse it.
            case 0xBF38: return tlv(0xBF38, tlv(0xA0, tlv(0x30, new byte[16])));
            case 0xBF41: return tlv(0xBF41, tlv(0x80, bytes(0)));
            case 0xBF31: return result(0xBF31, setState(req, true));
            case 0xBF32: return result(0xBF32, setState(req, false));
            case 0xBF33: return result(0xBF33, delete(req));
            case 0xBF29: return result(0xBF29, nickname(req));
            case 0xBF28: return notices();
            case 0xBF2B: {
                JSONObject n = notice(seqOf(req));
                return tlv(0xBF2B, tlv(0xA0, n == null ? new byte[0] : tlv(0x30, metadata(n))));
            }
            case 0xBF30: {
                JSONObject n = notice(seqOf(req));
                if (n != null) remove(card.getJSONArray("notices"), n);
                return result(0xBF30, n == null ? 1 : 0);
            }
            default: return null;
        }
    }

    private byte[] profiles() throws Exception {
        ByteArrayOutputStream list = new ByteArrayOutputStream();
        JSONArray all = card.getJSONArray("profiles");
        for (int i = 0; i < all.length(); i++) {
            JSONObject p = all.getJSONObject(i);
            String nick = p.optString("nickname");
            list.write(tlv(0xE3, tlv(0x5A, iccidBytes(p.getString("iccid"))), tlv(0x4F, unhex(p.getString("aid"))),
                tlv(0x9F70, bytes(p.getBoolean("enabled") ? 1 : 0)), nick.isEmpty() ? new byte[0] : tlv(0x90, utf8(nick)),
                tlv(0x91, utf8(p.getString("provider"))), tlv(0x92, utf8(p.getString("name"))), tlv(0x95, bytes(2))));
        }
        return tlv(0xBF2D, tlv(0xA0, list.toByteArray()));
    }

    private int setState(Tlv req, boolean enable) throws Exception {
        JSONObject p = profile(req);
        if (p == null) return 1;
        if (p.getBoolean("enabled") == enable) return 2;
        JSONArray all = card.getJSONArray("profiles");
        for (int i = 0; i < all.length(); i++) {
            JSONObject other = all.getJSONObject(i);
            if (enable && other != p && other.getBoolean("enabled")) { other.put("enabled", false); addNotice(DISABLE, other); }
        }
        p.put("enabled", enable);
        addNotice(enable ? ENABLE : DISABLE, p);
        // REFRESH: the card restarts. Every open channel is gone, and it can't be opened for a while.
        card.put("refreshUntil", System.currentTimeMillis() + cfg.optLong("refreshMs", 3000));
        for (Channel ch : channels.values()) ch.dead = true;
        if (cfg.optBoolean("dropEnableReply")) {
            write(STATE, card);
            throw new IllegalStateException("The card reset before it answered");
        }
        return 0;
    }

    private int delete(Tlv req) throws Exception {
        JSONObject p = profile(req);
        if (p == null) return 1;
        if (p.getBoolean("enabled")) return 2;
        remove(card.getJSONArray("profiles"), p);
        addNotice(DELETE, p);
        return 0;
    }

    private int nickname(Tlv req) throws Exception {
        JSONObject p = profile(req);
        if (p == null) return 1;
        Tlv name = req.find(0x90);
        p.put("nickname", name == null ? "" : new String(name.value, StandardCharsets.UTF_8));
        return 0;
    }

    /** By ISD-P AID (4F) or ICCID (5A). */
    private JSONObject profile(Tlv req) throws Exception {
        Tlv aid = req.find(0x4F), iccid = req.find(0x5A);
        JSONArray all = card.getJSONArray("profiles");
        for (int i = 0; i < all.length(); i++) {
            JSONObject p = all.getJSONObject(i);
            if (aid != null && hex(aid.value).equalsIgnoreCase(p.getString("aid"))) return p;
            if (iccid != null && Arrays.equals(iccid.value, iccidBytes(p.getString("iccid")))) return p;
        }
        return null;
    }

    private void addNotice(int event, JSONObject p) throws Exception {
        int seq = card.optInt("nextSeq", 1);
        card.put("nextSeq", seq + 1);
        card.getJSONArray("notices").put(new JSONObject().put("seq", seq).put("event", event)
            .put("address", p.getString("smdp")).put("iccid", p.getString("iccid")));
    }

    private JSONObject notice(int seq) throws Exception {
        JSONArray all = card.getJSONArray("notices");
        for (int i = 0; i < all.length(); i++) if (all.getJSONObject(i).getInt("seq") == seq) return all.getJSONObject(i);
        return null;
    }

    private byte[] notices() throws Exception {
        ByteArrayOutputStream list = new ByteArrayOutputStream();
        JSONArray all = card.getJSONArray("notices");
        for (int i = 0; i < all.length(); i++) list.write(metadata(all.getJSONObject(i)));
        return tlv(0xBF28, tlv(0xA0, list.toByteArray()));
    }

    private static byte[] metadata(JSONObject n) throws Exception {
        return tlv(0xBF2F, tlv(0x80, BigInteger.valueOf(n.getInt("seq")).toByteArray()),
            tlv(0x81, bytes(4, 0x80 >>> n.getInt("event"))), tlv(0x0C, utf8(n.getString("address"))),
            tlv(0x5A, iccidBytes(n.getString("iccid"))));
    }

    private static int seqOf(Tlv req) {
        Tlv seq = req.find(0x80);
        return seq == null ? -1 : new BigInteger(seq.value).intValue();
    }

    private JSONObject useSim(JSONObject q, JSONObject out) throws Exception {
        if (cfg.optBoolean("useSimRefuse")) throw new SecurityException("Not allowed to change SIM settings");
        long ready = card.optLong("refreshUntil") + cfg.optLong("useSimDelayMs", 2000);
        JSONArray all = card.getJSONArray("profiles");
        for (int i = 0; i < all.length(); i++) {
            JSONObject p = all.getJSONObject(i);
            if (p.getBoolean("enabled") && System.currentTimeMillis() >= ready && p.getString("iccid").equals(q.getString("iccid")))
                return out.put("applied", true);
        }
        return out.put("applied", false);
    }

    private static JSONObject newCard() throws Exception {
        JSONArray profiles = new JSONArray()
            .put(fakeProfile("8988247000000000011", "A0000005591010FFFFFFFF8900001000", true, "Firsty", "smdp.firsty.invalid"))
            .put(fakeProfile("8988247000000000029", "A0000005591010FFFFFFFF8900001100", false, "Roamless", "smdp.roamless.invalid"))
            .put(fakeProfile("8944478600000000037", "A0000005591010FFFFFFFF8900001200", false, "1GLOBAL", "smdp.1global.invalid"));
        return new JSONObject().put("eid", "89049032000000000000000000012345").put("profiles", profiles)
            .put("notices", new JSONArray()).put("nextSeq", 1);
    }

    private static JSONObject fakeProfile(String iccid, String aid, boolean enabled, String name, String smdp) throws Exception {
        return new JSONObject().put("iccid", iccid).put("aid", aid).put("enabled", enabled).put("nickname", "")
            .put("provider", name).put("name", name).put("smdp", smdp);
    }

    // ---------------- bytes ----------------

    private static byte[] result(int tag, int code) { return tlv(tag, tlv(0x80, bytes(code))); }

    private static byte[] tlv(int tag, byte[]... parts) {
        ByteArrayOutputStream value = new ByteArrayOutputStream();
        for (byte[] part : parts) value.write(part, 0, part.length);
        int len = value.size();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (tag > 0xFF) out.write(tag >> 8);
        out.write(tag & 0xFF);
        if (len >= 0x100) { out.write(0x82); out.write(len >> 8); out.write(len & 0xFF); }
        else if (len >= 0x80) { out.write(0x81); out.write(len); }
        else out.write(len);
        out.write(value.toByteArray(), 0, len);
        return out.toByteArray();
    }

    private static byte[] bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++) b[i] = (byte) values[i];
        return b;
    }

    private static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    private static byte[] iccidBytes(String iccid) {
        StringBuilder padded = new StringBuilder(iccid);
        while (padded.length() < 20) padded.append('F');
        StringBuilder swapped = new StringBuilder();
        for (int i = 0; i < 20; i += 2) swapped.append(padded.charAt(i + 1)).append(padded.charAt(i));
        return unhex(swapped.toString());
    }

    private static String hex(byte[] b) {
        char[] digits = "0123456789ABCDEF".toCharArray();
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) { out[2 * i] = digits[(b[i] >> 4) & 0xF]; out[2 * i + 1] = digits[b[i] & 0xF]; }
        return new String(out);
    }

    private static byte[] unhex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    private static void remove(JSONArray array, JSONObject item) {
        for (int i = 0; i < array.length(); i++) if (array.opt(i) == item) { array.remove(i); return; }
    }

    private static JSONObject read(File file, JSONObject fallback) {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) > 0; ) bytes.write(buffer, 0, n);
            return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) { return fallback; }
    }

    private static void write(File file, JSONObject json) throws Exception {
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(json.toString(1).getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(file)) throw new IllegalStateException("Couldn't save " + file);
    }

    /** One BER-TLV; [value] is a copy, [end] the offset just past it in the parsed buffer. */
    private static final class Tlv {
        final int tag;
        final byte[] value;
        final int end;

        private Tlv(int tag, byte[] value, int end) { this.tag = tag; this.value = value; this.end = end; }

        static Tlv parse(byte[] b, int i) {
            int tag = b[i++] & 0xFF;
            if ((tag & 0x1F) == 0x1F) {
                int x;
                do { x = b[i++] & 0xFF; tag = (tag << 8) | x; } while ((x & 0x80) != 0);
            }
            int len = b[i++] & 0xFF;
            if ((len & 0x80) != 0) {
                int n = len & 0x7F;
                len = 0;
                while (n-- > 0) len = (len << 8) | (b[i++] & 0xFF);
            }
            return new Tlv(tag, Arrays.copyOfRange(b, i, i + len), i + len);
        }

        /** Depth first, through constructed values. */
        Tlv find(int want) {
            for (int i = 0; i < value.length; ) {
                Tlv child = parse(value, i);
                if (child.tag == want) return child;
                if (constructed(child.tag)) { Tlv found = child.find(want); if (found != null) return found; }
                i = child.end;
            }
            return null;
        }

        private static boolean constructed(int tag) {
            while (tag > 0xFF) tag >>= 8;
            return (tag & 0x20) != 0;
        }
    }
}
