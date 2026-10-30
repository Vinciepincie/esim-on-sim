package com.esimonsim;

import java.lang.reflect.Method;

/** Read-only card access experiment. Uses direct shell Binder calls, no Shizuku.
 * Opens/selects a logical channel, reads EID (never prints it), and closes it.
 * Run with app_process through an already authorized ADB connection.
 */
public final class DirectAccessProbe {
    public static void main(String[] args) throws Exception {
        int slot = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        Class.forName("android.os.Looper").getMethod("prepareMainLooper").invoke(null);
        Object uid = Class.forName("android.os.Process").getMethod("myUid").invoke(null);
        System.out.println("Direct Binder probe uid=" + uid + " slot=" + slot);
        Class<?> binderClass = Class.forName("android.os.IBinder");
        Object binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "phone");
        Object service = Class.forName("com.android.internal.telephony.ITelephony$Stub")
                .getMethod("asInterface", binderClass).invoke(null, binder);
        Class<?> telephony = Class.forName("com.android.internal.telephony.ITelephony");
        Class<?> requestClass = Class.forName("com.android.internal.telephony.IccLogicalChannelRequest");
        Method transmit = telephony.getMethod("iccTransmitApduLogicalChannelByPort",
                int.class, int.class, int.class, int.class, int.class, int.class,
                int.class, int.class, String.class);
        String[] aids = {
                "A0000005591010FFFFFFFF8900000100",
                "A0000005591010FFFFFFFF89000001",
                "A0000005591010FFFFFFFF8900050500"
        };
        for (String aid : aids) {
            Object request = requestClass.getConstructor().newInstance();
            requestClass.getField("aid").set(request, aid);
            requestClass.getField("callingPackage").set(request, "com.android.shell");
            requestClass.getField("slotIndex").setInt(request, slot);
            requestClass.getField("portIndex").setInt(request, 0);
            requestClass.getField("subId").setInt(request, -1);
            requestClass.getField("p2").setInt(request, 0);
            Object token = Class.forName("android.os.Binder").getConstructor().newInstance();
            requestClass.getField("binder").set(request, token);
            int channel = -1;
            System.out.println("AID=" + aid);
            try {
                Object response = telephony.getMethod("iccOpenLogicalChannel", requestClass)
                        .invoke(service, request);
                if (response == null) { System.out.println("open=null"); continue; }
                channel = (int) response.getClass().getMethod("getChannel").invoke(response);
                Object status = response.getClass().getMethod("getStatus").invoke(response);
                System.out.println("open status=" + status + " channel=" + channel);
                if (channel <= 0) continue;
                String result = (String) transmit.invoke(service, slot, 0, channel,
                        0x80, 0xE2, 0x91, 0, 6, "BF3E035C015A");
                StringBuilder body = new StringBuilder();
                for (int count = 0; count < 16; count++) {
                    if (result == null || result.length() < 4) throw new Exception("Short response");
                    String sw = result.substring(result.length() - 4);
                    body.append(result, 0, result.length() - 4);
                    if (sw.startsWith("61")) {
                        result = (String) transmit.invoke(service, slot, 0, channel,
                                0, 0xC0, 0, 0, Integer.parseInt(sw.substring(2), 16), "");
                        continue;
                    }
                    byte[] eid = ShellProbe.findTag(ShellProbe.fromHex(body.toString()), 0x5A);
                    System.out.println("GET EID sw=" + sw + " valid16Bytes="
                            + ("9000".equals(sw) && eid != null && eid.length == 16));
                    break;
                }
            } catch (Throwable error) {
                while (error.getCause() != null) error = error.getCause();
                System.out.println(error.getClass().getSimpleName() + ": " + error.getMessage());
            } finally {
                if (channel > 0) {
                    requestClass.getField("channel").setInt(request, channel);
                    Object closed = telephony.getMethod("iccCloseLogicalChannel", requestClass)
                            .invoke(service, request);
                    System.out.println("close=" + closed);
                }
            }
        }
    }
}
