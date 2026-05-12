package com.leojasper.service.synthesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leojasper.service.ReportGenerationException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads field declarations out of a JRXML and emits matching sample data.
 *
 * <p>Two outputs:
 * <ul>
 *   <li>{@link #generateRecords(byte[], int)} — JSON array of N records, each
 *       with one entry per {@code <field/>}, types respected.</li>
 *   <li>{@link #generateParameters(byte[])} — JSON object with one entry per
 *       {@code <parameter/>} so the user can paste it into the test page's
 *       parameters textarea.</li>
 * </ul>
 *
 * <p>For richer values (matching what the user actually saw on screen) the
 * caller can pass the {@link LayoutModel} into the alternative overloads —
 * those use {@code sampleValue} from the model when present.
 */
public class SampleDataGenerator {

    private final ObjectMapper jackson = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public String generateRecords(byte[] jrxmlBytes, int rowCount) {
        return generateRecords(jrxmlBytes, rowCount, null);
    }

    /** Emits a JSON array of {@code rowCount} sample records. */
    public String generateRecords(byte[] jrxmlBytes, int rowCount, LayoutModel hints) {
        List<FieldSpec> fields = readFields(jrxmlBytes);
        if (fields.isEmpty()) return "[]";

        Map<String, String> hintMap = new LinkedHashMap<>();
        if (hints != null && hints.fields != null) {
            for (LayoutModel.Field f : hints.fields) {
                if (f.name != null && f.sampleValue != null) hintMap.put(f.name, f.sampleValue);
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        int n = Math.max(1, Math.min(rowCount, 1000));
        for (int i = 0; i < n; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (FieldSpec f : fields) {
                row.put(f.name, sampleValue(f, i, hintMap.get(f.name)));
            }
            rows.add(row);
        }
        try { return jackson.writeValueAsString(rows); }
        catch (Exception e) { throw new ReportGenerationException("Failed to encode JSON", e); }
    }

    public String generateParameters(byte[] jrxmlBytes) {
        return generateParameters(jrxmlBytes, null);
    }

    public String generateParameters(byte[] jrxmlBytes, LayoutModel hints) {
        List<FieldSpec> params = readParameters(jrxmlBytes);
        if (params.isEmpty()) return "{}";

        Map<String, String> hintMap = new LinkedHashMap<>();
        if (hints != null && hints.parameters != null) {
            for (LayoutModel.Parameter p : hints.parameters) {
                if (p.name != null && p.sampleValue != null) hintMap.put(p.name, p.sampleValue);
            }
        }

        Map<String, Object> obj = new LinkedHashMap<>();
        for (FieldSpec p : params) {
            obj.put(p.name, sampleValue(p, 0, hintMap.get(p.name)));
        }
        try { return jackson.writeValueAsString(obj); }
        catch (Exception e) { throw new ReportGenerationException("Failed to encode JSON", e); }
    }

    /** Emits a CSV string suitable for the {@code csv} input format. */
    public String generateCsv(byte[] jrxmlBytes, int rowCount, LayoutModel hints) {
        List<FieldSpec> fields = readFields(jrxmlBytes);
        if (fields.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(csvEscape(fields.get(i).name));
        }
        sb.append('\n');
        Map<String, String> hintMap = new LinkedHashMap<>();
        if (hints != null && hints.fields != null) {
            for (LayoutModel.Field f : hints.fields) {
                if (f.name != null && f.sampleValue != null) hintMap.put(f.name, f.sampleValue);
            }
        }
        int n = Math.max(1, Math.min(rowCount, 1000));
        for (int row = 0; row < n; row++) {
            for (int c = 0; c < fields.size(); c++) {
                if (c > 0) sb.append(',');
                Object v = sampleValue(fields.get(c), row, hintMap.get(fields.get(c).name));
                sb.append(csvEscape(String.valueOf(v)));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ----- value generation -----

    private Object sampleValue(FieldSpec f, int rowIndex, String hint) {
        if (hint != null && !hint.isBlank()) {
            // hint is a string from the LLM — try to coerce to declared type, otherwise keep as String.
            try {
                switch (f.type) {
                    case "java.lang.Integer": return Integer.parseInt(hint.replaceAll("[^-0-9]", ""));
                    case "java.lang.Long":    return Long.parseLong(hint.replaceAll("[^-0-9]", ""));
                    case "java.lang.Double":  return Double.parseDouble(hint.replaceAll("[^-0-9.]", ""));
                    case "java.lang.Float":   return Float.parseFloat(hint.replaceAll("[^-0-9.]", ""));
                    case "java.lang.Boolean": return Boolean.parseBoolean(hint);
                    case "java.math.BigDecimal":
                        return new java.math.BigDecimal(hint.replaceAll("[^-0-9.]", ""));
                    default: return hint;
                }
            } catch (Exception e) { return hint; }
        }
        switch (f.type) {
            case "java.lang.Integer": return rowIndex + 1;
            case "java.lang.Long":    return (long)(rowIndex + 1) * 1000L;
            case "java.lang.Double":  return Math.round((10 + rowIndex * 12.5) * 100.0) / 100.0;
            case "java.lang.Float":   return (float)(rowIndex + 1.5);
            case "java.lang.Boolean": return rowIndex % 2 == 0;
            case "java.math.BigDecimal":
                return new java.math.BigDecimal((rowIndex + 1) * 100).setScale(2, java.math.RoundingMode.HALF_UP);
            case "java.util.Date":
                return new java.util.Date().toString();
            case "java.time.LocalDate":
                return java.time.LocalDate.now().minusDays(rowIndex).toString();
            case "java.time.LocalDateTime":
                return java.time.LocalDateTime.now().minusDays(rowIndex).toString();
            case "java.time.Instant":
                return java.time.Instant.now().minusSeconds(rowIndex * 3600L).toString();
            default:
                return prettyName(f.name) + " " + (rowIndex + 1);
        }
    }

    private String prettyName(String name) {
        if (name == null) return "Sample";
        return name.replace('_', ' ');
    }

    private String csvEscape(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // ----- JRXML parsing -----

    private List<FieldSpec> readFields(byte[] jrxmlBytes) {
        return readByTag(jrxmlBytes, "field");
    }

    private List<FieldSpec> readParameters(byte[] jrxmlBytes) {
        return readByTag(jrxmlBytes, "parameter");
    }

    private List<FieldSpec> readByTag(byte[] jrxmlBytes, String tag) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            try (InputStream in = new ByteArrayInputStream(jrxmlBytes)) {
                org.w3c.dom.Document doc = db.parse(in);
                // Plain (non-NS) lookup — works regardless of whether the parser
                // was configured namespace-aware or the JRXML declares a default xmlns.
                org.w3c.dom.NodeList nodes = doc.getElementsByTagName(tag);
                List<FieldSpec> out = new ArrayList<>();
                for (int i = 0; i < nodes.getLength(); i++) {
                    org.w3c.dom.Element el = (org.w3c.dom.Element) nodes.item(i);
                    String name = el.getAttribute("name");
                    String type = el.getAttribute("class");
                    if (name == null || name.isEmpty()) continue;
                    if (type == null || type.isEmpty()) type = "java.lang.String";
                    // Skip JR built-in parameters like REPORT_LOCALE, PAGE_NUMBER, etc.
                    if ("parameter".equals(tag) && (name.startsWith("REPORT_") || name.startsWith("JASPER_") || name.equals("IS_IGNORE_PAGINATION"))) {
                        continue;
                    }
                    out.add(new FieldSpec(name, type));
                }
                return out;
            }
        } catch (Exception e) {
            throw new ReportGenerationException("Failed to parse JRXML: " + e.getMessage(), e);
        }
    }

    private static class FieldSpec {
        final String name;
        final String type;
        FieldSpec(String name, String type) { this.name = name; this.type = type; }
    }
}
