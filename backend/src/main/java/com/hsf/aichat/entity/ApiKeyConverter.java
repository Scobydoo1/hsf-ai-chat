package com.hsf.aichat.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts API keys before JPA writes them to the database (AES-GCM) and decrypts them when reading.
 * The secret comes from the APP_SECRET_KEY environment variable.
 * If you change APP_SECRET_KEY, saved keys can no longer be read: enter them again in Settings.
 */
@Converter
public class ApiKeyConverter implements AttributeConverter<String, String> {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyConverter.class);
    private static final String PREFIX = "v1:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private static SecretKeySpec key() {
        try {
            String secret = System.getenv().getOrDefault("APP_SECRET_KEY", "local-dev-secret-change-me");
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(hash, "AES");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String convertToDatabaseColumn(String plain) {
        if (plain == null || plain.isBlank()) {
            return null;
        }
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt API key", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        if (!stored.startsWith(PREFIX)) {
            return stored; // plain text typed directly into the database
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, all, 0, 12));
            return new String(cipher.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Could not decrypt a saved API key (APP_SECRET_KEY changed?). Enter the key again in Settings.");
            return null;
        }
    }
}
