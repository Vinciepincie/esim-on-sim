package com.esimonsim;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Standalone shell probe (run via app_process as uid shell=2000).
 * Verifies the telephony logical-channel path (gated by MODIFY_PHONE_STATE,
 * NOT by the card's OMAPI ARA) can open the eUICC ISD-R applet and read the EID.
 */
public class ShellProbe {
    static final String ISD_R = "A0000005591010FFFFFFFF8900000100";
    static final String PKG = "com.android.shell";

    public static void main(String[] args) {
        try { run(args); }
        catch (Throwable t) {
            System.out.println("EXC " + t.getClass().getName() + ": " + t.getMessage());
            Throwable c = t.getCause();
            while (c != null) { System.out.println("  caused by " + c.getClass().getName() + ": " + c.getMessage()); c = c.getCause(); }
        }
        System.out.flush();
    }

    static void run(String[] args) throws Exception {
        int slot = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        int subId = args.length > 2 ? Integer.parseInt(args[2]) : -1;
        // Some framework paths need a prepared looper for binder callbacks.
        try { Class<?> lp = Class.forName("android.os.Looper"); lp.getMethod("prepareMainLooper").invoke(null); } catch (Throwable ignore) {}
        System.out.println("== ShellProbe telephony path slot=" + slot + " port=" + port + " subId=" + subId + " ==");

        Class<?> ibinder = Class.forName("android.os.IBinder");
        Object binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "phone");
        Object itel = Class.forName("com.android.internal.telephony.ITelephony$Stub")
                .getMethod("asInterface", ibinder).invoke(null, binder);
        Class<?> itelC = Class.forName("com.android.internal.telephony.ITelephony");

        // Build IccLogicalChannelRequest
        Class<?> reqC = Class.forName("com.android.internal.telephony.IccLogicalChannelRequest");
        Object req = reqC.getConstructor().newInstance();
        set(reqC, req, "aid", ISD_R);
        set(reqC, req, "callingPackage", PKG);
        setI(reqC, req, "slotIndex", slot);
        setI(reqC, req, "portIndex", port);
        setI(reqC, req, "subId", subId);
        setI(reqC, req, "p2", 0);
        Object tok = Class.forName("android.os.Binder").getConstructor().newInstance();
        reqC.getField("binder").set(req, tok);

        Object resp = itelC.getMethod("iccOpenLogicalChannel", reqC).invoke(itel, req);
        if (resp == null) { System.out.println("open returned null"); return; }
        int ch = (int) resp.getClass().getMethod("getChannel").invoke(resp);
        int status = (int) resp.getClass().getMethod("getStatus").invoke(resp);
        byte[] sel = (byte[]) resp.getClass().getMethod("getSelectResponse").invoke(resp);
        System.out.println("open status=" + status + " channel=" + ch);
        System.out.println("SELECT=" + (sel == null ? "null" : hex(sel)));
        if (ch <= 0) { System.out.println(">>> FAILED to open ISD-R (status=" + status + ")"); return; }
        System.out.println(">>> ISD-R channel OPEN via telephony path (ARA bypassed)");

        Method tx = itelC.getMethod("iccTransmitApduLogicalChannelByPort",
                int.class, int.class, int.class, int.class, int.class, int.class, int.class, int.class, String.class);
        // GET EID: STORE DATA 80 E2 91 00 06 BF3E035C015A
        String r = (String) tx.invoke(itel, slot, port, ch, 0x80, 0xE2, 0x91, 0x00, 0x06, "BF3E035C015A");
        System.out.println("GET EID resp=" + r);
        byte[] eid = findTag(fromHex(r), 0x5A);
        if (eid != null) System.out.println(">>> EID=" + hex(eid));
        // GetEuiccInfo1: STORE DATA 80 E2 91 00 02 BF20
        String info = (String) tx.invoke(itel, slot, port, ch, 0x80, 0xE2, 0x91, 0x00, 0x02, "BF20");
        System.out.println("EUICCInfo1(head)=" + (info == null ? "null" : info.substring(0, Math.min(100, info.length()))));

        // close
        setI(reqC, req, "channel", ch);
        try { itelC.getMethod("iccCloseLogicalChannel", reqC).invoke(itel, req); } catch (Throwable t) {}
        System.out.println("== done ==");
    }

    static void set(Class<?> c, Object o, String f, String v) throws Exception { c.getField(f).set(o, v); }
    static void setI(Class<?> c, Object o, String f, int v) throws Exception { c.getField(f).setInt(o, v); }
    static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02X", x)); return s.toString(); }
    static byte[] fromHex(String s) { if (s == null) return new byte[0]; byte[] o = new byte[s.length()/2]; for (int i=0;i<o.length;i++) o[i]=(byte)Integer.parseInt(s.substring(2*i,2*i+2),16); return o; }
    static byte[] findTag(byte[] tlv, int want) {
        int i=0; while (i<tlv.length){ int first=tlv[i]&0xFF; boolean cons=(first&0x20)!=0; int tag=first; i++;
            if ((first&0x1F)==0x1F){ while(i<tlv.length){ int b=tlv[i]&0xFF; tag=(tag<<8)|b; i++; if((b&0x80)==0)break; } }
            if (i>=tlv.length) break; int len=tlv[i]&0xFF; i++;
            if ((len&0x80)!=0){ int n=len&0x7F; len=0; for(int k=0;k<n&&i<tlv.length;k++){len=(len<<8)|(tlv[i]&0xFF);i++;} }
            if (i+len>tlv.length) break;
            if (tag==want){ byte[] o=new byte[len]; System.arraycopy(tlv,i,o,0,len); return o; }
            if (cons){ byte[] sub=new byte[len]; System.arraycopy(tlv,i,sub,0,len); byte[] r=findTag(sub,want); if(r!=null) return r; }
            i+=len; }
        return null;
    }
}
