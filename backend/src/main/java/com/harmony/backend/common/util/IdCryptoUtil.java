package com.harmony.backend.common.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Component
@Slf4j
public class IdCryptoUtil {

    @Value("${app.id-encryption.secret:your-32-byte-secret-key-for-id-encryption}")
    private String secretKey;

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/ECB/PKCS5Padding";

    public String encryptId(Long id) {
        if (id == null) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            SecretKeySpec keySpec = new SecretKeySpec(getValidKey(), ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            String idStr = String.valueOf(id);
            byte[] encrypted = cipher.doFinal(idStr.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
        } catch (Exception e) {
            return fallbackEncrypt(id);
        }
    }

    public Long decryptId(String encryptedId) {
        if (encryptedId == null || encryptedId.trim().isEmpty()) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            SecretKeySpec keySpec = new SecretKeySpec(getValidKey(), ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec);

            byte[] decoded = Base64.getUrlDecoder().decode(encryptedId);
            byte[] decrypted = cipher.doFinal(decoded);
            String idStr = new String(decrypted, StandardCharsets.UTF_8);
            return Long.parseLong(idStr);
        } catch (Exception e) {
            return fallbackDecrypt(encryptedId);
        }
    }

    private byte[] getValidKey() {
        byte[] keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length == 16 || keyBytes.length == 24 || keyBytes.length == 32) {
            return keyBytes;
        }
        byte[] validKey = new byte[32];
        System.arraycopy(keyBytes, 0, validKey, 0, Math.min(keyBytes.length, 32));
        return validKey;
    }

    private String fallbackEncrypt(Long id) {
        String idStr = "dev_" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(idStr.getBytes(StandardCharsets.UTF_8));
    }

    private Long fallbackDecrypt(String encryptedId) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encryptedId);
            String idStr = new String(decoded, StandardCharsets.UTF_8);
            if (idStr.startsWith("dev_")) {
                return Long.parseLong(idStr.substring(4));
            }
            return Long.parseLong(idStr);
        } catch (Exception e) {
            return null;
        }
    }

    public List<String> batchEncryptIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        return ids.stream().map(this::encryptId).collect(Collectors.toList());
    }

    public boolean isValidEncryptedId(String encryptedId) {
        try {
            Long id = decryptId(encryptedId);
            return id != null && id > 0;
        } catch (Exception e) {
            return false;
        }
    }
}