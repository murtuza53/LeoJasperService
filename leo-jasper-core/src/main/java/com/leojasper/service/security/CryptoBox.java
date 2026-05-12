package com.leojasper.service.security;

import com.leojasper.service.ReportGenerationException;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;

/**
 * AES-GCM file/blob encryption with a key derived from the admin password
 * via PBKDF2-HMAC-SHA256 (250,000 iterations, 256-bit key). Output layout:
 * <pre>
 *   bytes[0..16)   salt (16)
 *   bytes[16..28)  iv (12)
 *   bytes[28..)    ciphertext + GCM tag (16-byte tag, included by the cipher)
 * </pre>
 *
 * <p>Verifying the password is just an attempt to decrypt — if the GCM tag
 * doesn't match, the password is wrong and we throw. No separate password
 * hash is stored alongside the file.
 */
public final class CryptoBox {

    private static final int SALT_LEN     = 16;
    private static final int IV_LEN       = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int PBKDF2_ITERS = 250_000;
    private static final int KEY_BITS     = 256;

    private static final SecureRandom RNG = new SecureRandom();

    private CryptoBox() {}

    /** Encrypts {@code plaintext} with a fresh salt+IV and returns the layout above. */
    public static byte[] encrypt(byte[] plaintext, char[] password) {
        byte[] salt = new byte[SALT_LEN];
        byte[] iv   = new byte[IV_LEN];
        RNG.nextBytes(salt);
        RNG.nextBytes(iv);

        byte[] key = deriveKey(password, salt);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext);
            ByteBuffer out = ByteBuffer.allocate(SALT_LEN + IV_LEN + ct.length);
            out.put(salt).put(iv).put(ct);
            return out.array();
        } catch (Exception e) {
            throw new ReportGenerationException("encrypt failed: " + e.getMessage(), e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * Decrypts a blob produced by {@link #encrypt}. Throws
     * {@link BadPasswordException} when the GCM tag fails.
     */
    public static byte[] decrypt(byte[] blob, char[] password) {
        if (blob == null || blob.length < SALT_LEN + IV_LEN + 16) {
            throw new ReportGenerationException("encrypted blob is too short");
        }
        byte[] salt = Arrays.copyOfRange(blob, 0, SALT_LEN);
        byte[] iv   = Arrays.copyOfRange(blob, SALT_LEN, SALT_LEN + IV_LEN);
        byte[] ct   = Arrays.copyOfRange(blob, SALT_LEN + IV_LEN, blob.length);

        byte[] key = deriveKey(password, salt);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(ct);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new BadPasswordException("Wrong password or corrupted file");
        } catch (Exception e) {
            throw new ReportGenerationException("decrypt failed: " + e.getMessage(), e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** PBKDF2-HMAC-SHA256, 250 k iterations, 256-bit output. */
    public static byte[] deriveKey(char[] password, byte[] salt) {
        try {
            KeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERS, KEY_BITS);
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new ReportGenerationException("key derivation failed: " + e.getMessage(), e);
        }
    }

    /** Cryptographically random 32-byte token, base64url-encoded (43 chars). */
    public static String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        RNG.nextBytes(buf);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /** Constant-time compare to defeat timing attacks on secret/hash compare. */
    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= (a[i] ^ b[i]);
        return diff == 0;
    }

    /** Thrown when AES-GCM tag verification fails — i.e. wrong password. */
    public static class BadPasswordException extends RuntimeException {
        public BadPasswordException(String msg) { super(msg); }
    }
}
