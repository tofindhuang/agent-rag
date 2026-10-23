package com.agentcore.server.common.crypto;

import com.agentcore.server.config.ModelProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** AES-GCM encryption for user-supplied model API keys (ADR-0010). */
@Component
public class ApiKeyCipher {

    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyCipher(ModelProperties properties) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(
                    properties.secretKey() == null ? "" : properties.secretKey());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "MODEL_SECRET_KEY must be base64-encoded 32 bytes", ex);
        }
        if (decoded.length != KEY_BYTES) {
            throw new IllegalStateException("MODEL_SECRET_KEY must be base64-encoded 32 bytes");
        }
        this.key = new SecretKeySpec(decoded, "AES");
    }

    /** Returns base64(iv || ciphertext), bound to the owning user via AAD. */
    public String encrypt(long userId, String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(Long.toString(userId).getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("failed to encrypt API key", ex);
        }
    }

    public String decrypt(long userId, String payload) {
        try {
            byte[] decoded = Base64.getDecoder().decode(payload);
            if (decoded.length <= IV_BYTES) {
                throw new IllegalStateException("invalid encrypted API key");
            }
            byte[] iv = new byte[IV_BYTES];
            byte[] encrypted = new byte[decoded.length - IV_BYTES];
            System.arraycopy(decoded, 0, iv, 0, IV_BYTES);
            System.arraycopy(decoded, IV_BYTES, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(Long.toString(userId).getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("failed to decrypt API key", ex);
        }
    }

    /** Masked hint for display; never the full key. */
    public static String hint(String plaintext) {
        return plaintext.length() <= 4 ? "••••" : "••••" + plaintext.substring(plaintext.length() - 4);
    }
}
