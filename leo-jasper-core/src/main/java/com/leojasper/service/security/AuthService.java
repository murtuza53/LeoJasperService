package com.leojasper.service.security;

import com.leojasper.service.ReportGenerationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.stream.Stream;

/**
 * Coordinates the admin lifecycle: login (decrypt files, mint session),
 * change-password (re-encrypt files), set-assets-root, logout (lock registry).
 */
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final CredentialsStore credentialsStore;
    private final CompanyRegistry  registry;
    private final AdminSessionStore sessions;
    private final String defaultAssetsRoot;

    /** Live state — mutated only while holding the admin login. */
    private volatile CredentialsStore.AdminFile adminFile;
    private volatile char[] cachedPassword;       // cleared on logout

    public AuthService(CredentialsStore credentialsStore,
                       CompanyRegistry registry,
                       AdminSessionStore sessions,
                       String defaultAssetsRoot) {
        this.credentialsStore  = credentialsStore;
        this.registry          = registry;
        this.sessions          = sessions;
        this.defaultAssetsRoot = defaultAssetsRoot;
        autoMigrateLegacyVault();
    }

    /**
     * If a previous version persisted the vault at {@code ./.leojasper/} (a
     * project-local directory which can be wiped by a redeploy), move both
     * encrypted files into the new location under the assets root. Idempotent:
     * runs once and is a no-op if the legacy dir is already gone or the new
     * location is non-empty.
     */
    private void autoMigrateLegacyVault() {
        Path legacyVault = Paths.get(".leojasper").toAbsolutePath().normalize();
        Path legacyCred  = legacyVault.resolve("credentials.enc");
        Path legacyComp  = legacyVault.resolve("companies.enc");
        if (!Files.exists(legacyCred) && !Files.exists(legacyComp)) return;

        Path newCred = credentialsStore.file();
        Path newComp = registry.file();
        if (legacyCred.equals(newCred) && legacyComp.equals(newComp)) {
            // The configured vault IS the legacy location — nothing to do.
            return;
        }
        if (Files.exists(newCred) || Files.exists(newComp)) {
            log.warn("legacy vault still at {} but the new location {} already has vault files — " +
                     "skipping auto-migration; admin must reconcile manually", legacyVault, newCred.getParent());
            return;
        }
        try {
            Files.createDirectories(newCred.getParent());
            if (Files.exists(legacyCred)) {
                Files.move(legacyCred, newCred, StandardCopyOption.REPLACE_EXISTING);
                log.info("migrated {} → {}", legacyCred, newCred);
            }
            if (Files.exists(legacyComp)) {
                Files.move(legacyComp, newComp, StandardCopyOption.REPLACE_EXISTING);
                log.info("migrated {} → {}", legacyComp, newComp);
            }
        } catch (IOException e) {
            log.warn("auto-migration of legacy vault failed ({}): {}",
                     e.getClass().getSimpleName(), e.getMessage());
            return;
        }
        // Best-effort tidy of the now-empty legacy dir. Some sync agents
        // (OneDrive, Dropbox) hold a brief handle so deletion can fail; the
        // moves themselves are already complete, so this is informational only.
        try (Stream<Path> s = Files.list(legacyVault)) {
            if (s.findAny().isEmpty()) {
                Files.delete(legacyVault);
                log.info("removed empty legacy vault {}", legacyVault);
            }
        } catch (IOException e) {
            log.debug("legacy vault dir {} could not be removed: {}", legacyVault, e.getMessage());
        }
    }

    public CredentialsStore credentialsStore() { return credentialsStore; }
    public CompanyRegistry  registry()         { return registry; }
    public AdminSessionStore sessions()        { return sessions; }

    public synchronized LoginResult login(String username, char[] password) {
        return login(username, password, null);
    }

    /**
     * Log in against the vault at the supplied path. Used to adopt a vault
     * carried over from a previous install — the path's
     * {@code .leojasper/credentials.enc} is unlocked with {@code password}
     * and becomes the active vault for the session.
     *
     * <p>On a wrong password the original store paths are restored before
     * surfacing the error, so a failed adoption attempt leaves the service
     * in exactly the state it was before the call.
     *
     * <p>If the unlocked file's recorded {@code assetsRoot} differs from the
     * path the caller supplied (the user moved the folder since the vault
     * was last written), the file is re-saved with the supplied path. Trust
     * the user-provided location over a stale pointer.
     */
    public synchronized LoginResult login(String username, char[] password, String vaultPath) {
        if (!CredentialsStore.DEFAULT_USER.equals(username)) {
            throw new CryptoBox.BadPasswordException("Invalid credentials");
        }

        Path originalCredFile = credentialsStore.file();
        Path originalCompFile = registry.file();
        boolean redirected = false;

        if (vaultPath != null && !vaultPath.isBlank()) {
            Path adopted = Paths.get(vaultPath).toAbsolutePath().normalize();
            Path vault   = vaultDirFor(adopted);
            Path newCred = vault.resolve("credentials.enc");
            Path newComp = vault.resolve("companies.enc");
            if (!Files.exists(newCred)) {
                throw new ReportGenerationException(
                        "No vault found at " + newCred +
                        " — check the path or leave it blank to use the configured default.");
            }
            credentialsStore.setFile(newCred);
            registry.setFile(newComp);
            redirected = true;
            log.info("login attempting vault adoption from {}", vault);
        }

        CredentialsStore.AdminFile file;
        try {
            file = credentialsStore.load(password);
            registry.unlock(password);
        } catch (RuntimeException e) {
            // Revert path redirection so a failed adoption doesn't strand the service.
            if (redirected) {
                credentialsStore.setFile(originalCredFile);
                registry.setFile(originalCompFile);
            }
            throw e;
        }

        this.adminFile      = file;
        this.cachedPassword = password.clone();

        // Path-drift correction: if the user supplied a path that differs from
        // the one baked into credentials.enc (folder was moved between installs),
        // trust the user and re-persist with the new path.
        if (redirected) {
            Path adopted = Paths.get(vaultPath).toAbsolutePath().normalize();
            String adoptedString = adopted.toString();
            if (!adoptedString.equals(file.assetsRoot)) {
                log.info("path drift detected — credentials.enc said assetsRoot={}, " +
                         "vault is actually at {}; rewriting", file.assetsRoot, adoptedString);
                file.assetsRoot = adoptedString;
                file.updatedAt  = Company.now();
                credentialsStore.save(file, password);
            }
        }

        String sid = sessions.createSession(username);
        log.info("admin logged in (vaultFile={}, mustChangePassword={}, assetsRoot={})",
                credentialsStore.file(), file.mustChangePassword, file.assetsRoot);
        return new LoginResult(sid, file.mustChangePassword,
                file.assetsRoot, file.assetsRoot == null,
                /* registered */ !registry.list().isEmpty());
    }

    public synchronized void changePassword(char[] currentPassword, char[] newPassword) {
        if (cachedPassword == null) {
            throw new ReportGenerationException("Not logged in");
        }
        if (!CryptoBox.constantTimeEquals(toBytes(currentPassword), toBytes(cachedPassword))) {
            throw new CryptoBox.BadPasswordException("Current password is wrong");
        }
        if (newPassword == null || newPassword.length < 6) {
            throw new ReportGenerationException("New password must be at least 6 characters");
        }
        if (Arrays.equals(currentPassword, newPassword)) {
            throw new ReportGenerationException("New password must differ from current");
        }
        // Update credentials file
        adminFile.mustChangePassword = false;
        adminFile.updatedAt = Company.now();
        credentialsStore.save(adminFile, newPassword);
        // Re-encrypt company registry under new key
        registry.rekey(newPassword);

        // Clear old password from memory
        Arrays.fill(cachedPassword, '\0');
        this.cachedPassword = newPassword.clone();
        log.info("admin password changed");
    }

    public synchronized Path setAssetsRoot(String absolutePath) {
        if (cachedPassword == null) {
            throw new ReportGenerationException("Not logged in");
        }
        if (absolutePath == null || absolutePath.isBlank()) {
            throw new ReportGenerationException("Path is required");
        }
        Path newRoot = Paths.get(absolutePath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(newRoot);
        } catch (Exception e) {
            throw new ReportGenerationException(
                    "Cannot create directory: " + e.getMessage(), e);
        }
        if (!Files.isWritable(newRoot)) {
            throw new ReportGenerationException("Path is not writable: " + newRoot);
        }

        Path newVault    = vaultDirFor(newRoot);
        Path newCredFile = newVault.resolve("credentials.enc");
        Path newCompFile = newVault.resolve("companies.enc");
        Path oldCredFile = credentialsStore.file();
        Path oldCompFile = registry.file();
        boolean relocating = !newCredFile.equals(oldCredFile)
                          || !newCompFile.equals(oldCompFile);

        // Refuse to overwrite an existing vault — that would silently destroy
        // a previous install's admin password, companies, and their license
        // secrets. Admin must adopt the existing vault by logging back in
        // with the path entered as the vault location.
        if (relocating && Files.exists(newCredFile)) {
            throw new ReportGenerationException(
                    "A vault already exists at " + newVault + ". " +
                    "To use the existing vault, sign out and sign back in with " +
                    "this path entered as 'Vault path' on the login form. " +
                    "Pick a different empty folder if you want to start a new vault here.");
        }

        try {
            Files.createDirectories(newVault);
        } catch (IOException e) {
            throw new ReportGenerationException(
                    "Cannot create vault dir " + newVault + ": " + e.getMessage(), e);
        }

        // Update in-memory adminFile.
        adminFile.assetsRoot = newRoot.toString();
        adminFile.updatedAt  = Company.now();

        // Write the credentials file at the (possibly new) location.
        credentialsStore.setFile(newCredFile);
        credentialsStore.save(adminFile, cachedPassword);

        // Persist the (possibly empty) registry at the new location.
        registry.setFile(newCompFile);
        registry.save();

        if (relocating) {
            deleteIfPresent(oldCredFile);
            deleteIfPresent(oldCompFile);
            tryRemoveEmptyDir(oldCredFile.getParent());
            log.info("vault relocated to {}", newVault);
        }
        log.info("assetsRoot set to {}", newRoot);
        return newRoot;
    }

    /** Vault dir for a given assets root: {@code <root>/.leojasper/}. */
    public static Path vaultDirFor(Path assetsRoot) {
        return assetsRoot.resolve(".leojasper").toAbsolutePath().normalize();
    }

    private void deleteIfPresent(Path p) {
        if (p == null) return;
        try { Files.deleteIfExists(p); }
        catch (IOException e) { log.warn("could not delete {}: {}", p, e.getMessage()); }
    }

    private void tryRemoveEmptyDir(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.list(dir)) {
            if (s.findAny().isEmpty()) Files.delete(dir);
        } catch (IOException ignored) { }
    }

    public Path effectiveAssetsRoot() {
        if (adminFile != null && adminFile.assetsRoot != null && !adminFile.assetsRoot.isBlank()) {
            return Paths.get(adminFile.assetsRoot).toAbsolutePath().normalize();
        }
        return Paths.get(defaultAssetsRoot).toAbsolutePath().normalize();
    }

    public boolean isLoggedIn() { return cachedPassword != null; }

    public CredentialsStore.AdminFile adminFile() { return adminFile; }

    public synchronized void logout(String sessionId) {
        sessions.invalidate(sessionId);
        if (sessions.validate(sessionId).isEmpty() && cachedPassword != null) {
            // No active sessions — release sensitive state.
            // (For multi-tab admin we'd keep state until the last session.
            // Here we lock on every logout to err on the safe side.)
            registry.lock();
            Arrays.fill(cachedPassword, '\0');
            cachedPassword = null;
            adminFile = null;
            log.info("admin logged out — registry locked");
        }
    }

    private static byte[] toBytes(char[] chars) {
        byte[] out = new byte[chars.length];
        for (int i = 0; i < chars.length; i++) out[i] = (byte) chars[i];
        return out;
    }

    public static class LoginResult {
        public final String  sessionId;
        public final boolean mustChangePassword;
        public final String  assetsRoot;
        public final boolean mustSetAssetsRoot;
        public final boolean hasCompanies;

        public LoginResult(String sessionId, boolean mustChangePassword,
                           String assetsRoot, boolean mustSetAssetsRoot,
                           boolean hasCompanies) {
            this.sessionId = sessionId;
            this.mustChangePassword = mustChangePassword;
            this.assetsRoot = assetsRoot;
            this.mustSetAssetsRoot = mustSetAssetsRoot;
            this.hasCompanies = hasCompanies;
        }
    }
}
