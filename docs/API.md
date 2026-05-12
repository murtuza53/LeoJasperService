# LeoJasperService — API reference for client integrators

The service compiles a JasperReports `.jrxml` template, fills it from your
data, and returns a rendered report in the format you ask for. This document
is everything a React (or any browser) client needs to talk to it.

---

## 1. Quickstart — render a PDF in 10 lines of React

```jsx
async function renderPdf(rows) {
  const fd = new FormData();
  fd.append("templateId",   "sample");                 // template stored on the server
  fd.append("dataInline",   JSON.stringify(rows));     // inline JSON string
  fd.append("inputFormat",  "json");
  fd.append("outputFormat", "pdf");

  const res  = await fetch("http://localhost:8080/api/reports/render",
                           { method: "POST", body: fd });
  if (!res.ok) throw new Error(await res.text());
  const blob = await res.blob();
  window.open(URL.createObjectURL(blob), "_blank");    // opens in new tab
}
```

That's the whole integration surface for the simple case. The rest of this
doc is reference material: every endpoint, every parameter, error format,
and patterns for async / large / streamed responses.

---

## 2. Base URL & connection

| Environment | Default URL |
|-------------|-------------|
| Local dev   | `http://localhost:8080` |
| Docker      | the container's mapped port (default `-p 8080:8080`) |
| Production  | whatever your DevOps team publishes — likely behind a reverse proxy with TLS, e.g. `https://reports.yourco.com` |

Set this once in your app (env var, config file, etc.) and reference it everywhere.

```ts
// src/config.ts
export const REPORTS_BASE_URL =
  import.meta.env.VITE_REPORTS_BASE_URL ?? "http://localhost:8080";
```

### CORS

The dev server has wide-open CORS:

```
Access-Control-Allow-Origin:      <echoes your Origin>
Access-Control-Allow-Methods:     GET, POST, PUT, DELETE, OPTIONS, HEAD, PATCH
Access-Control-Allow-Headers:     *
Access-Control-Expose-Headers:    Content-Disposition, Location, X-Job-Id
Access-Control-Allow-Credentials: false
Access-Control-Max-Age:           3600
```

