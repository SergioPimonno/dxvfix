package dxvfix.license.tools;

import dxvfix.license.LicenseIssuer;
import dxvfix.license.LicenseRecord;

import java.nio.file.Path;
import java.security.PrivateKey;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CLI variant of the "keygen for other devices" tool. Prefer {@link LicenseAdminGui} for everyday
 * use (double-click, no terminal needed); this remains for scripting.
 * <p>
 * Usage:
 * <pre>
 *   java -cp dxvfix.jar dxvfix.license.tools.GenerateLicenseMain \
 *        --key path\to\license_private.key \
 *        --fingerprint AA:BB:CC:DD:EE:FF \
 *        --out ivan-workstation.lic \
 *        [--label "Ivan's workstation"] \
 *        [--expires 2027-01-01]
 * </pre>
 */
public final class GenerateLicenseMain {

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        String keyPath = require(opts, "key");
        String fingerprint = require(opts, "fingerprint");
        String outPath = require(opts, "out");
        String label = opts.getOrDefault("label", "");
        String expiresStr = opts.get("expires");

        PrivateKey privateKey = LicenseIssuer.loadPrivateKey(Path.of(keyPath));
        LocalDate issued = LocalDate.now();
        LocalDate expires = expiresStr == null ? null : LocalDate.parse(expiresStr);

        LicenseRecord record = LicenseIssuer.issue(privateKey, fingerprint, label, issued, expires, Path.of(outPath));

        System.out.println("Issued license: " + Path.of(outPath).toAbsolutePath());
        System.out.println("  fingerprint = " + record.fingerprint);
        System.out.println("  label       = " + record.label);
        System.out.println("  issued      = " + record.issued);
        System.out.println("  expires     = " + (record.expires == null ? "(never)" : record.expires));
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                String key = a.substring(2);
                String value = (i + 1 < args.length) ? args[++i] : "";
                map.put(key, value);
            }
        }
        return map;
    }

    private static String require(Map<String, String> opts, String key) {
        String v = opts.get(key);
        if (v == null || v.isBlank()) {
            System.err.println("Missing required --" + key + " argument.");
            printUsage();
            System.exit(1);
        }
        return v;
    }

    private static void printUsage() {
        System.err.println("Usage: java -cp dxvfix.jar dxvfix.license.tools.GenerateLicenseMain " +
                "--key <license_private.key> --fingerprint <AA:BB:CC:DD:EE:FF> --out <file.lic> " +
                "[--label <text>] [--expires <yyyy-MM-dd>]");
    }
}
