package com.leojasper.service.security;

import com.leojasper.service.ReportGenerationException;

import java.security.MessageDigest;
import java.util.Base64;

/**
 * One-way hashing for two distinct use-cases:
 * <ul>
 *   <li>{@link #sha256(String)} — for storing API key secrets. Inputs are
 *       256-bit random tokens, so SHA-256 is sufficient (no PBKDF2 stretching
 *       needed when the input is already cryptographically random).</li>
 *   <li>{@link #sha256Prefix(String, int)} — short, non-secret identifier
 *       safe to put in audit logs.</li>
 * </ul>
 *
 * <p>For the admin password we don't store a hash at all — verification is
 * via {@link CryptoBox#decrypt} attempt success.
 */
public final class PasswordHash {
    private PasswordHash() {}

    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(h);
        } catch (Exception e) {
            throw new ReportGenerationException("hash failed: " + e.getMessage(), e);
        }
    }

    public static String sha256Prefix(String input, int chars) {
        String full = sha256(input);
        return full.substring(0, Math.min(chars, full.length()));
    }
}