So **no proxy or CORS workaround is needed** during development. For
production, see [§9 Production checklist](#9-production-checklist) — you
should narrow this down to your real frontend origin.

### Authentication

The current service is **unauthenticated**. It assumes deployment behind
a reverse proxy or VPN that does the auth. If you need per-request auth
in your client, add an `Authorization: Bearer …` header to every call —
the server ignores it today but you can wire it up later without changing
the client.

---

## 3. Endpoints at a glance

| Method | Path                                | Purpose                                         |
|--------|-------------------------------------|-------------------------------------------------|
| GET    | `/actuator/health`                  | Is the service up?                              |
| GET    | `/api/reports/formats`              | What input/output formats are supported?        |
| POST   | `/api/reports/render`               | Render a report synchronously, get bytes back   |
| POST   | `/api/reports/render-async`         | Submit a render job, get a `jobId`              |
| GET    | `/api/reports/jobs?limit=N`         | List recent jobs                                |
| GET    | `/api/reports/jobs/{id}`            | Job status & metadata                           |
| GET    | `/api/reports/jobs/{id}/result`     | Download a finished job's bytes                 |
| GET    | `/api/templates`                    | List server-stored JRXML template ids           |
| GET    | `/api/templates/{id}`               | Download a JRXML                                |
| POST   | `/api/templates`                    | Upload a JRXML (multipart `id`, `file`)         |
| DELETE | `/api/templates/{id}`               | Remove a JRXML                                  |
| GET    | `/actuator/prometheus`              | Metrics scrape endpoint                         |

---

## 4. Endpoint reference

### `GET /actuator/health`

Returns `{"status":"UP", ...}`. Use it for a startup probe in your dev tooling
or as a "service ready?" check.

```js
const ok = (await fetch(`${REPORTS_BASE_URL}/actuator/health`)).ok;
```

---

### `GET /api/reports/formats`

```json
{
  "input":  ["json", "xml", "csv", "sql", "beans"],
  "output": ["pdf", "xlsx", "xls", "html", "png", "png-zip",
             "docx", "odt", "rtf", "csv", "text", "pptx"]
}
```

Use this to populate the format dropdowns in your UI rather than hardcoding.

> Note: `sql` and `beans` are server-side input formats — they aren't
> reachable from a browser client because they need a JDBC connection or
> Java collection. Browser clients use **`json` / `xml` / `csv`**.

---

### `POST /api/reports/render` (sync)

Render a report and stream the bytes back. Always returns either the report
binary (HTTP 200) or a JSON error (4xx/5xx).

**Content-Type:** `multipart/form-data`

| Field          | Required?       | Description |
|----------------|-----------------|-------------|
| `templateId`   | one of these    | id of a server-stored template — `GET /api/templates` to list |
| `template`     | two             | a `.jrxml` file uploaded as multipart (one-off, not stored) |
| `data`         | one of these    | the data payload as a multipart file |
| `dataInline`   | two             | the data payload as a string field |
| `inputFormat`  | yes             | `json`, `xml`, or `csv` (browser-side) |
| `outputFormat` | yes             | any value from `formats.output` above |
| `parameters`   | optional        | JSON string with `Map<String,Object>` of report parameters |

**Response headers** on success:
- `Content-Type` matches the requested output format (e.g. `application/pdf`)
- `Content-Disposition: attachment; filename="report.pdf"`

**Response headers** on error:
- `Content-Type: application/json`, body shape in [§7 Error model](#7-error-model)

#### curl

```bash
curl -X POST http://localhost:8080/api/reports/render \
     -F templateId=sample \
     -F 'dataInline=[{"name":"Alpha","qty":3}]' \
     -F inputFormat=json \
     -F outputFormat=pdf \
     --output report.pdf
```

#### fetch (preferred for binary downloads)

```js
async function render({ templateId, data, inputFormat, outputFormat, parameters }) {
  const fd = new FormData();
  fd.append("templateId",   templateId);
  fd.append("inputFormat",  inputFormat);
  fd.append("outputFormat", outputFormat);
  if (data instanceof File || data instanceof Blob) fd.append("data", data);
  else fd.append("dataInline", typeof data === "string" ? data : JSON.stringify(data));
  if (parameters) fd.append("parameters", JSON.stringify(parameters));

  const res = await fetch(`${REPORTS_BASE_URL}/api/reports/render`,
                          { method: "POST", body: fd });
  if (!res.ok) {
    const err = await res.json().catch(() => ({ message: res.statusText }));
    throw new Error(err.message);
  }
  return await res.blob();
}
```

#### axios

```js
const fd = new FormData();
fd.append("templateId", "sample");
fd.append("dataInline", JSON.stringify(rows));
fd.append("inputFormat", "json");
fd.append("outputFormat", "xlsx");
const { data: blob } = await axios.post(
  `${REPORTS_BASE_URL}/api/reports/render`,
  fd,
  { responseType: "blob" } // ← important; otherwise axios tries to JSON-parse
);
```

---

### `POST /api/reports/render-async`

Same form fields as `/render`, but returns immediately with a job id. Use
this when:
- the report takes more than a few seconds (large datasets, complex layout)
- the user shouldn't have to wait on the request
- you want to render in the background and notify the user later

**Response (HTTP 202):**

```json
{
  "jobId":  "18b992ad-20ff-4dbf-bb20-7a03da7ec304",
  "status": "PENDING"
}
```

Headers:
- `Location: /api/reports/jobs/{jobId}` — RFC-compliant pointer to the resource
- `X-Job-Id: {jobId}` — convenience header

---

### `GET /api/reports/jobs/{id}`

Job status and metadata.

```json
{
  "id": "18b992ad-20ff-4dbf-bb20-7a03da7ec304",
  "status": "COMPLETED",
  "templateId": "sample",
  "inputFormat": "json",
  "outputFormat": "pdf",
  "submittedAt": "2026-05-07T14:42:00Z",
  "startedAt":   "2026-05-07T14:42:00.5Z",
  "finishedAt":  "2026-05-07T14:42:02Z",
  "contentType": "application/pdf",
  "filename":    "report-18b992ad.pdf",
  "error":       null
}
```

`status` ∈ `PENDING | RUNNING | COMPLETED | FAILED | CANCELLED`.

When `FAILED`, `error` carries the human-readable message.

Jobs are kept in memory for **2 hours** by default (`leojasper.jobs.ttl`).
Don't rely on indefinite retention — download the result soon after completion.

---

### `GET /api/reports/jobs/{id}/result`

Downloads the bytes once the job is `COMPLETED`. Same response format
as the sync `/render` endpoint. If the job is still running you get HTTP
409 with a plain-text body explaining the current status.

---

### `GET /api/reports/jobs?limit=20`

Recent jobs, newest first. Useful for an admin/debug screen.

---

### `GET /api/templates` & friends

```http
GET    /api/templates             → ["invoice", "summary", "sample"]
GET    /api/templates/{id}        → the .jrxml bytes
POST   /api/templates             → multipart {id, file} — replaces if exists
DELETE /api/templates/{id}        → 204 if removed, 404 if missing
```

Upload example:

```js
const fd = new FormData();
fd.append("id",   "invoice");
fd.append("file", jrxmlFile);   // a File from <input type="file">
await fetch(`${REPORTS_BASE_URL}/api/templates`, { method: "POST", body: fd });
```

> Template ids may contain `/` for folders (e.g. `invoices/v3`) but not `..`
> — path traversal attempts return HTTP 400.

---

## 5. TypeScript client

A drop-in client. Save as `src/lib/leoJasper.ts`.

```ts
export type InputFormat  = "json" | "xml" | "csv";
export type OutputFormat =
  | "pdf" | "xlsx" | "xls" | "html" | "png" | "png-zip"
  | "docx" | "odt" | "rtf" | "csv" | "text" | "pptx";

export type RenderRequest = {
  /** Either templateId OR template (uploaded multipart). */
  templateId?:  string;
  template?:    Blob | File;
  /** Either dataInline (string) OR data (Blob/File). */
  dataInline?:  string;
  data?:        Blob | File;
  inputFormat:  InputFormat;
  outputFormat: OutputFormat;
  parameters?:  Record<string, unknown>;
};

export type ReportJob = {
  id: string;
  status: "PENDING" | "RUNNING" | "COMPLETED" | "FAILED" | "CANCELLED";
  templateId: string;
  inputFormat: string;
  outputFormat: string;
  submittedAt: string;
  startedAt?:  string | null;
  finishedAt?: string | null;
  contentType?: string | null;
  filename?:    string | null;
  error?:       string | null;
};

export class LeoJasperClient {
  constructor(private baseUrl = "http://localhost:8080") {}

  private buildForm(req: RenderRequest): FormData {
    const fd = new FormData();
    if (req.templateId) fd.append("templateId", req.templateId);
    if (req.template)   fd.append("template",   req.template);
    if (req.data)       fd.append("data",       req.data);
    if (req.dataInline) fd.append("dataInline", req.dataInline);
    fd.append("inputFormat",  req.inputFormat);
    fd.append("outputFormat", req.outputFormat);
    if (req.parameters) fd.append("parameters", JSON.stringify(req.parameters));
    return fd;
  }

  async render(req: RenderRequest): Promise<Blob> {
    const res = await fetch(`${this.baseUrl}/api/reports/render`,
                            { method: "POST", body: this.buildForm(req) });
    if (!res.ok) throw await this.toError(res);
    return res.blob();
  }

  async submit(req: RenderRequest): Promise<ReportJob> {
    const res = await fetch(`${this.baseUrl}/api/reports/render-async`,
                            { method: "POST", body: this.buildForm(req) });
    if (!res.ok) throw await this.toError(res);
    return res.json();
  }

  async job(id: string): Promise<ReportJob> {
    const res = await fetch(`${this.baseUrl}/api/reports/jobs/${id}`);
    if (!res.ok) throw await this.toError(res);
    return res.json();
  }

  async jobResult(id: string): Promise<Blob> {
    const res = await fetch(`${this.baseUrl}/api/reports/jobs/${id}/result`);
    if (!res.ok) throw await this.toError(res);
    return res.blob();
  }

  /** Submit, poll until done, return the rendered bytes. */
  async renderAsync(req: RenderRequest, opts: { intervalMs?: number; timeoutMs?: number } = {}): Promise<Blob> {
    const interval = opts.intervalMs ?? 1000;
    const timeout  = opts.timeoutMs  ?? 5 * 60 * 1000;
    const job0 = await this.submit(req);
    const deadline = Date.now() + timeout;
    while (Date.now() < deadline) {
      const j = await this.job(job0.id);
      if (j.status === "COMPLETED") return this.jobResult(job0.id);
      if (j.status === "FAILED" || j.status === "CANCELLED") {
        throw new Error(j.error ?? `Job ${j.status}`);
      }
      await new Promise(r => setTimeout(r, interval));
    }
    throw new Error(`Render timed out after ${timeout} ms`);
  }

  async templates(): Promise<string[]> {
    return (await fetch(`${this.baseUrl}/api/templates`)).json();
  }

  async uploadTemplate(id: string, file: Blob): Promise<void> {
    const fd = new FormData();
    fd.append("id", id);
    fd.append("file", file);
    const res = await fetch(`${this.baseUrl}/api/templates`,
                            { method: "POST", body: fd });
    if (!res.ok) throw await this.toError(res);
  }

  private async toError(res: Response): Promise<Error> {
    const ct = res.headers.get("content-type") ?? "";
    if (ct.includes("application/json")) {
      const body = await res.json().catch(() => null);
      return new Error(body?.message ?? `HTTP ${res.status}`);
    }
    return new Error(`HTTP ${res.status}: ${await res.text()}`);
  }
}
```

---

## 6. React hook example

```tsx
import { useState } from "react";
import { LeoJasperClient, RenderRequest } from "../lib/leoJasper";

const client = new LeoJasperClient(import.meta.env.VITE_REPORTS_BASE_URL);

export function useReport() {
  const [loading, setLoading] = useState(false);
  const [error,   setError]   = useState<string | null>(null);

  async function render(req: RenderRequest, filename = "report") {
    setLoading(true); setError(null);
    try {
      const blob = await client.render(req);
      triggerDownload(blob, `${filename}.${req.outputFormat}`);
    } catch (e: any) {
      setError(e.message); throw e;
    } finally {
      setLoading(false);
    }
  }

  return { render, loading, error };
}

function triggerDownload(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const a   = document.createElement("a");
  a.href = url; a.download = filename; a.click();
  URL.revokeObjectURL(url);
}
```

Use it:

```tsx
const { render, loading, error } = useReport();

<button
  disabled={loading}
  onClick={() => render({
    templateId:  "sample",
    dataInline:  JSON.stringify(rows),
    inputFormat: "json",
    outputFormat:"pdf",
    parameters:  { REPORT_TITLE: "Q1 sales" },
  }, "q1-sales")}
>
  {loading ? "Rendering…" : "Download PDF"}
</button>
```

---

## 7. Error model

Every 4xx/5xx response is JSON in this shape:

```json
{
  "status":    400,
  "error":     "Bad Request",
  "message":   "Unknown templateId: invoice",
  "timestamp": "2026-05-07T14:42:00Z"
}
```

Common cases your UI should handle:

| Code | Typical cause |
|------|---------------|
| 400  | wrong `templateId`, malformed `parameters` JSON, unsupported format |
| 409  | calling `/jobs/{id}/result` before the job is `COMPLETED` |
| 413  | uploaded file exceeds the server's multipart limit (default 50 MB) |
| 500  | template/data parse failure inside JasperReports — `message` carries the JR error |

The `Content-Type: application/json` on the error response means it's safe to
`JSON.parse()` the body in your error handler regardless of what the success
response would have been.

---

## 8. Notes & gotchas

- **Field names in the report.** Your `data` keys must match the `<field name="…">`
  declarations in the JRXML. If JasperReports complains about a missing field,
  rename your JSON keys (or update the template).
- **JSON shape for the default factory.** The built-in `json` factory iterates
  the JSON root if it's an array. `[{…},{…}]` works out of the box. If your
  JSON wraps the rows (`{"items":[…]}`), either pre-extract on the client or
  switch the field path in the template. Same pattern for XML.
- **CSV needs a header row.** The first line is read as field names.
- **Templates are cached**, keyed by mtime. Replace a `.jrxml` and the next
  render picks it up automatically.
- **Don't try to call `/render` with `responseType: "json"` in axios.** The
  success response is binary; axios will mangle it. Use `responseType: "blob"`
  always; for errors, axios re-reads the blob as JSON when status ≠ 2xx.
- **Long-running renders go through `/render-async`.** Browsers and proxies
  often have a 30–60 s response timeout. If a sync `/render` ever hits it,
  switch that call to `client.renderAsync(...)` — same inputs, polled.

---

## 9. Production checklist

Before shipping to production, ask the backend team to:

1. **Lock CORS down** to your real frontend origin in `WebConfig`:
   ```java
   .allowedOrigins("https://app.yourco.com")  // not "*"
   .allowCredentials(true)                    // if you need session cookies
   ```
2. **Put the service behind TLS** and a reverse proxy (nginx, Cloudflare, ALB).
3. **Add authentication** — the service ships unauth. Add a JWT filter, an
   API-key header check, or rely on the proxy.
4. **Tune file size limits** in `application.yml`
   (`spring.servlet.multipart.max-file-size`).
5. **Persist the job store** if you want jobs to survive a restart — swap
   `InMemoryReportJobStore` for a Redis or DB-backed implementation.
6. **Scrape the metrics.** `GET /actuator/prometheus` exposes
   `leojasper_compile_seconds`, `leojasper_fill_seconds{inputFormat}`,
   `leojasper_export_seconds{outputFormat}`, `leojasper_errors_total{stage}`.

---

## Appendix — supported field bindings per input format

| Input | Field path syntax | Example |
|-------|-------------------|---------|
| `json`  | JSON keys, dot-notation in JRXML field description | `<fieldDescription>customer.name</fieldDescription>` |
| `xml`   | XPath relative to the iteration node                | `<fieldDescription>name</fieldDescription>` (= child element `<name>`) |
| `csv`   | the column header from row 1                        | `<field name="qty"/>` ↔ CSV column `qty` |

For `xml`, set the iteration root in the JRXML's `<queryString>` element using
XPath, e.g. `<queryString language="xpath">/orders/order</queryString>`.
