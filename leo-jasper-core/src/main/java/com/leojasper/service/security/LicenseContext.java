package com.leojasper.service.security;

import java.nio.file.Path;

/**
 * Per-request data resolved by the {@link com.leojasper.service.security.LicenseFilter
 * license filter} and read by downstream services. Pass-by-value POJO; not
 * persistent.
 */
public class LicenseContext {

    private static final ThreadLocal<LicenseContext> CURRENT = new ThreadLocal<>();

    public final String        keyId;
    public final String        companyName;
    public final LicenseScope  scope;
    public final Path          companyDir;
    public final String        clientIp;

    public LicenseContext(String keyId, String companyName, LicenseScope scope,
                          Path companyDir, String clientIp) {
        this.keyId = keyId;
        this.companyName = companyName;
        this.scope = scope;
        this.companyDir = companyDir;
        this.clientIp = clientIp;
    }

    public static void set(LicenseContext ctx) { CURRENT.set(ctx); }
    public static LicenseContext get()         { return CURRENT.get(); }
    public static void clear()                 { CURRENT.remove(); }

    public Path imagesDir()    { return companyDir.resolve("images"); }
    public Path templatesDir() { return companyDir.resolve("templates"); }
    public Path dataDir()      { return companyDir.resolve("data"); }
    public Path otherDir()     { return companyDir.resolve("other"); }
    public Path metaDir()      { return companyDir.resolve(".meta"); }
}
