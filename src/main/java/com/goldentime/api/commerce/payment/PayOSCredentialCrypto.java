package com.goldentime.api.commerce.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class PayOSCredentialCrypto {
    private static final String VERSION = "v1";
    private static final int NONCE_BYTES = 12;
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public PayOSCredentialCrypto(@Value("${app.payment.payos.encryption-key:}") String encodedKey) {
        try {
            this.key = encodedKey == null || encodedKey.isBlank() ? null : Base64.getDecoder().decode(encodedKey.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("PAYOS_CREDENTIAL_ENCRYPTION_KEY must be a Base64-encoded 32-byte key.", exception);
        }
        if (key != null && key.length != 32) {
            throw new IllegalStateException("PAYOS_CREDENTIAL_ENCRYPTION_KEY must decode to exactly 32 bytes.");
        }
    }

    public String encrypt(String value) {
        if (key == null) throw new IllegalStateException("PAYOS_CREDENTIAL_ENCRYPTION_KEY is not configured.");
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return VERSION + ":" + Base64.getEncoder().encodeToString(nonce) + ":" + Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not encrypt PayOS credentials.", exception);
        }
    }

    public String decrypt(String value) {
        if (key == null) throw new IllegalStateException("PAYOS_CREDENTIAL_ENCRYPTION_KEY is not configured.");
        try {
            String[] parts = value.split(":", 3);
            if (parts.length != 3 || !VERSION.equals(parts[0])) throw new IllegalArgumentException("Unknown encrypted credential format.");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, Base64.getDecoder().decode(parts[1])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[2])), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not decrypt PayOS credentials.", exception);
        }
    }
}
