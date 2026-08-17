package dev.frostguard.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

public final class ProjectManifestSigner {
    static final String PRIVATE_KEY_ENV = "FROSTGUARD_UPDATE_SIGNING_PRIVATE_KEY_BASE64";

    private ProjectManifestSigner() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 3 && "sign".equals(arguments[0])) {
            String privateKey = System.getenv(PRIVATE_KEY_ENV);
            if (privateKey == null || privateKey.isBlank()) {
                throw new IllegalArgumentException(PRIVATE_KEY_ENV + " is not configured");
            }
            byte[] payload = Files.readAllBytes(Path.of(arguments[1]));
            byte[] envelope = sign(payload, ProjectUpdateKey.current().keyId(), privateKey);
            writeNew(Path.of(arguments[2]), envelope);
            return;
        }
        if (arguments.length == 3 && "generate".equals(arguments[0])) {
            generate(Path.of(arguments[1]), Path.of(arguments[2]));
            return;
        }
        if (arguments.length == 2 && "verify".equals(arguments[0])) {
            UpdateManifest manifest = new SignedManifestCodec().read(
                    Files.readAllBytes(Path.of(arguments[1])), ProjectUpdateKey.current());
            System.out.println("Verified project-signed " + manifest.channel()
                    + " manifest for " + manifest.version());
            return;
        }
        throw new IllegalArgumentException("Usage: sign <manifest-payload> <signed-envelope> "
                + "or verify <signed-envelope> or generate <private-key-output> <public-key-output>");
    }

    static byte[] sign(byte[] payload, String keyId, String privateKeyBase64) throws GeneralSecurityException {
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("Manifest payload must not be empty");
        }
        if (!ManifestVerificationKey.isValidKeyId(keyId)) {
            throw new IllegalArgumentException("Update-signing key ID is invalid");
        }
        byte[] encodedPrivateKey = Base64.getDecoder().decode(privateKeyBase64.trim());
        PrivateKey privateKey = KeyFactory.getInstance("Ed25519")
                .generatePrivate(new PKCS8EncodedKeySpec(encodedPrivateKey));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(payload);
        String envelope = """
                {
                  "envelopeVersion": 1,
                  "algorithm": "Ed25519",
                  "keyId": "%s",
                  "payload": "%s",
                  "signature": "%s"
                }
                """.formatted(
                keyId,
                Base64.getEncoder().encodeToString(payload),
                Base64.getEncoder().encodeToString(signer.sign()));
        return envelope.getBytes(StandardCharsets.UTF_8);
    }

    static void generate(Path privateKeyOutput, Path publicKeyOutput) throws GeneralSecurityException, IOException {
        if (privateKeyOutput.toAbsolutePath().normalize()
                .equals(publicKeyOutput.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Private and public key outputs must be different");
        }
        if (Files.exists(privateKeyOutput)) {
            throw new FileAlreadyExistsException(privateKeyOutput.toString());
        }
        if (Files.exists(publicKeyOutput)) {
            throw new FileAlreadyExistsException(publicKeyOutput.toString());
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        KeyPair keyPair = generator.generateKeyPair();
        writeNew(privateKeyOutput, Base64.getEncoder().encode(keyPair.getPrivate().getEncoded()));
        writeNew(publicKeyOutput, Base64.getEncoder().encode(keyPair.getPublic().getEncoded()));
    }

    private static void writeNew(Path output, byte[] content) throws IOException {
        Path parent = output.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(output, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
}
