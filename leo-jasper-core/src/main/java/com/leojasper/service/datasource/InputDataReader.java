package com.leojasper.service.datasource;

import com.leojasper.service.ReportGenerationException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Coerces a flexible {@code Object} payload into an {@link InputStream} the
 * text-based data sources (JSON / XML / CSV) can consume. Strings are treated
 * as a file path if they resolve to a regular file, otherwise as inline content.
 */
public final class InputDataReader {

    private InputDataReader() {}

    public static InputStream toInputStream(Object inputData) throws IOException {
        if (inputData == null) {
            throw new ReportGenerationException("Input data is null");
        }
        if (inputData instanceof InputStream) {
            return (InputStream) inputData;
        }
        if (inputData instanceof byte[]) {
            return new ByteArrayInputStream((byte[]) inputData);
        }
        if (inputData instanceof String) {
            String s = (String) inputData;
            try {
                Path candidate = Paths.get(s);
                if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                    return Files.newInputStream(candidate);
                }
            } catch (InvalidPathException ignored) {
                // not a path — treat as inline content
            }
            return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
        }
        if (inputData instanceof File) {
            return new FileInputStream((File) inputData);
        }
        if (inputData instanceof Path) {
            return Files.newInputStream((Path) inputData);
        }
        throw new ReportGenerationException(
                "Cannot convert " + inputData.getClass().getName() + " to InputStream");
    }
}
