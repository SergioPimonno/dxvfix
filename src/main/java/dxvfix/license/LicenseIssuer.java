package dxvfix.license;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDate;

/** Shared signing logic used by both the CLI keygen and the standalone license admin GUI. */
public final class LicenseIssuer {

    private LicenseIssuer() {
    }

    public static String normalizeFingerprint(String s) {
        return s.trim().toUpperCase();
    }

    public static PrivateKey loadPrivateKey(Path path) throws Exception {
        String base64 = Files.readString(path, StandardCharsets.UTF_8).strip();
        byte[] der = java.util.Base64.getDecoder().decode(base64);
        KeyFactory kf = KeyFactory.getInstance("EC");
        return kf.generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /** Signs and writes a license file for the given fields; returns the record that was written. */
    public static LicenseRecord issue(PrivateKey privateKey, String fingerprint, String label,
                                       LocalDate issued, LocalDate expires, Path out) throws Exception {
        LicenseRecord record = new LicenseRecord(normalizeFingerprint(fingerprint), label, issued, expires);
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(privateKey);
        sig.update(record.canonicalBytes());
        byte[] signature = sig.sign();
        record.writeSigned(out, signature);
        return record;
    }
}
