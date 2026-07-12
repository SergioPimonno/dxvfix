package dxvfix.license;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;

/** Verifies a signed .lic file (see LicenseRecord) against the app's embedded public key. */
public final class LicenseVerifier {

    private static final String PUBLIC_KEY_RESOURCE = "/dxvfix/license/public.key";

    public enum Status {
        VALID, FILE_NOT_FOUND, MALFORMED, BAD_SIGNATURE, FINGERPRINT_MISMATCH, EXPIRED, NO_PUBLIC_KEY
    }

    public static final class Result {
        public final Status status;
        public final String message;
        public final LicenseRecord record;

        Result(Status status, String message, LicenseRecord record) {
            this.status = status;
            this.message = message;
            this.record = record;
        }

        public boolean isValid() {
            return status == Status.VALID;
        }
    }

    private LicenseVerifier() {
    }

    public static Path defaultLicensePath() {
        String appData = System.getenv("APPDATA");
        Path base = appData != null ? Path.of(appData) : Path.of(System.getProperty("user.home"));
        return base.resolve("DxvFrameDoctor").resolve("license.lic");
    }

    public static Result verify(Path licenseFile) {
        if (!Files.isRegularFile(licenseFile)) {
            return new Result(Status.FILE_NOT_FOUND, "License file not found: " + licenseFile, null);
        }

        PublicKey publicKey;
        try {
            publicKey = loadPublicKey();
        } catch (Exception e) {
            return new Result(Status.NO_PUBLIC_KEY, "Could not load embedded public key: " + e.getMessage(), null);
        }

        LicenseRecord.Parsed parsed;
        try {
            parsed = LicenseRecord.parse(licenseFile);
        } catch (Exception e) {
            return new Result(Status.MALFORMED, "License file is malformed: " + e.getMessage(), null);
        }

        try {
            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initVerify(publicKey);
            sig.update(parsed.record.canonicalBytes());
            if (!sig.verify(parsed.signature)) {
                return new Result(Status.BAD_SIGNATURE, "License signature does not verify", parsed.record);
            }
        } catch (Exception e) {
            return new Result(Status.BAD_SIGNATURE, "License signature check failed: " + e.getMessage(), parsed.record);
        }

        String currentFingerprint;
        try {
            currentFingerprint = Fingerprint.compute();
        } catch (Exception e) {
            return new Result(Status.FINGERPRINT_MISMATCH, "Could not compute this machine's fingerprint: " + e.getMessage(), parsed.record);
        }
        if (!currentFingerprint.equalsIgnoreCase(parsed.record.fingerprint)) {
            return new Result(Status.FINGERPRINT_MISMATCH,
                    "This license is for machine " + parsed.record.fingerprint + ", but this machine is " + currentFingerprint,
                    parsed.record);
        }

        if (parsed.record.expires != null && LocalDate.now().isAfter(parsed.record.expires)) {
            return new Result(Status.EXPIRED, "License expired on " + parsed.record.expires, parsed.record);
        }

        return new Result(Status.VALID, "OK", parsed.record);
    }

    private static PublicKey loadPublicKey() throws Exception {
        try (InputStream in = LicenseVerifier.class.getResourceAsStream(PUBLIC_KEY_RESOURCE)) {
            if (in == null) {
                throw new IOException("Embedded public key resource not found: " + PUBLIC_KEY_RESOURCE);
            }
            String base64 = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            byte[] der = java.util.Base64.getDecoder().decode(base64);
            KeyFactory kf = KeyFactory.getInstance("EC");
            return kf.generatePublic(new X509EncodedKeySpec(der));
        }
    }
}
