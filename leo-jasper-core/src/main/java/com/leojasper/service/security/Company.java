package com.leojasper.service.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Locale;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Company {

    public String  keyId;          // public; safe to log
    public String  secretHash;     // SHA-256 of the issued secret (never the secret itself)
    public String  name;
    public String  address;
    public String  scope = "FULL"; // READ | RENDER | FULL
    public boolean hmacRequired;   // tier 2 opt-in
    public boolean disabled;
    public String  folderName;     // {slug}-{keyId-prefix6}
    public String  createdAt;
    public String  rotatedAt;
    public String  disabledAt;

    public Company() {}

    public LicenseScope scopeEnum() { return LicenseScope.from(scope); }

    /** {@code "acme-corp"} from "Acme Corp, Inc.". */
    public static String slug(String s) {
        if (s == null) return "company";
        String slug = s.toLowerCase(Locale.ROOT)
                       .replaceAll("[^a-z0-9]+", "-")
                       .replaceAll("^-+|-+$", "");
        if (slug.length() > 30) slug = slug.substring(0, 30);
        return slug.isEmpty() ? "company" : slug;
    }

    /** Touch and return current ISO-8601 timestamp. */
    public static String now() { return Instant.now().toString(); }
}
