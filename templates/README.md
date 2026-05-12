# Sample templates

Each `.jrxml` here is registered with the `FileSystemTemplateRegistry` and
appears at `GET /api/templates`. Matching test data sits under `sample-data/`.

| Template               | Input format | Sample data file                       | Highlights |
|------------------------|--------------|----------------------------------------|------------|
| `sample`               | json         | (use any inline rows)                  | Tiny smoke-test report — name + qty |
| `invoice`              | json         | `sample-data/invoice.json`             | Parameters, currency formatting, sum variable for total |
| `sales-report`         | json         | `sample-data/sales-report.json`        | Group by region with subtotals + grand total |
| `customer-list`        | csv          | `sample-data/customer-list.csv`        | CSV input, dark column header, page numbering |
| `employee-directory`   | json         | `sample-data/employee-directory.json`  | Group by department, per-group count |
| `product-catalog`      | json         | `sample-data/product-catalog.json`     | Two-column layout, rounded borders |

## How to test each one

### From the test page (recommended)

Open <http://localhost:8080/>, pick the template from the dropdown, set
`Data → Upload file` to the matching file under `sample-data/`, choose an
output format, click **Render**.

Templates that need parameters (`invoice`) — paste this into the
"Report parameters" textarea before rendering:

```json
{
  "INVOICE_NUMBER":   "INV-2026-001",
  "INVOICE_DATE":     "2026-05-08",
  "CUSTOMER_NAME":    "Acme Corp",
  "CUSTOMER_ADDRESS": "123 Business Ave, Springfield, ST 12345"
}
```

### From curl

```bash
# Invoice → PDF
curl -X POST http://localhost:8080/api/reports/render \
     -F templateId=invoice \
     -F "data=@templates/sample-data/invoice.json" \
     -F inputFormat=json -F outputFormat=pdf \
     -F 'parameters={"INVOICE_NUMBER":"INV-2026-001","INVOICE_DATE":"2026-05-08","CUSTOMER_NAME":"Acme Corp","CUSTOMER_ADDRESS":"123 Business Ave, City, ST 12345"}' \
     --output invoice.pdf

# Sales report → XLSX
curl -X POST http://localhost:8080/api/reports/render \
     -F templateId=sales-report \
     -F "data=@templates/sample-data/sales-report.json" \
     -F inputFormat=json -F outputFormat=xlsx \
     --output sales.xlsx

# Customer list → HTML  (CSV input!)
curl -X POST http://localhost:8080/api/reports/render \
     -F templateId=customer-list \
     -F "data=@templates/sample-data/customer-list.csv" \
     -F inputFormat=csv -F outputFormat=html \
     --output customers.html

# Employee directory → DOCX
curl -X POST http://localhost:8080/api/reports/render \
     -F templateId=employee-directory \
     -F "data=@templates/sample-data/employee-directory.json" \
     -F inputFormat=json -F outputFormat=docx \
     --output directory.docx

# Product catalog → multi-page PNG zip
curl -X POST http://localhost:8080/api/reports/render \
     -F templateId=product-catalog \
     -F "data=@templates/sample-data/product-catalog.json" \
     -F inputFormat=json -F outputFormat=png-zip \
     --output catalog-pages.zip
```

## Adding your own templates

1. Drop `my-template.jrxml` next to these files.
2. The registry picks it up immediately — no restart needed (cache invalidates
   on mtime change).
3. Confirm with `GET /api/templates`; the new id should appear in the list.

### Conventions used here

- **JSON input expects a root array.** The default `JsonDataSource` iterates
  the root if it's an array. If your data is wrapped (e.g. `{"items":[…]}`)
  either pre-extract on the client or add a `<queryString language="json">items</queryString>`
  to the JRXML.
- **For grouping, sort the input by the group field**. JasperReports doesn't
  sort — it just resets aggregates when the field value changes. The
  `sales-report` and `employee-directory` data files are pre-sorted.
- **CSV must have a header row** matching the JRXML field names.
- **Field types matter.** `class="java.lang.Integer"` will reject non-numeric
  strings; use `java.lang.String` if you're not sure.
- **Currency / date patterns** go in the `pattern="…"` attribute on
  `<textField>`, e.g. `pattern="$#,##0.00"` or `pattern="yyyy-MM-dd"`.
