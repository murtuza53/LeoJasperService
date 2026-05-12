package com.leojasper.cli;

import com.leojasper.service.LeoJasperService;
import com.leojasper.service.OutputFormat;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "leo-jasper",
        mixinStandardHelpOptions = true,
        version = "leo-jasper 1.0.0",
        description = "Compile a JRXML, fill it from JSON/XML/CSV/beans, export to PDF/XLSX/HTML/PNG/DOCX/ODT/RTF/CSV/TEXT/PPTX."
)
public class LeoJasperCli implements Callable<Integer> {

    @Option(names = {"-t", "--template"}, required = true,
            description = "Path to the JRXML template")
    private Path template;

    @Option(names = {"-d", "--data"}, required = true,
            description = "Input data file (or - for stdin not yet supported)")
    private Path data;

    @Option(names = {"-i", "--input-format"}, required = true,
            description = "Input format: json|xml|csv|beans")
    private String inputFormat;

    @Option(names = {"-f", "--output-format"}, required = true,
            description = "Output format: pdf|xlsx|xls|html|png|png-zip|docx|odt|rtf|csv|text|pptx")
    private String outputFormat;

    @Option(names = {"-o", "--out"}, required = true,
            description = "Output file")
    private Path out;

    @Option(names = {"-p", "--param"},
            description = "Report parameter, repeatable: -p key=value")
    private Map<String, String> rawParams = new HashMap<>();

    @Override
    public Integer call() throws Exception {
        if (!Files.exists(template)) {
            System.err.println("Template not found: " + template);
            return 2;
        }
        if (!Files.exists(data)) {
            System.err.println("Data file not found: " + data);
            return 2;
        }

        Map<String, Object> params = new HashMap<>(rawParams);

        LeoJasperService service = new LeoJasperService();
        byte[] bytes;
        try {
            bytes = service.generateReport(
                    template.toString(),
                    Files.readAllBytes(data),
                    inputFormat,
                    outputFormat,
                    params);
        } catch (Exception e) {
            System.err.println("Failed: " + e.getMessage());
            return 1;
        }

        Files.write(out, bytes);
        OutputFormat fmt = OutputFormat.from(outputFormat);
        System.out.println("Wrote " + bytes.length + " bytes to " + out + " (" + fmt.contentType() + ")");
        return 0;
    }

    public static void main(String[] args) throws IOException {
        Path here = Paths.get(".").toAbsolutePath();
        if (here == null) { /* satisfy IDE */ }
        int rc = new CommandLine(new LeoJasperCli()).execute(args);
        System.exit(rc);
    }
}
