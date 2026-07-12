package dxvfix.license.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;

/**
 * One-time setup tool: generates the EC keypair used to sign/verify license files.
 * <p>
 * Run this ONCE. Keep {@code license_private.key} secret — never commit it, never put it in the
 * app's jar. Copy {@code license_public.key} into {@code src/main/resources/dxvfix/license/public.key}
 * before building the app jar that you distribute; that public key is what every copy of the app
 * uses to verify licenses. Only whoever holds the private key file can produce a license that a
 * distributed app will accept.
 * <p>
 * Usage: {@code java -cp dxvfix.jar dxvfix.license.tools.GenerateKeyPairMain <outputDir>}
 */
public final class GenerateKeyPairMain {

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: GenerateKeyPairMain <outputDir>");
            System.exit(1);
        }
        Path outDir = Path.of(args[0]);
        Files.createDirectories(outDir);

        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = gen.generateKeyPair();

        Path privPath = outDir.resolve("license_private.key");
        Path pubPath = outDir.resolve("license_public.key");
        Files.writeString(privPath, java.util.Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()), StandardCharsets.UTF_8);
        Files.writeString(pubPath, java.util.Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()), StandardCharsets.UTF_8);

        System.out.println("Wrote private key (KEEP SECRET): " + privPath.toAbsolutePath());
        System.out.println("Wrote public key (bundle into the app): " + pubPath.toAbsolutePath());
        System.out.println();
        System.out.println("Next step: copy " + pubPath.getFileName() +
                " to src/main/resources/dxvfix/license/public.key (renaming it) and rebuild the app.");
    }
}
