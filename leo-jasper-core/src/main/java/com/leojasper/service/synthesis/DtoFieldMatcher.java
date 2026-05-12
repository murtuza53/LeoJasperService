package com.leojasper.service.synthesis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a Java class declaration (a DTO) and renames {@link LayoutModel}
 * fields/parameters to match the DTO's actual property names.
 *
 * <p>Matching is fuzzy: case-insensitive, whitespace-insensitive, and
 * tolerates camelCase ↔ snake_case conversion. If the DTO contains
 * {@code BigDecimal price}, an LLM-detected field {@code unit_price} of type
 * String becomes {@code price} of type {@code java.math.BigDecimal}.
 */
public class DtoFieldMatcher {

    /** Pattern to find: <modifier>* <type> <name>; — handles records too. */
    private static final Pattern FIELD_PATTERN = Pattern.compile(
            "(?:private|public|protected|final|static|\\s)+" +    // modifiers
            "([A-Za-z_][\\w<>?,\\s\\[\\]]*?)\\s+" +                // type
            "([A-Za-z_]\\w*)\\s*(?:[=;)])",                       // name
            Pattern.MULTILINE);

    /** Record component pattern: TYPE name */
    private static final Pattern RECORD_COMP = Pattern.compile(
            "([A-Za-z_][\\w<>?,\\s\\[\\]]*?)\\s+([A-Za-z_]\\w*)\\s*[,)]",
            Pattern.MULTILINE);

    /** Java getter pattern: TYPE getName() */
    private static final Pattern GETTER = Pattern.compile(
            "(?:public|protected|\\s)+" +
            "([A-Za-z_][\\w<>?,\\s\\[\\]]*?)\\s+" +
            "(?:get|is)([A-Z]\\w*)\\s*\\(\\s*\\)",
            Pattern.MULTILINE);

    /**
     * Apply DTO field names + types to a layout model. Returns a report of
     * what was matched, for the UI to show.
     */
    public DtoMatchResult apply(LayoutModel model, String javaSource) {
        Map<String, String> dto = parseDto(javaSource);          // canonicalKey → "Type:name"
        DtoMatchResult result = new DtoMatchResult();
        if (dto.isEmpty()) {
            result.note = "No fields parsed from DTO source — leaving model unchanged.";
            return result;
        }

        for (LayoutModel.Field f : model.fields) {
            String key = canonicalKey(f.name);
            String hit = dto.get(key);
            if (hit != null) {
                String[] tn = hit.split(":", 2);
                String oldName = f.name;
                String oldType = f.type;
                f.type = mapType(tn[0]);
                f.name = tn[1];
                result.fieldRenames.add(oldName + " → " + f.name +
                        " (" + oldType + " → " + f.type + ")");
                rewriteFieldExpressions(model, oldName, f.name);
                renameTableColumn(model, oldName, f.name);
            } else {
                result.unmatchedFields.add(f.name);
            }
        }

        // Parameters typically don't live on a DTO line-item, but try anyway.
        for (LayoutModel.Parameter p : model.parameters) {
            String key = canonicalKey(p.name);
            String hit = dto.get(key);
            if (hit != null) {
                String[] tn = hit.split(":", 2);
                String oldName = p.name;
                String oldType = p.type;
                p.type = mapType(tn[0]);
                p.name = tn[1].toUpperCase(Locale.ROOT);          // params stay UPPER_SNAKE
                result.paramRenames.add(oldName + " → " + p.name +
                        " (" + oldType + " → " + p.type + ")");
                rewriteParamExpressions(model, oldName, p.name);
            }
        }

        return result;
    }

    // ----- helpers -----

