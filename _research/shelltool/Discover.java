package com.esimonsim;

import java.lang.reflect.*;

public class Discover {
    public static void main(String[] a) throws Exception {
        Class<?> req = Class.forName("com.android.internal.telephony.IccLogicalChannelRequest");
        System.out.println("== IccLogicalChannelRequest fields ==");
        for (Field f : req.getDeclaredFields())
            System.out.println("  " + Modifier.toString(f.getModifiers()) + " " + f.getType().getName() + " " + f.getName());
        System.out.println("== constructors ==");
        for (Constructor<?> c : req.getDeclaredConstructors()) System.out.println("  " + c);
        Class<?> itelC = Class.forName("com.android.internal.telephony.ITelephony");
        System.out.println("== transmit/close methods ==");
        for (Method m : itelC.getMethods()) {
            String n = m.getName();
            if (n.contains("iccTransmit") || n.contains("iccClose")) System.out.println("  " + m);
        }
    }
}
