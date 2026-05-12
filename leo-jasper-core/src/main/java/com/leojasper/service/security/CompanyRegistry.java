package com.leojasper.service.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leojasper.service.ReportGenerationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Encrypted persistent store of {@link Company} records. Encryption uses the
 * same admin password as {@link CredentialsStore}, so the admin's password
 * unlocks everything in one step.
 *
 * <p>The cleartext registry is also kept in memory after the first decrypt
 * for fast lookup on the request path.
 */
public class CompanyRegistry {

    private static final Logger log = LoggerFactory.getLogger(CompanyRegistry.class);

    private volatile Path file;
    private final ObjectMapper jackson = new ObjectMapper();
    private final Map<String, Company> byKeyId = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private volatile char[] cachedPassword;     // held only after admin login; cleared on logout
    private volatile boolean loaded;

    public CompanyRegistry(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    public Path file() { return file; }
    public boolean isLoaded() { return loaded; }

    /** Re-point the registry at a new file location. Does not touch the old file. */
    public void setFile(Path newFile) {
        lock.writeLock().lock();
        try {
            this.file = newFile.toAbsolutePath().normalize();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Public version of {@code persist()} so {@link AuthService} can write the
     *  current state to a relocated file during an assets-root migration. */
    public void save() {
        lock.writeLock().lock();
        try {
            requireUnlocked();
            persist();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Load and decrypt with the admin password. Caches the password for writes. */
    public void unlock(char[] password) {
        lock.writeLock().lock();
        try {
            byKeyId.clear();
            if (!Files.exists(file)) {
                this.cachedPassword = password.clone();
                this.loaded = true;
                log.info("companies.enc absent — starting with an empty registry");
                return;
            }
            byte[] blob = Files.readAllBytes(file);
            byte[] plain = CryptoBox.decrypt(blob, password);
            List<Company> list = jackson.readValue(plain,
                    jackson.getTypeFactory().constructCollectionType(List.class, Company.class));
            for (Company c : list) byKeyId.put(c.keyId, c);
            this.cachedPassword = password.clone();
            this.loaded = true;
            log.info("companies.enc loaded — {} company(ies)", byKeyId.size());
        } catch (IOException e) {
            throw new ReportGenerationException(
                    "Failed to read companies file: " + e.getMessage(), e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void lock() {
        lock.writeLock().lock();
        try {
            byKeyId.clear();
            if (cachedPassword != null) {
                java.util.Arrays.fill(cachedPassword, '\0');
                cachedPassword = null;
            }
            loaded = false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void requireUnlocked() {
        if (!loaded) {
            throw new ReportGenerationException("Company registry is locked — admin login required");
        }
    }

    private void persist() {
        if (cachedPassword == null) {
            throw new ReportGenerationException("Cannot persist — no cached password");
        }
        try {
            Files.createDirectories(file.getParent());
            byte[] plain = jackson.writeValueAsBytes(new ArrayList<>(byKeyId.values()));
            byte[] blob = CryptoBox.encrypt(plain, cachedPassword);
            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            Files.write(tmp, blob);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                       StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new ReportGenerationException(
                    "Failed to write companies file: " + e.getMessage(), e);
        }
    }

    /** Re-encrypt the file under a new admin password. Called after change-password. */
    public void rekey(char[] newPassword) {
        lock.writeLock().lock();
        try {
            requireUnlocked();
            this.cachedPassword = newPassword.clone();
            persist();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<Company> list() {
        lock.readLock().lock();
        try {
            requireUnlocked();
            return new ArrayList<>(byKeyId.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    public Optional<Company> findByKeyId(String keyId) {
        lock.readLock().lock();
        try {
            requireUnlocked();
            return Optional.ofNullable(byKeyId.get(keyId));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Verify the supplied secret against the stored hash. */
    public boolean verifySecret(Company company, String secret) {
        if (company == null || secret == null) return false;
        String h = PasswordHash.sha256(secret);
        return CryptoBox.constantTimeEquals(
                h.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                company.secretHash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Generate a new keyId+secret pair, save the company, return both for one-time display. */
    public CompanyCreate create(String name, String address, LicenseScope scope, boolean hmacRequired) {
        lock.writeLock().lock();
        try {
            requireUnlocked();
            String keyId  = CryptoBox.randomToken(16);   // 22 chars base64url
            String secret = CryptoBox.randomToken(32);   // 43 chars base64url
            Company c = new Company();
            c.keyId        = keyId;
            c.secretHash   = PasswordHash.sha256(secret);
            c.name         = name;
            c.address      = address;
            c.scope        = scope.name();
            c.hmacRequired = hmacRequired;
            c.folderName   = Company.slug(name) + "-" + keyId.substring(0, 6);
            c.createdAt    = Company.now();
            byKeyId.put(keyId, c);
            persist();
            log.info("registered company '{}' keyId={} folder={}", name, keyId, c.folderName);
            return new CompanyCreate(c, secret);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Issue a fresh secret for a company; old secret invalidated. KeyId stays. */
    public String rotateSecret(String keyId) {
        lock.writeLock().lock();
        try {
            requireUnlocked();
            Company c = byKeyId.get(keyId);
            if (c == null) throw new ReportGenerationException("Unknown keyId: " + keyId);
            String secret = CryptoBox.randomToken(32);
            c.secretHash = PasswordHash.sha256(secret);
            c.rotatedAt  = Company.now();
            persist();
            return secret;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void setDisabled(String keyId, boolean disabled) {
        lock.writeLock().lock();
        try {
            requireUnlocked();
            Company c = byKeyId.get(keyId);
            if (c == null) throw new ReportGenerationException("Unknown keyId: " + keyId);
            c.disabled = disabled;
            c.disabledAt = disabled ? Company.now() : null;
            persist();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void update(String keyId, String name, String address, LicenseScope scope, boolean hmacRequired) {
        lock.writeLock().lock();
        try {
            requireUnlocked();
            Company c = byKeyId.get(keyId);
            if (c == null) throw new ReportGenerationException("Unknown keyId: " + keyId);
            if (name != null && !name.isBlank())    c.name = name;
            if (address != null)                    c.address = address;
            if (scope != null)                      c.scope = scope.name();
            c.hmacRequired = hmacRequired;
            persist();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file", file.toString());
        m.put("loaded", loaded);
        m.put("count", byKeyId.size());
        return m;
    }

    public static class CompanyCreate {
        public final Company company;
        public final String  secret;          // raw — for one-time display only
        public CompanyCreate(Company c, String s) { this.company = c; this.secret = s; }
    }
}
