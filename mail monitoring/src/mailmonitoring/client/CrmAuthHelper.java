package mailmonitoring.client;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

public class CrmAuthHelper {
    private static final int GCM_TAG_LENGTH = 128;

    private CrmAuthHelper() {
    }

    public static String buildAuthorization(String accessKey, String secretKey) {
        String plain = accessKey + "|" + UUID.randomUUID() + "|" + System.currentTimeMillis();
        String signature = aesGcmEncrypt(plain, secretKey, accessKey.getBytes(StandardCharsets.UTF_8));
        return accessKey + ":" + signature;
    }

    /**
     * CLI helper to print Authorization header value.
     *
     * Usage:
     * java -cp ".\\out" mailmonitoring.client.CrmAuthHelper <accessKey> <secretKey>
     */
    public static void main(String[] args) {
        if (args == null || args.length < 2) {
            System.out.println("Usage: java -cp \".\\\\out\" mailmonitoring.client.CrmAuthHelper <accessKey> <secretKey>");
            return;
        }
        String accessKey = args[0];
        String secretKey = args[1];
        System.out.println(buildAuthorization(accessKey, secretKey));
    }

    private static String aesGcmEncrypt(String src, String secretKey, byte[] iv) {
        try {
            byte[] keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
            if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
                throw new IllegalArgumentException("secretKey length must be 16/24/32 bytes for AES.");
            }
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
            byte[] encrypted = cipher.doFinal(src.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("build signature failed", e);
        }
    }
}
