package com.leojasper.service.synthesis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * Provider-neutral intermediate representation of a document layout.
 *
 * <p>The pipeline is:
 * <pre>
 *   PDF/image → VisionAnalyzer → LayoutModel → JrxmlGenerator → JRXML
 * </pre>
 *
 * <p>Mutable POJOs (Jackson-friendly). Instances are produced by an analyzer,
 * potentially edited by the user via the API, and consumed by the JRXML
 * generator. Field names match the JSON schema sent to / received from
 * the LLM, so don't rename without updating the prompt.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class LayoutModel {

    public String title;
    public PageInfo page = new PageInfo();
    public List<Parameter> parameters = new ArrayList<>();
    public List<Field>     fields     = new ArrayList<>();
    public List<Region>    regions    = new ArrayList<>();
    public List<TableColumn> tableColumns = new ArrayList<>();
    public TableStyle      tableStyle = null;
    public List<AssetRef>  assets     = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PageInfo {
        public int width = 595;          // A4 portrait at 72 dpi
        public int height = 842;
        public int leftMargin = 30;
        public int rightMargin = 30;
        public int topMargin = 30;
        public int bottomMargin = 30;

        public int columnWidth() {
            return Math.max(1, width - leftMargin - rightMargin);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Parameter {
        public String name;
        public String type = "java.lang.String";
        public String description;
        public String sampleValue;

        public Parameter() {}
        public Parameter(String name, String type, String description, String sampleValue) {
            this.name = name; this.type = type;
            this.description = description; this.sampleValue = sampleValue;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Field {
        public String name;
        public String type = "java.lang.String";
        public String description;
        public String sampleValue;

        public Field() {}
        public Field(String name, String type, String description, String sampleValue) {
            this.name = name; this.type = type;
            this.description = description; this.sampleValue = sampleValue;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TableColumn {
        public String name;          // matches a field.name
        public String header;        // text shown in the column header
        public Integer x;            // 0-based, in column-width coords
        public Integer width;
        public String alignment = "Left";
        public String pattern;       // optional, e.g. "$#,##0.00"
        public String headerAlignment;   // optional override; defaults to alignment
    }

    /**
     * Table-wide visual style. Each field is optional; the generator falls
     * back to "ledger" defaults (thin grey grid + thicker black header rules)
     * whenever {@code tableColumns} is non-empty so even a sparse model output
     * gets a recognisably tabular look.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TableStyle {
        public Float  cellBorderWidth;        // default 0.5
        public String cellBorderColor;        // default "#BBBBBB"
        public String cellBorderSides;        // default "bottom,right"
        public Float  headerBorderWidth;      // default 1.0
        public String headerBorderColor;      // default "#000000"
        public String headerBorderSides;      // default "top,bottom"
        public String headerBackground;       // optional, e.g. "#1F3A5F"
        public String headerForeground;       // optional, e.g. "#FFFFFF"
        public String evenRowBackground;      // optional zebra striping
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Region {
        public String band;          // TITLE | PAGE_HEADER | COLUMN_HEADER | DETAIL | PAGE_FOOTER | SUMMARY
        public Integer x;
        public Integer y;
        public Integer width;
        public Integer height;
        public Element element;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Element {
        public String type;          // static_text | text_field | image | line | rectangle
        public String text;          // static_text payload
        public String expression;    // text_field payload, e.g. "$P{INVOICE_NUMBER}" or "$F{description}"
        public String pattern;       // optional, e.g. "$#,##0.00", "yyyy-MM-dd"
        public String assetRef;      // image: name in the asset store
        public Style style = new Style();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Style {
        public String fontName;
        public Integer fontSize;
        public Boolean bold;
        public Boolean italic;
        public String alignment;     // Left | Center | Right | Justified
        public String color;         // hex like "#1A1A1A"
        public String background;    // hex like "#E0E8F0"

        // Border on the element. Renders to a JR <box> with one or more <pen/>s.
        public Float  borderWidth;       // line thickness in pt; null/0 = no border
        public String borderColor;       // hex; defaults to #000000
        public String borderStyle;       // Solid | Dashed | Dotted | Double
        /** Comma-separated subset of "top,right,bottom,left". null/empty = all 4 sides. */
        public String borderSides;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AssetRef {
        public String name;          // logo.png
        public String contentType;   // image/png
        public Integer width;
        public Integer height;
        public String description;
    }
}
