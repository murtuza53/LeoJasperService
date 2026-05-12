package com.leojasper.service.security;

public enum LicenseScope {
    /** list / get / render only — no upload, no delete. */
    READ,
    /** READ + render-time multipart uploads of inputs (no asset writes). */
    RENDER,
    /** Everything: assets CRUD, synthesis, render. */
    FULL;

    public boolean canRead()   { return true; }
    public boolean canRender() { return this == RENDER || this == FULL; }
    public boolean canWrite()  { return this == FULL; }

    public static LicenseScope from(String s) {
        if (s == null || s.isBlank()) return FULL;
        switch (s.trim().toUpperCase()) {
            case "READ":   return READ;
            case "RENDER": return RENDER;
            case "FULL":   return FULL;
            default:       return FULL;
        }
    }
}