    private Map<String, String> parseDto(String source) {
        if (source == null || source.isBlank()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();

        // record components: record Foo(String a, int b) { ... }
        Matcher recHeader = Pattern.compile("record\\s+\\w+\\s*\\(([^)]*)\\)", Pattern.DOTALL).matcher(source);
        if (recHeader.find()) {
            String compList = recHeader.group(1) + ",";
            Matcher mc = RECORD_COMP.matcher(compList);
            while (mc.find()) {
                addField(out, mc.group(1), mc.group(2));
            }
        }

        // explicit fields
        Matcher fields = FIELD_PATTERN.matcher(source);
        while (fields.find()) {
            String type = fields.group(1).trim();
            String name = fields.group(2).trim();
            // skip method declarations: e.g. "public String getX() {...}"
            if (Character.isUpperCase(name.charAt(0))) continue;
            if (isJavaKeyword(name)) continue;
            addField(out, type, name);
        }

        // getters
        Matcher getters = GETTER.matcher(source);
        while (getters.find()) {
            String type = getters.group(1).trim();
            String capName = getters.group(2);
            String name = Character.toLowerCase(capName.charAt(0)) + capName.substring(1);
            addField(out, type, name);
        }

        return out;
    }

    private void addField(Map<String, String> out, String type, String name) {
        String key = canonicalKey(name);
        out.putIfAbsent(key, type.replaceAll("\\s+", "") + ":" + name);
    }

    private String canonicalKey(String s) {
        if (s == null) return "";
        return s.replaceAll("[_\\-\\s]", "").toLowerCase(Locale.ROOT);
    }

    private boolean isJavaKeyword(String s) {
        switch (s) {
            case "class": case "interface": case "enum": case "record":
            case "extends": case "implements": case "package": case "import":
            case "return": case "if": case "else": case "for": case "while":
            case "do": case "switch": case "case": case "break": case "continue":
            case "true": case "false": case "null": case "new": case "this":
            case "super": case "throw": case "throws": case "try": case "catch":
            case "finally": case "void":
                return true;
            default: return false;
        }
    }

    /** Map common Java types to JasperReports field types. */
    private String mapType(String type) {
        String t = type.replaceAll("\\s+", "");
        switch (t) {
            case "int": case "Integer": case "java.lang.Integer":  return "java.lang.Integer";
            case "long": case "Long": case "java.lang.Long":       return "java.lang.Long";
            case "double": case "Double": case "java.lang.Double": return "java.lang.Double";
            case "float": case "Float": case "java.lang.Float":    return "java.lang.Float";
            case "boolean": case "Boolean": case "java.lang.Boolean": return "java.lang.Boolean";
            case "BigDecimal": case "java.math.BigDecimal":        return "java.math.BigDecimal";
            case "BigInteger": case "java.math.BigInteger":        return "java.math.BigInteger";
            case "Date": case "java.util.Date":                    return "java.util.Date";
            case "LocalDate": case "java.time.LocalDate":          return "java.time.LocalDate";
            case "LocalDateTime": case "java.time.LocalDateTime":  return "java.time.LocalDateTime";
            case "Instant": case "java.time.Instant":              return "java.time.Instant";
            default:                                               return "java.lang.String";
        }
    }

    private void rewriteFieldExpressions(LayoutModel model, String oldName, String newName) {
        if (oldName.equals(newName)) return;
        for (LayoutModel.Region r : model.regions) {
            if (r.element == null || r.element.expression == null) continue;
            r.element.expression = r.element.expression
                    .replace("$F{" + oldName + "}", "$F{" + newName + "}");
        }
    }

    private void rewriteParamExpressions(LayoutModel model, String oldName, String newName) {
        if (oldName.equals(newName)) return;
        for (LayoutModel.Region r : model.regions) {
            if (r.element == null || r.element.expression == null) continue;
            r.element.expression = r.element.expression
                    .replace("$P{" + oldName + "}", "$P{" + newName + "}");
        }
    }

    private void renameTableColumn(LayoutModel model, String oldName, String newName) {
        for (LayoutModel.TableColumn c : model.tableColumns) {
            if (oldName.equals(c.name)) c.name = newName;
        }
    }

    public static class DtoMatchResult {
        public List<String> fieldRenames = new ArrayList<>();
        public List<String> paramRenames = new ArrayList<>();
        public List<String> unmatchedFields = new ArrayList<>();
        public String note;
    }
}
