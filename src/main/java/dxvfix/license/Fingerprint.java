package dxvfix.license;

import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Computes a machine identifier from the lowest-sorted eligible network adapter's MAC address.
 * <p>
 * This is deliberately simple, per the intended use (node-locking this internal tool to specific
 * team machines) rather than DRM-grade hardware attestation. Caveat worth knowing: a MAC address
 * can be changed in software, a machine can have no eligible adapter (rare, but e.g. a stripped-
 * down VM), and swapping/disabling the chosen NIC changes the fingerprint. It is combined with
 * signed license files (see LicenseVerifier) so that even though the identifier itself is
 * spoofable, a *valid license* for a spoofed identifier can still only be produced by whoever
 * holds the private signing key.
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    /** Returns a stable, human-shareable machine identifier, e.g. "AA:BB:CC:DD:EE:FF". */
    public static String compute() {
        String mac = primaryMacAddress();
        if (mac == null) {
            throw new IllegalStateException("No eligible network adapter with a MAC address was found on this machine");
        }
        return mac;
    }

    private static String primaryMacAddress() {
        List<String> candidates = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (ni.isLoopback() || ni.isVirtual()) continue;
                byte[] mac = ni.getHardwareAddress();
                if (mac == null || mac.length != 6) continue;
                if (isAllZero(mac)) continue;
                candidates.add(format(mac));
            }
        } catch (Exception e) {
            return null;
        }
        if (candidates.isEmpty()) return null;
        Collections.sort(candidates);
        return candidates.get(0);
    }

    private static boolean isAllZero(byte[] mac) {
        for (byte b : mac) if (b != 0) return false;
        return true;
    }

    private static String format(byte[] mac) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mac.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02X", mac[i]));
        }
        return sb.toString();
    }
}
