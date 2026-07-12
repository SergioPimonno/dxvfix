package dxvfix.license;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A license's payload fields plus its ECDSA signature, in a simple deterministic text format. */
public final class LicenseRecord {

    public final String fingerprint;
    public final String label;
    public final LocalDate issued;
    public final LocalDate expires; // nullable

    public LicenseRecord(String fingerprint, String label, LocalDate issued, LocalDate expires) {
        this.fingerprint = fingerprint;
        this.label = label == null ? "" : label;
        this.issued = issued;
        this.expires = expires;
    }

    /** The exact bytes that get signed / whose signature gets verified. */
    public byte[] canonicalBytes() {
        StringBuilder sb = new StringBuilder();
        sb.append("fingerprint=").append(fingerprint).append('\n');
        sb.append("label=").append(label).append('\n');
        sb.append("issued=").append(issued).append('\n');
        sb.append("expires=").append(expires == null ? "" : expires).append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public void writeSigned(Path out, byte[] signature) throws IOException {
        String text = new String(canonicalBytes(), StandardCharsets.UTF_8)
                + "signature=" + java.util.Base64.getEncoder().encodeToString(signature) + "\n";
        Files.writeString(out, text, StandardCharsets.UTF_8);
    }

    /** Parses a .lic file into its record fields plus the raw signature bytes. */
    public static Parsed parse(Path licenseFile) throws IOException {
        List<String> lines = Files.readAllLines(licenseFile, StandardCharsets.UTF_8);
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : lines) {
            line = line.strip();
            if (line.isEmpty()) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            fields.put(line.substring(0, eq), line.substring(eq + 1));
        }
        String fingerprint = fields.getOrDefault("fingerprint", "");
        String label = fields.getOrDefault("label", "");
        String issuedStr = fields.getOrDefault("issued", "");
        String expiresStr = fields.getOrDefault("expires", "");
        String sigStr = fields.getOrDefault("signature", "");
        if (fingerprint.isEmpty() || issuedStr.isEmpty() || sigStr.isEmpty()) {
            throw new IOException("License file is missing required fields (fingerprint/issued/signature)");
        }
        LocalDate issued = LocalDate.parse(issuedStr);
        LocalDate expires = expiresStr.isBlank() ? null : LocalDate.parse(expiresStr);
        LicenseRecord record = new LicenseRecord(fingerprint, label, issued, expires);
        byte[] signature = java.util.Base64.getDecoder().decode(sigStr);
        return new Parsed(record, signature);
    }

    public static final class Parsed {
        public final LicenseRecord record;
        public final byte[] signature;

        Parsed(LicenseRecord record, byte[] signature) {
            this.record = record;
            this.signature = signature;
        }
    }
}
