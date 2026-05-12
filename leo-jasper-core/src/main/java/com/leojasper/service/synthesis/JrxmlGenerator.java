package com.leojasper.service.synthesis;

import com.leojasper.service.ReportGenerationException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic {@link LayoutModel} → JRXML translator.
 *
 * <p>Strategy:
 * <ul>
 *   <li>Page geometry comes straight from {@code page}.</li>
 *   <li>Each {@link LayoutModel.Parameter} becomes a {@code <parameter/>}.</li>
 *   <li>Each {@link LayoutModel.Field} becomes a {@code <field/>}.</li>
 *   <li>{@code regions} are grouped by {@code band}; each group becomes a band
 *       whose height is the max of (region.y + region.height) within it.</li>
 *   <li>If {@code tableColumns} is non-empty, COLUMN_HEADER + DETAIL bands are
 *       synthesized from it (overriding any user-supplied content for those
 *       bands), since that's where most LLM output ends up imprecise.</li>
 *   <li>Band order is enforced to match the JRXML schema:
 *       title, pageHeader, columnHeader, detail, columnFooter, pageFooter, summary.</li>
 * </ul>
 */
public class JrxmlGenerator {

    private static final List<String> BAND_ORDER = List.of(
            "TITLE", "PAGE_HEADER", "COLUMN_HEADER", "DETAIL",
            "COLUMN_FOOTER", "PAGE_FOOTER", "SUMMARY");

    public String generate(String reportName, LayoutModel m) {
        if (m == null) throw new ReportGenerationException("LayoutModel is null");
        LayoutModel.PageInfo page = m.page == null ? new LayoutModel.PageInfo() : m.page;
        int columnWidth = page.columnWidth();

        StringBuilder sb = new StringBuilder(8 * 1024);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<jasperReport xmlns=\"http://jasperreports.sourceforge.net/jasperreports\"\n")
          .append("              name=\"").append(esc(reportName)).append("\"\n")
          .append("              pageWidth=\"").append(page.width).append("\"")
          .append(" pageHeight=\"").append(page.height).append("\"")
          .append(" columnWidth=\"").append(columnWidth).append("\"\n")
          .append("              leftMargin=\"").append(page.leftMargin).append("\"")
          .append(" rightMargin=\"").append(page.rightMargin).append("\"")
          .append(" topMargin=\"").append(page.topMargin).append("\"")
          .append(" bottomMargin=\"").append(page.bottomMargin).append("\">\n\n");

        // Parameters
        for (LayoutModel.Parameter p : safeList(m.parameters)) {
            if (isBlank(p.name)) continue;
            sb.append("    <parameter name=\"").append(esc(p.name)).append("\" class=\"")
              .append(esc(orDefault(p.type, "java.lang.String"))).append("\"/>\n");
        }
        if (!safeList(m.parameters).isEmpty()) sb.append('\n');

        // Fields
        for (LayoutModel.Field f : safeList(m.fields)) {
            if (isBlank(f.name)) continue;
            sb.append("    <field name=\"").append(esc(f.name)).append("\" class=\"")
              .append(esc(orDefault(f.type, "java.lang.String"))).append("\"/>\n");
        }
        if (!safeList(m.fields).isEmpty()) sb.append('\n');

        // Group user-supplied regions by band
        Map<String, List<LayoutModel.Region>> byBand = new LinkedHashMap<>();
        for (String band : BAND_ORDER) byBand.put(band, new ArrayList<>());
        for (LayoutModel.Region r : safeList(m.regions)) {
            String b = (r.band == null ? "" : r.band).toUpperCase(Locale.ROOT);
            if (!byBand.containsKey(b)) continue;
            byBand.get(b).add(r);
        }

        // tableColumns synthesizes COLUMN_HEADER + DETAIL when non-empty,
        // overriding whatever the LLM put there.
        boolean hasTable = !safeList(m.tableColumns).isEmpty();
        if (hasTable) {
            byBand.get("COLUMN_HEADER").clear();
            byBand.get("COLUMN_HEADER").addAll(buildColumnHeader(m.tableColumns, m.tableStyle));
            byBand.get("DETAIL").clear();
            byBand.get("DETAIL").addAll(buildDetailRow(m.tableColumns, m.tableStyle));
        }

