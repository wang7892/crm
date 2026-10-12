package cn.cordys.crm.aiagent.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Component
public class AiAgentDatasourceSecrets {
    private final Path keyFile;
    private byte[] key;

    public AiAgentDatasourceSecrets(@Value("${crm.ai-agent.datasource.storage-path:runtime/ai-agent/datasources}") String path) {
        keyFile = Path.of(path).toAbsolutePath().normalize().resolve("connection.key");
    }

    private synchronized byte[] key(boolean create) throws Exception {
        if (key != null) return key;
        if (!Files.exists(keyFile)) {
            if (!create) throw new IllegalStateException("Missing datasource encryption key");
            AiAgentDatasourceStorage.directory(keyFile.getParent());
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            // Publish a complete key atomically; a competing instance must read the winning key.
            Path temp = Files.createTempFile(keyFile.getParent(), "connection-", ".tmp");
            try {
                AiAgentDatasourceStorage.ownerOnly(temp);
                Files.write(temp, generated);
                try { Files.move(temp, keyFile); } catch (FileAlreadyExistsException ignored) { }
            } finally { Files.deleteIfExists(temp); }
        }
        key = Files.readAllBytes(keyFile);
        if (key.length != 32) throw new IllegalStateException("Invalid datasource encryption key");
        return key;
    }

    public String encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(true), "AES"), new GCMParameterSpec(128, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] envelope = Arrays.copyOf(nonce, nonce.length + ciphertext.length);
            System.arraycopy(ciphertext, 0, envelope, nonce.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(envelope);
        } catch (Exception e) { throw new IllegalStateException("无法加密数据源密码，请检查服务端存储目录权限。", e); }
    }

    public String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) return "";
        try {
            byte[] envelope = Base64.getDecoder().decode(encrypted);
            if (envelope.length < 28) throw new IllegalArgumentException("Invalid ciphertext");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(false), "AES"), new GCMParameterSpec(128, envelope, 0, 12));
            return new String(cipher.doFinal(envelope, 12, envelope.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("无法解密数据源密码，请恢复原 connection.key 文件。", e); }
    }
}
