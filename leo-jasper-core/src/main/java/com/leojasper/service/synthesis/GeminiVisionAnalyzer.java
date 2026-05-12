package com.leojasper.service.synthesis;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.leojasper.service.ReportGenerationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends a rendered page to the Google Gemini API and parses the JSON response
 * into a {@link LayoutModel}. Uses {@code response_mime_type: "application/json"}
 * so the model is forced to return parseable JSON.
 *
 * <p>API key is read from the {@code GEMINI_API_KEY} environment variable
 * (or whatever you wire up in Spring config).
 *
 * <p>Default model: {@code gemini-2.5-flash}. Override via constructor.
 */
public class GeminiVisionAnalyzer implements VisionAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(GeminiVisionAnalyzer.class);

    private static final String DEFAULT_MODEL = "gemini-2.5-flash";
    private static final String API_HOST      = "https://generativelanguage.googleapis.com";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper jackson = new ObjectMapper()
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
            // Vision LLMs invent extra fields. Tolerate them rather than failing.
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final String apiKey;
    private final String model;

    public GeminiVisionAnalyzer(String apiKey) { this(apiKey, DEFAULT_MODEL); }

    public GeminiVisionAnalyzer(String apiKey, String model) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ReportGenerationException("Gemini API key is required");
        }
        this.apiKey = apiKey;
        this.model = (model == null || model.isBlank()) ? DEFAULT_MODEL : model;
    }

    @Override public String name() { return "gemini:" + model; }

    @Override
    public LayoutModel analyze(byte[] pngBytes, String userHint) {
        try {
            String prompt = buildPrompt(userHint);
            String body   = buildRequestBody(prompt, pngBytes);

            HttpRequest req = HttpRequest.newBuilder(URI.create(
                    API_HOST + "/v1beta/models/" + model + ":generateContent?key=" + apiKey))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(90))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

            log.info("calling gemini ({}), payload {} bytes", model, body.length());
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (res.statusCode() / 100 != 2) {
                throw new ReportGenerationException(
                        "Gemini API call failed: HTTP " + res.statusCode() + " — " + summarize(res.body()));
            }

            String layoutJson = extractText(res.body());
            log.debug("gemini returned {} chars of JSON", layoutJson.length());
            return jackson.readValue(layoutJson, LayoutModel.class);

        } catch (ReportGenerationException e) {
            throw e;
        } catch (Exception e) {
            throw new ReportGenerationException("Gemini analysis failed: " + e.getMessage(), e);
        }
    }

    // ---- request body ----------------------------------------------------

    private String buildRequestBody(String prompt, byte[] pngBytes) throws Exception {
        Map<String, Object> textPart  = Map.of("text", prompt);
        Map<String, Object> inlineData = Map.of(
                "mime_type", "image/png",
                "data",      Base64.getEncoder().encodeToString(pngBytes));
        Map<String, Object> imgPart   = Map.of("inline_data", inlineData);

        Map<String, Object> contents = new LinkedHashMap<>();
        contents.put("parts", List.of(textPart, imgPart));

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("response_mime_type", "application/json");
        generationConfig.put("temperature", 0.2);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("contents", List.of(contents));
        root.put("generationConfig", generationConfig);
        return jackson.writeValueAsString(root);
    }

    private String extractText(String jsonResponse) throws Exception {
        JsonNode root = jackson.readTree(jsonResponse);
        JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            throw new ReportGenerationException(
                    "Gemini returned no content parts: " + summarize(jsonResponse));
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode p : parts) {
            String t = p.path("text").asText("");
            if (!t.isEmpty()) sb.append(t);
        }
        String s = sb.toString().trim();
        // Strip ```json fences if Gemini ignored response_mime_type
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            if (nl > 0) s = s.substring(nl + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
            s = s.trim();
        }
        return s;
    }

    private String summarize(String body) {
        if (body == null) return "";
        return body.length() > 500 ? body.substring(0, 500) + "…" : body;
    }

    // ---- prompt ----------------------------------------------------------

    private String buildPrompt(String userHint) {
        String hint = (userHint == null || userHint.isBlank())
                ? ""
                : "\n\nUser hint: " + userHint;

        return """
        You are analyzing a document layout (invoice, report, form, etc.) so that a
        JasperReports JRXML template can be generated from it. Examine the image and
        return a single JSON object describing the layout.

        Distinguish two roles for data:
          - PARAMETER  = a value that appears ONCE per document (invoice number, date,
                         customer name, totals, header text, address). These become
                         $P{NAME} placeholders in the JRXML.
          - FIELD      = a value that appears in a REPEATING context (table rows,
                         list items). These become $F{name} columns in a band.
        Static labels like "Invoice #:" stay as static text, NOT as parameters.

        Return JSON exactly in this shape:

        {
          "title": "<short descriptive name>",
          "page": {"width":595,"height":842,"leftMargin":30,"rightMargin":30,
                   "topMargin":30,"bottomMargin":30},
          "parameters": [
            {"name":"INVOICE_NUMBER","type":"java.lang.String",
             "description":"Top-right invoice number","sampleValue":"INV-2026-001"}
          ],
          "fields": [
            {"name":"description","type":"java.lang.String",
             "description":"Line item description","sampleValue":"Web design"}
          ],
          "tableColumns": [
            {"name":"description","header":"Description","x":0,"width":290,
             "alignment":"Left"},
            {"name":"unit_price","header":"Unit Price","x":350,"width":90,
             "alignment":"Right","pattern":"$#,##0.00"}
          ],
          "tableStyle": {
            "cellBorderWidth": 0.5, "cellBorderColor": "#BBBBBB",
            "cellBorderSides": "bottom,right",
            "headerBorderWidth": 1.0, "headerBorderColor": "#000000",
            "headerBorderSides": "top,bottom",
            "headerBackground": "#1F3A5F", "headerForeground": "#FFFFFF"
          },
          "regions": [
            {"band":"TITLE","x":0,"y":0,"width":535,"height":40,
             "element":{"type":"static_text","text":"INVOICE",
                        "style":{"fontSize":28,"bold":true}}},
            {"band":"PAGE_HEADER","x":0,"y":0,"width":260,"height":60,
             "element":{"type":"text_field","expression":"$P{CUSTOMER_NAME}",
                        "style":{"fontSize":13,"bold":true,
                                 "background":"#F4F6F9",
                                 "borderWidth":0.5, "borderColor":"#999999",
                                 "borderSides":"top,right,bottom,left"}}}
          ],
          "assets": []
        }

        Rules:
        - PARAMETER names UPPER_SNAKE_CASE, FIELD names snake_case.
        - Type must be one of: java.lang.String, java.lang.Integer, java.lang.Double,
          java.lang.Boolean.
        - Page coordinates use 72 dpi units. A4 portrait = 595x842, US Letter = 612x792.
        - Region x/y are relative to the band. The band's available width =
          page.width - leftMargin - rightMargin. Don't exceed that.
        - For tables: fill BOTH "fields" (one per column) AND "tableColumns" (with x +
          width laid out across the band). Add a COLUMN_HEADER region for the header
          row only if you also want column labels rendered as static text.
        - Pattern attribute: use "$#,##0.00" for currency, "yyyy-MM-dd" for dates,
          "#,##0" for plain integers with thousand separators.
        - tableColumns x values must be cumulative (no overlap), and the last column
          must end at or before page.columnWidth.
        - SCHEMA STRICTNESS — element ALWAYS has exactly these keys:
            type, text, expression, pattern, assetRef, style.
          Do NOT put alignment, fontSize, bold, italic, color, background, fontName,
          borderWidth, borderColor, borderSides directly on element. Those keys live
          INSIDE element.style. Example:
            "element": { "type":"text_field", "expression":"$F{amount}",
                         "pattern":"$#,##0.00",
                         "style": { "alignment":"Right", "fontSize":12, "bold":true,
                                    "borderWidth":0.5, "borderColor":"#999999",
                                    "borderSides":"bottom" } }
        - Style keys are: fontName, fontSize, bold, italic, alignment, color, background,
          borderWidth, borderColor, borderStyle, borderSides. Anything else is dropped.

        - VISUAL FIDELITY (this is what makes the output look like the original):
          • Every visible BORDER you see in the source must be reflected. Set
            style.borderWidth (0.5 for thin/grey, 1.0 for crisp black, 2.0 for thick).
            Set style.borderColor to the hex you observe.
            Set style.borderSides to a comma-separated subset of "top,right,bottom,left"
            (e.g. "bottom" for an underline, "top,right,bottom,left" for a full box).
          • Every visible BACKGROUND COLOR (header strips, totals box, customer block,
            footer band) must be set via style.background.
          • For tabular data, ALWAYS populate tableStyle so the output has grid lines
            and a styled header row, even if you only saw subtle rules. Defaults are
            ledger-look (grey "bottom,right" cell borders + black "top,bottom" header
            rules) — override only when the source clearly differs.
          • Header / customer / "bill to" blocks usually have a thin border + light fill.
            Render them as a single static_text or text_field region with both
            style.background and style.borderWidth + borderSides="top,right,bottom,left".
          • Footer signature blocks, totals boxes, and "amount in words" regions
            typically have borders. Capture them.

        - Return ONLY the JSON object. No prose, no markdown fences.
        """ + hint;
    }
}
