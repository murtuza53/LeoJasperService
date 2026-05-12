package com.leojasper.service.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leojasper.service.ReportGenerationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/**
 * Persists the admin's choice of password and the configured assets-root
 * folder. The whole file is AES-GCM encrypted; the encryption key is derived
 * from the admin password (PBKDF2). No separate password hash — verifying
 * the password = a successful decryption.
 *
 * <p>First-run behaviour: when the file is absent, the default credentials
 * {@code admin / admin52} log in and {@link AdminFile#mustChangePassword} is
 * true. The file is created the moment the admin changes the password.
 */
public class CredentialsStore {

    public static final String DEFAULT_USER     = "admin";
    public static final String DEFAULT_PASSWORD = "admin52";

    private static final Logger log = LoggerFactory.getLogger(CredentialsStore.class);
    private volatile Path file;
    private final ObjectMapper jackson = new ObjectMapper();

    public CredentialsStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    public Path file() { return file; }

    /** Re-point the store at a new file location. The old file is not touched. */
    public void setFile(Path newFile) {
        this.file = newFile.toAbsolutePath().normalize();
    }

    public boolean exists() { return Files.exists(file); }

    /**
     * Verifies the password by decrypting the file. Returns the decoded
     * {@link AdminFile}. Throws {@link CryptoBox.BadPasswordException} on
     * wrong password.
     */
    public AdminFile load(char[] password) {
        if (!exists()) {
            // First-run: only the default password works; nothing on disk yet.
            if (DEFAULT_PASSWORD.equals(new String(password))) {
                AdminFile fresh = new AdminFile();
                fresh.mustChangePassword = true;
                fresh.assetsRoot = null;
                fresh.createdAt = Instant.now().toString();
                return fresh;
            }
            throw new CryptoBox.BadPasswordException("No credentials file yet — login with admin/admin52");
        }
        try {
            byte[] blob = Files.readAllBytes(file);
            byte[] plain = CryptoBox.decrypt(blob, password);
            return jackson.readValue(plain, AdminFile.class);
        } catch (CryptoBox.BadPasswordException e) {
            throw e;
        } catch (IOException e) {
            throw new ReportGenerationException(
                    "Failed to read credentials file: " + e.getMessage(), e);
        }
    }

    /** Writes a fresh credentials file (creating parent dirs as needed). */
    public void save(AdminFile content, char[] password) {
        try {
            Files.createDirectories(file.getParent());
            byte[] plain = jackson.writeValueAsBytes(content);
            byte[] blob  = CryptoBox.encrypt(plain, password);
            // Atomic write: write to a sibling temp file, then move.
            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            Files.write(tmp, blob);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                       StandardCopyOption.ATOMIC_MOVE);
            log.info("credentials saved to {}", file);
        } catch (IOException e) {
            throw new ReportGenerationException(
                    "Failed to write credentials file: " + e.getMessage(), e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AdminFile {
        public boolean mustChangePassword;
        /** Where company asset folders are created. May be null on first run. */
        public String  assetsRoot;
        public String  createdAt;
        public String  updatedAt;
    }
}