        for (String band : BAND_ORDER) {
            List<LayoutModel.Region> regions = byBand.get(band);
            if (regions.isEmpty()) continue;
            int height = computeBandHeight(regions, columnWidth);
            sb.append("    <").append(toJrxmlBandTag(band)).append(">\n");
            sb.append("        <band height=\"").append(height).append("\">\n");
            for (LayoutModel.Region r : regions) {
                appendElement(sb, r, columnWidth);
            }
            sb.append("        </band>\n");
            sb.append("    </").append(toJrxmlBandTag(band)).append(">\n\n");
        }

        sb.append("</jasperReport>\n");
        return sb.toString();
    }

    // ----- column-derived bands -----

    private List<LayoutModel.Region> buildColumnHeader(
            List<LayoutModel.TableColumn> cols, LayoutModel.TableStyle ts) {
        List<LayoutModel.Region> out = new ArrayList<>();
        for (LayoutModel.TableColumn c : cols) {
            LayoutModel.Region r = new LayoutModel.Region();
            r.band = "COLUMN_HEADER";
            r.x = nz(c.x); r.y = 0;
            r.width = nz(c.width); r.height = 22;
            r.element = new LayoutModel.Element();
            r.element.type = "static_text";
            r.element.text = orDefault(c.header, c.name);
            r.element.style.bold = true;
            r.element.style.alignment = orDefault(c.headerAlignment, orDefault(c.alignment, "Left"));
            // Header background + foreground from TableStyle.
            if (ts != null) {
                if (!isBlank(ts.headerBackground)) r.element.style.background = ts.headerBackground;
                if (!isBlank(ts.headerForeground)) r.element.style.color      = ts.headerForeground;
            }
            // Header border: thicker black top+bottom rules by default.
            r.element.style.borderWidth = (ts != null && ts.headerBorderWidth != null) ? ts.headerBorderWidth : 1.0f;
            r.element.style.borderColor = (ts != null && !isBlank(ts.headerBorderColor)) ? ts.headerBorderColor : "#000000";
            r.element.style.borderSides = (ts != null && !isBlank(ts.headerBorderSides)) ? ts.headerBorderSides : "top,bottom";
            out.add(r);
        }
        return out;
    }

    private List<LayoutModel.Region> buildDetailRow(
            List<LayoutModel.TableColumn> cols, LayoutModel.TableStyle ts) {
        List<LayoutModel.Region> out = new ArrayList<>();
        for (LayoutModel.TableColumn c : cols) {
            LayoutModel.Region r = new LayoutModel.Region();
            r.band = "DETAIL";
            r.x = nz(c.x); r.y = 0;
            r.width = nz(c.width); r.height = 18;
            r.element = new LayoutModel.Element();
            r.element.type = "text_field";
            r.element.expression = "$F{" + c.name + "}";
            r.element.pattern = c.pattern;
            r.element.style.alignment = orDefault(c.alignment, "Left");
            // Cell grid lines: thin grey by default. "bottom,right" gives a ledger look.
            r.element.style.borderWidth = (ts != null && ts.cellBorderWidth != null) ? ts.cellBorderWidth : 0.5f;
            r.element.style.borderColor = (ts != null && !isBlank(ts.cellBorderColor)) ? ts.cellBorderColor : "#BBBBBB";
            r.element.style.borderSides = (ts != null && !isBlank(ts.cellBorderSides)) ? ts.cellBorderSides : "bottom,right";
            out.add(r);
        }
        return out;
    }

    private int computeBandHeight(List<LayoutModel.Region> regions, int columnWidth) {
        int max = 20;
        for (LayoutModel.Region r : regions) {
            int bottom = nz(r.y) + nz(r.height);
            if (bottom > max) max = bottom;
        }
        return max + 2;     // small breathing room avoids JR's "element extends past band" warning
    }

    // ----- element output -----

    private void appendElement(StringBuilder sb, LayoutModel.Region r, int columnWidth) {
        LayoutModel.Element e = r.element;
        if (e == null || e.type == null) return;

        int x = clamp(nz(r.x), 0, columnWidth);
        int width = clamp(nz(r.width), 1, columnWidth - x);
        int y = nz(r.y);
        int height = Math.max(1, nz(r.height));

        switch (e.type) {
            case "static_text": appendStaticText(sb, e, x, y, width, height); return;
            case "text_field":  appendTextField(sb,  e, x, y, width, height); return;
            case "image":       appendImage(sb,      e, x, y, width, height); return;
            case "line":        appendLine(sb,       e, x, y, width, height); return;
            case "rectangle":   appendRectangle(sb,  e, x, y, width, height); return;
            default: /* ignore unknown */ return;
        }
    }

    private void appendStaticText(StringBuilder sb, LayoutModel.Element e,
                                  int x, int y, int width, int height) {
        sb.append("            <staticText>\n");
        sb.append(reportElement(x, y, width, height, e.style));
        sb.append(box(e.style));
        sb.append(textElement(e.style));
        sb.append("                <text><![CDATA[").append(safeCdata(orDefault(e.text, "")))
          .append("]]></text>\n");
        sb.append("            </staticText>\n");
    }

    private void appendTextField(StringBuilder sb, LayoutModel.Element e,
                                 int x, int y, int width, int height) {
        sb.append("            <textField");
        if (!isBlank(e.pattern)) sb.append(" pattern=\"").append(esc(e.pattern)).append("\"");
        sb.append(">\n");
        sb.append(reportElement(x, y, width, height, e.style));
        sb.append(box(e.style));
        sb.append(textElement(e.style));
        sb.append("                <textFieldExpression><![CDATA[")
          .append(safeCdata(orDefault(e.expression, "\"\"")))
          .append("]]></textFieldExpression>\n");
        sb.append("            </textField>\n");
    }

    private void appendImage(StringBuilder sb, LayoutModel.Element e,
                             int x, int y, int width, int height) {
        sb.append("            <image>\n");
        sb.append(reportElement(x, y, width, height, e.style));
        sb.append(box(e.style));
        String ref = orDefault(e.assetRef, "");
        sb.append("                <imageExpression><![CDATA[\"")
          .append(safeCdata(ref)).append("\"]]></imageExpression>\n");
        sb.append("            </image>\n");
    }

    private void appendLine(StringBuilder sb, LayoutModel.Element e,
                            int x, int y, int width, int height) {
        sb.append("            <line>\n");
        sb.append(reportElement(x, y, width, Math.max(1, height), e.style));
        sb.append("            </line>\n");
    }

    private void appendRectangle(StringBuilder sb, LayoutModel.Element e,
                                 int x, int y, int width, int height) {
        sb.append("            <rectangle>\n");
        sb.append(reportElement(x, y, width, height, e.style));
        // JR's <rectangle> uses <graphicElement> for stroke (NOT <box>); emit one
        // when borderWidth/borderColor is set so the stroke shows up.
        if (e.style != null && e.style.borderWidth != null && e.style.borderWidth > 0f) {
            String color = orDefault(e.style.borderColor, "#000000");
            sb.append("                <graphicElement>\n")
              .append("                    <pen lineWidth=\"").append(formatWidth(e.style.borderWidth))
              .append("\" lineColor=\"").append(esc(color)).append("\"/>\n")
              .append("                </graphicElement>\n");
        }
        sb.append("            </rectangle>\n");
    }

    /**
     * Emit a JR {@code <box>} with one or more {@code <pen/>} children when
     * the style requests a border. Side-specific {@code <topPen/>} etc. are
     * used so we can render only "bottom,right" grid lines (ledger look) or
     * any subset.
     */
    private String box(LayoutModel.Style style) {
        if (style == null || style.borderWidth == null || style.borderWidth <= 0f) return "";

        String color = orDefault(style.borderColor, "#000000");
        String lineStyle = orDefault(style.borderStyle, "Solid");
        String sides = (style.borderSides == null || style.borderSides.isBlank())
                ? "top,right,bottom,left" : style.borderSides.toLowerCase(Locale.ROOT);

        StringBuilder sb = new StringBuilder("                <box>\n");
        // JR's <box> XSD requires this exact order: top, left, bottom, right.
        for (String side : new String[]{"top", "left", "bottom", "right"}) {
            if (!sides.contains(side)) continue;
            sb.append("                    <").append(side).append("Pen lineWidth=\"")
              .append(formatWidth(style.borderWidth))
              .append("\" lineStyle=\"").append(esc(lineStyle))
              .append("\" lineColor=\"").append(esc(color)).append("\"/>\n");
        }
        sb.append("                </box>\n");
        return sb.toString();
    }

    private static String formatWidth(float w) {
        // JR happily accepts decimal widths; trim trailing zeros for readability.
        if (w == (long) w) return String.valueOf((long) w);
        return String.valueOf(w);
    }

    private String reportElement(int x, int y, int width, int height, LayoutModel.Style style) {
        StringBuilder sb = new StringBuilder("                <reportElement x=\"")
            .append(x).append("\" y=\"").append(y).append("\" width=\"").append(width)
            .append("\" height=\"").append(height).append("\"");
        if (style != null && !isBlank(style.background)) {
            sb.append(" backcolor=\"").append(esc(style.background)).append("\" mode=\"Opaque\"");
        }
        if (style != null && !isBlank(style.color)) {
            sb.append(" forecolor=\"").append(esc(style.color)).append("\"");
        }
        sb.append("/>\n");
        return sb.toString();
    }

    private String textElement(LayoutModel.Style style) {
        if (style == null) return "";
        boolean any = !isBlank(style.alignment) || style.fontSize != null
                || Boolean.TRUE.equals(style.bold) || Boolean.TRUE.equals(style.italic)
                || !isBlank(style.fontName);
        if (!any) return "";

        StringBuilder sb = new StringBuilder("                <textElement");
        if (!isBlank(style.alignment)) {
            sb.append(" textAlignment=\"").append(esc(style.alignment)).append("\"");
        }
        sb.append(">\n");
        if (style.fontSize != null
                || Boolean.TRUE.equals(style.bold)
                || Boolean.TRUE.equals(style.italic)
                || !isBlank(style.fontName)) {
            sb.append("                    <font");
            if (!isBlank(style.fontName)) sb.append(" fontName=\"").append(esc(style.fontName)).append("\"");
            if (style.fontSize != null)   sb.append(" size=\"").append(style.fontSize).append("\"");
            if (Boolean.TRUE.equals(style.bold))   sb.append(" isBold=\"true\"");
            if (Boolean.TRUE.equals(style.italic)) sb.append(" isItalic=\"true\"");
            sb.append("/>\n");
        }
        sb.append("                </textElement>\n");
        return sb.toString();
    }

    // ----- helpers -----

    private String toJrxmlBandTag(String band) {
        switch (band) {
            case "TITLE":         return "title";
            case "PAGE_HEADER":   return "pageHeader";
            case "COLUMN_HEADER": return "columnHeader";
            case "DETAIL":        return "detail";
            case "COLUMN_FOOTER": return "columnFooter";
            case "PAGE_FOOTER":   return "pageFooter";
            case "SUMMARY":       return "summary";
            default: throw new ReportGenerationException("Unknown band: " + band);
        }
    }

    private static <T> List<T> safeList(List<T> list) { return list == null ? List.of() : list; }
    private static int nz(Integer i) { return i == null ? 0 : i; }
    private static int clamp(int v, int lo, int hi) {
        if (hi < lo) hi = lo;
        return Math.max(lo, Math.min(hi, v));
    }
    private static boolean isBlank(String s) { return s == null || s.isEmpty(); }
    private static String orDefault(String s, String d) { return isBlank(s) ? d : s; }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
    private static String safeCdata(String s) {
        if (s == null) return "";
        // Forbid the ]]> sequence inside CDATA to prevent breakage.
        return s.replace("]]>", "]]&gt;");
    }
}
