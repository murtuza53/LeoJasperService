package com.leojasper.service.template;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Resolves a logical template ID (e.g. {@code "invoice"} or {@code "invoice/v3"})
 * to a JRXML byte stream. Implementations may load from filesystem, classpath,
 * S3, a database, etc.
 */
public interface TemplateRegistry {

    /** Open the JRXML for the given id. The caller closes the stream. */
    InputStream openJrxml(String templateId) throws IOException;

    /** Discoverable template ids (best effort — some backends may return empty). */
    List<String> list() throws IOException;

    /** True if the registry knows of this template. */
    boolean exists(String templateId);

    /**
     * Stable cache key — when the underlying template changes, this value must
     * change so the compiled report cache invalidates. Default: the templateId.
     */
    default String cacheKey(String templateId) throws IOException {
        return templateId;
    }
}
