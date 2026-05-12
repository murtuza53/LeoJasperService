# LeoJasperService

A multi-module Java service that compiles a JasperReports `.jrxml`, fills it
from JSON / XML / CSV / SQL / JavaBeans, and exports it to **PDF, XLSX, XLS,
HTML, PNG, multi-page PNG (zip), DOCX, ODT, RTF, CSV, TEXT, PPTX**.

```
leo-jasper-parent/
├── leo-jasper-core/   # the service library
├── leo-jasper-rest/   # Spring Boot REST API (open CORS, multipart, async jobs)
└── leo-jasper-cli/    # picocli-based command-line tool
```

## Build

Maven 3.9 / JDK 17.

```bash
"C:\apache-maven-3.9.15\bin\mvn" -DskipTests package
```

Artifacts:
- `leo-jasper-core/target/leo-jasper-core-1.0.0.jar`
- `leo-jasper-rest/target/leo-jasper-rest.jar` — Spring Boot fat jar
- `leo-jasper-cli/target/leo-jasper-cli-shaded.jar` — runnable CLI

Run the tests once to verify all 12 export formats work:
```bash
"C:\apache-maven-3.9.15\bin\mvn" -pl leo-jasper-core test
```

## Run the REST service

```bash
"C:\apache-maven-3.9.15\bin\mvn" -pl leo-jasper-rest -am spring-boot:run
# or
java -jar leo-jasper-rest/target/leo-jasper-rest.jar
```

Defaults: port `8080`, templates dir `./templates/`, multipart cap 50 MB,
async-job parallelism 4.

### Endpoints

| Method | Path                                     | Purpose                                     |
|--------|------------------------------------------|---------------------------------------------|
| POST   | `/api/reports/render`                    | Synchronous — returns the report bytes      |
| POST   | `/api/reports/render-async`              | Submit a job, returns `{jobId,status}`      |
| GET    | `/api/reports/jobs/{id}`                 | Job metadata (status, timestamps, error)    |
| GET    | `/api/reports/jobs/{id}/result`          | Download finished report                    |
| GET    | `/api/reports/jobs?limit=20`             | Recent jobs                                 |
| GET    | `/api/reports/formats`                   | Supported input/output formats              |
| GET    | `/api/templates`                         | List registered templates                   |
| GET    | `/api/templates/{id}`                    | Download a `.jrxml`                         |
| POST   | `/api/templates`                         | Upload a `.jrxml` (multipart `id`+`file`)   |
| DELETE | `/api/templates/{id}`                    | Remove a template                           |
| GET    | `/actuator/health`                       | Health probe (used by Docker `HEALTHCHECK`) |
| GET    | `/actuator/prometheus`                   | Micrometer metrics in Prometheus format     |

CORS is wide-open (`allowedOriginPatterns("*")`, all methods) so any client —
including a React dev server on `localhost:3000` — can call it directly.

### Calling from React

```jsx
async function renderPdf(rows) {
  const fd = new FormData();
  fd.append("templateId", "sample");                 // uses ./templates/sample.jrxml
  fd.append("dataInline", JSON.stringify(rows));     // or `data` as a File
  fd.append("inputFormat", "json");
  fd.append("outputFormat", "pdf");
  fd.append("parameters", JSON.stringify({ REPORT_TITLE: "Hello" }));

  const res = await fetch("http://localhost:8080/api/reports/render", {
    method: "POST", body: fd
  });
  if (!res.ok) throw new Error(await res.text());
  const blob = await res.blob();
  window.open(URL.createObjectURL(blob), "_blank");
}
```

For long-running reports, use the async flow:

```jsx
const res = await fetch("http://localhost:8080/api/reports/render-async",
                        { method: "POST", body: fd });
const { jobId } = await res.json();

// poll until done
while (true) {
  const j = await (await fetch(`http://localhost:8080/api/reports/jobs/${jobId}`)).json();
  if (j.status === "COMPLETED") break;
  if (j.status === "FAILED")    throw new Error(j.error);
  await new Promise(r => setTimeout(r, 800));
}
const blob = await (await fetch(
  `http://localhost:8080/api/reports/jobs/${jobId}/result`)).blob();
```

## Run the CLI

```bash
java -jar leo-jasper-cli/target/leo-jasper-cli-shaded.jar \
    --template templates/sample.jrxml \
    --data    data/items.json \
    --input-format  json \
    --output-format pdf \
    --out report.pdf \
    -p REPORT_TITLE="Hello"
```

## Use the library directly

```java
LeoJasperService service = new LeoJasperService()
    .templateRegistry(new FileSystemTemplateRegistry(Paths.get("templates")))
    .pdfPostProcessors(List.of(
        new WatermarkPostProcessor("DRAFT"),
        new PasswordEncryptionPostProcessor("user", "owner")))
    .useSqlVirtualizer(true);

byte[] pdf = service.generateReport("invoice", rows, "beans", "pdf",
    LeoJasperService.withLocale(params, Locale.GERMAN, ResourceBundle.getBundle("invoice")));
```

## Docker

```bash
docker build -t leojasper .
docker run --rm -p 8080:8080 -v "$(pwd)/templates:/app/templates" leojasper
```

The image runs the REST module under a non-root user with a built-in
`HEALTHCHECK` hitting `/actuator/health`.

## Plugging in a custom data source (Phase 6 SPI)

```java
public class MongoDataSourceFactory implements JRDataSourceFactory {
    public String name() { return "mongo"; }
    public boolean supports(Object o) { return o instanceof MongoQuerySpec; }
    public JRDataSource create(Object o) { return new MongoJrDataSource((MongoQuerySpec) o); }
}
```

Register via `META-INF/services/com.leojasper.service.datasource.JRDataSourceFactory`
or `service.registerDataSourceFactory(new MongoDataSourceFactory())`. Then call
`generateReport(..., "mongo", ...)`.
