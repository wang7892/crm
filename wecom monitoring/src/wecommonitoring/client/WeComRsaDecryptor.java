package wecommonitoring.client;

import javax.crypto.Cipher;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

final class WeComRsaDecryptor {
    private static final String PRIVATE_KEY_BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String PRIVATE_KEY_END = "-----END PRIVATE KEY-----";
    private static final String RSA_PRIVATE_KEY_BEGIN = "-----BEGIN RSA PRIVATE KEY-----";
    private static final String RSA_PRIVATE_KEY_END = "-----END RSA PRIVATE KEY-----";

    private final PrivateKey privateKey;
    private final String disabledReason;

    private WeComRsaDecryptor(PrivateKey privateKey, String disabledReason) {
        this.privateKey = privateKey;
        this.disabledReason = disabledReason;
    }

    static WeComRsaDecryptor create(String privateKeyPem, String privateKeyPath) {
        try {
            String pem = loadPem(privateKeyPem, privateKeyPath);
            if (pem == null || pem.isBlank()) {
                return unavailable("WECOM_PRIVATE_KEY_PATH/WECOM_PRIVATE_KEY_PEM empty");
            }
            return new WeComRsaDecryptor(parsePrivateKey(pem), null);
        } catch (Exception ex) {
            return unavailable(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    static WeComRsaDecryptor unavailable(String reason) {
        return new WeComRsaDecryptor(null, reason);
    }

    boolean available() {
        return disabledReason == null;
    }

    String disabledReason() {
        return disabledReason;
    }

    String decryptRandomKey(String encryptedRandomKey) throws Exception {
        if (!available()) {
            throw new IllegalStateException(disabledReason);
        }
        if (encryptedRandomKey == null || encryptedRandomKey.isBlank()) {
            throw new IllegalArgumentException("encrypt_random_key is blank");
        }
        byte[] encrypted = decodeBase64(encryptedRandomKey);
        Exception lastError = null;
        for (String transformation : new String[]{"RSA/ECB/PKCS1Padding", "RSA/ECB/OAEPWithSHA-1AndMGF1Padding"}) {
            try {
                Cipher cipher = Cipher.getInstance(transformation);
                cipher.init(Cipher.DECRYPT_MODE, privateKey);
                return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
            } catch (Exception ex) {
                lastError = ex;
            }
        }
        throw lastError == null ? new IllegalStateException("RSA decrypt failed") : lastError;
    }

    private static String loadPem(String privateKeyPem, String privateKeyPath) throws Exception {
        if (privateKeyPath != null && !privateKeyPath.isBlank()) {
            return Files.readString(Path.of(privateKeyPath.trim()), StandardCharsets.UTF_8);
        }
        return privateKeyPem == null ? null : privateKeyPem.replace("\\n", "\n").trim();
    }

    private static PrivateKey parsePrivateKey(String pem) throws Exception {
        String normalized = pem.trim();
        boolean pkcs1 = normalized.contains(RSA_PRIVATE_KEY_BEGIN);
        String base64 = normalized
                .replace(PRIVATE_KEY_BEGIN, "")
                .replace(PRIVATE_KEY_END, "")
                .replace(RSA_PRIVATE_KEY_BEGIN, "")
                .replace(RSA_PRIVATE_KEY_END, "")
                .replaceAll("\\s+", "");
        byte[] keyBytes = decodeBase64(base64);
        if (pkcs1) {
            keyBytes = wrapPkcs1RsaPrivateKey(keyBytes);
        }
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
    }

    private static byte[] decodeBase64(String value) {
        String compact = value.replaceAll("\\s+", "");
        try {
            return Base64.getDecoder().decode(compact);
        } catch (IllegalArgumentException ex) {
            return Base64.getMimeDecoder().decode(value);
        }
    }

    private static byte[] wrapPkcs1RsaPrivateKey(byte[] pkcs1) throws Exception {
        byte[] version = new byte[]{0x02, 0x01, 0x00};
        byte[] algorithmIdentifier = new byte[]{
                0x30, 0x0D,
                0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01,
                0x05, 0x00
        };
        byte[] privateKeyOctet = derEncode(0x04, pkcs1);
        return derEncode(0x30, concat(version, algorithmIdentifier, privateKeyOctet));
    }

    private static byte[] derEncode(int tag, byte[] value) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        writeDerLength(out, value.length);
        out.write(value);
        return out.toByteArray();
    }

    private static void writeDerLength(ByteArrayOutputStream out, int length) {
        if (length < 128) {
            out.write(length);
            return;
        }
        int temp = length;
        int bytes = 0;
        while (temp > 0) {
            bytes++;
            temp >>= 8;
        }
        out.write(0x80 | bytes);
        for (int i = bytes - 1; i >= 0; i--) {
            out.write((length >> (8 * i)) & 0xFF);
        }
    }

    private static byte[] concat(byte[]... chunks) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            out.write(chunk);
        }
        return out.toByteArray();
    }
}
