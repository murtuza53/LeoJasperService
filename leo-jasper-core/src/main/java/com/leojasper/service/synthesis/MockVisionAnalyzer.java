package com.leojasper.service.synthesis;

import java.util.Arrays;

/**
 * Hard-coded fallback analyzer — produces a generic invoice-shaped model
 * regardless of input. Keeps the synthesis pipeline runnable without an
 * API key, so the rest of the wiring can be exercised end-to-end.
 */
public class MockVisionAnalyzer implements VisionAnalyzer {

    @Override public String name() { return "mock"; }

    @Override
    public LayoutModel analyze(byte[] pngBytes, String userHint) {
        LayoutModel m = new LayoutModel();
        m.title = "Mock invoice (analyzer not configured)";

        m.parameters.addAll(Arrays.asList(
                new LayoutModel.Parameter("INVOICE_NUMBER", "java.lang.String",
                        "Invoice number (top right)", "INV-2026-001"),
                new LayoutModel.Parameter("INVOICE_DATE", "java.lang.String",
                        "Invoice date", "2026-05-08"),
                new LayoutModel.Parameter("CUSTOMER_NAME", "java.lang.String",
                        "Bill-to customer name", "Acme Corp"),
                new LayoutModel.Parameter("CUSTOMER_ADDRESS", "java.lang.String",
                        "Bill-to address", "123 Business Ave, City, ST 12345")
        ));

        m.fields.addAll(Arrays.asList(
                new LayoutModel.Field("description", "java.lang.String",
                        "Line item description", "Web design (40 hours)"),
                new LayoutModel.Field("quantity", "java.lang.Integer",
                        "Quantity", "1"),
                new LayoutModel.Field("unit_price", "java.lang.Double",
                        "Unit price", "100.00"),
                new LayoutModel.Field("amount", "java.lang.Double",
                        "Line amount = quantity × unit_price", "100.00")
        ));

        LayoutModel.TableColumn c1 = new LayoutModel.TableColumn();
        c1.name = "description"; c1.header = "Description";
        c1.x = 0; c1.width = 290; c1.alignment = "Left";
        LayoutModel.TableColumn c2 = new LayoutModel.TableColumn();
        c2.name = "quantity"; c2.header = "Qty";
        c2.x = 290; c2.width = 60; c2.alignment = "Right";
        LayoutModel.TableColumn c3 = new LayoutModel.TableColumn();
        c3.name = "unit_price"; c3.header = "Unit Price";
        c3.x = 350; c3.width = 90; c3.alignment = "Right";
        c3.pattern = "$#,##0.00";
        LayoutModel.TableColumn c4 = new LayoutModel.TableColumn();
        c4.name = "amount"; c4.header = "Amount";
        c4.x = 440; c4.width = 95; c4.alignment = "Right";
        c4.pattern = "$#,##0.00";
        m.tableColumns.addAll(Arrays.asList(c1, c2, c3, c4));

        LayoutModel.TableStyle ts = new LayoutModel.TableStyle();
        ts.cellBorderWidth = 0.5f;
        ts.cellBorderColor = "#BBBBBB";
        ts.cellBorderSides = "bottom,right";
        ts.headerBorderWidth = 1.0f;
        ts.headerBorderColor = "#000000";
        ts.headerBorderSides = "top,bottom";
        ts.headerBackground = "#1F3A5F";
        ts.headerForeground = "#FFFFFF";
        m.tableStyle = ts;

        return m;
    }
}
