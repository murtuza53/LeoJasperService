package com.leojasper.rest.config;

import com.leojasper.service.LeoJasperService;
import com.leojasper.service.ReportMetrics;
import com.leojasper.service.job.AsyncReportService;
import com.leojasper.service.job.InMemoryReportJobStore;
import com.leojasper.service.job.ReportJobStore;
import com.leojasper.rest.security.AdminAuthFilter;
import com.leojasper.rest.security.LicenseFilter;
import com.leojasper.service.security.AdminSessionStore;
import com.leojasper.service.security.AssetService;
import com.leojasper.service.security.AuditLogger;
import com.leojasper.service.security.AuthService;
import com.leojasper.service.security.CompanyRegistry;
import com.leojasper.service.security.CredentialsStore;
import com.leojasper.service.security.RateLimiter;
import com.leojasper.service.synthesis.AssetStore;
import com.leojasper.service.synthesis.GeminiVisionAnalyzer;
import com.leojasper.service.synthesis.MockVisionAnalyzer;
import com.leojasper.service.synthesis.TemplateSynthesisService;
import com.leojasper.service.synthesis.VisionAnalyzer;
import com.leojasper.service.template.FileSystemTemplateRegistry;
import com.leojasper.service.template.TemplateRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

@Configuration
public class ServiceConfig {

    private static final Logger log = LoggerFactory.getLogger(ServiceConfig.class);

    @Bean(destroyMethod = "close")
    public AsyncReportService asyncReportService(LeoJasperService service, ReportJobStore store,
                                                 LeoJasperProperties props) {
        return new AsyncReportService(service, store, props.getJobs().getParallelism());
    }

    @Bean
    public ReportJobStore reportJobStore(LeoJasperProperties props) {
        return new InMemoryReportJobStore(props.getJobs().getTtl());
    }

    @Bean
    public LeoJasperService leoJasperService(TemplateRegistry registry,
                                             ReportMetrics metrics,
                                             LeoJasperProperties props) {
        return new LeoJasperService()
                .templateRegistry(registry)
                .metrics(metrics)
                .useSqlVirtualizer(props.isVirtualizer())
                .cacheEnabled(props.isCache());
    }

    @Bean
    public TemplateRegistry templateRegistry(@Value("${leojasper.templates.path:./templates}") String path) {
        return new FileSystemTemplateRegistry(Paths.get(path));
    }

    @Bean
    public ReportMetrics reportMetrics(MeterRegistry registry) {
        return new ReportMetrics(registry);
    }

    @Bean
    @ConfigurationProperties("leojasper")
    public LeoJasperProperties leoJasperProperties() {
        return new LeoJasperProperties();
    }

    // ----- security: admin + license -----

    @Bean
    public CredentialsStore credentialsStore(LeoJasperProperties props) {
        return new CredentialsStore(vaultDir(props).resolve("credentials.enc"));
    }

    @Bean
    public CompanyRegistry companyRegistry(LeoJasperProperties props) {
        return new CompanyRegistry(vaultDir(props).resolve("companies.enc"));
    }

    /** Vault always lives under the assets root so a fresh deploy can't wipe it. */
    private static Path vaultDir(LeoJasperProperties props) {
        return Paths.get(props.getAdmin().getDefaultAssetsRoot())
                    .resolve(".leojasper")
                    .toAbsolutePath()
                    .normalize();
    }

    @Bean
    public AdminSessionStore adminSessionStore(LeoJasperProperties props) {
        return new AdminSessionStore(props.getAdmin().getSessionTtl());
    }

    @Bean
    public AuthService authService(CredentialsStore credentialsStore,
                                   CompanyRegistry companyRegistry,
                                   AdminSessionStore sessions,
                                   LeoJasperProperties props) {
        return new AuthService(credentialsStore, companyRegistry, sessions,
                props.getAdmin().getDefaultAssetsRoot());
    }

    @Bean
    public RateLimiter rateLimiter(LeoJasperProperties props) {
        return new RateLimiter(
                props.getAdmin().getRateLimit().getRequestsPerMinute(),
                60_000L,
                props.getAdmin().getRateLimit().getAuthLockThreshold(),
                props.getAdmin().getRateLimit().getAuthLockMinutes() * 60_000L);
    }

    @Bean
    public AuditLogger auditLogger() { return new AuditLogger(); }

    @Bean
    public AssetService assetService(LeoJasperProperties props) {
        return new AssetService(Duration.ofDays(props.getAdmin().getTrashRetentionDays()));
    }

    @Bean
    public FilterRegistrationBean<AdminAuthFilter> adminAuthFilter(AuthService authService) {
        FilterRegistrationBean<AdminAuthFilter> reg = new FilterRegistrationBean<>(
                new AdminAuthFilter(authService));
        reg.addUrlPatterns("/api/admin/*");
        reg.setOrder(10);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<LicenseFilter> licenseFilter(AuthService auth,
                                                               RateLimiter rateLimiter,
                                                               AuditLogger auditLogger) {
        FilterRegistrationBean<LicenseFilter> reg = new FilterRegistrationBean<>(
                new LicenseFilter(auth, rateLimiter, auditLogger));
        reg.addUrlPatterns("/api/*");
        reg.setOrder(20);   // runs after admin filter
        return reg;
    }

    // ----- synthesis -----

    @Bean
    public AssetStore assetStore(@Value("${leojasper.synthesis.assets-path:./synthesis-assets}") String path) {
        return new AssetStore(Paths.get(path));
    }

    @Bean
    public VisionAnalyzer visionAnalyzer(LeoJasperProperties props) {
        String key = resolveKey(props.getSynthesis().getGeminiApiKey());
        if (key == null || key.isBlank()) {
            log.warn("GEMINI_API_KEY not set — falling back to MockVisionAnalyzer. " +
                     "Set leojasper.synthesis.gemini-api-key (or GEMINI_API_KEY env var) for real analysis.");
            return new MockVisionAnalyzer();
        }
        return new GeminiVisionAnalyzer(key, props.getSynthesis().getGeminiModel());
    }

    @Bean
    public TemplateSynthesisService templateSynthesisService(VisionAnalyzer analyzer,
                                                             TemplateRegistry registry,
                                                             AssetStore assetStore,
                                                             LeoJasperProperties props) {
        return new TemplateSynthesisService(
                analyzer, registry,
                Paths.get(props.getTemplates().getPath()),
                assetStore);
    }

    /** Allow ${ENV_VAR} style indirection in the property value. */
    private static String resolveKey(String configured) {
        if (configured == null || configured.isBlank()) {
            return System.getenv("GEMINI_API_KEY");
        }
        if (configured.startsWith("${") && configured.endsWith("}")) {
            String envName = configured.substring(2, configured.length() - 1);
            return System.getenv(envName);
        }
        return configured;
    }

    public static class LeoJasperProperties {
        private final Templates templates = new Templates();
        private final Jobs jobs = new Jobs();
        private final Synthesis synthesis = new Synthesis();
        private final Admin admin = new Admin();
        private boolean cache = true;
        private boolean virtualizer = false;

        public Templates getTemplates() { return templates; }
        public Jobs getJobs() { return jobs; }
        public Synthesis getSynthesis() { return synthesis; }
        public Admin getAdmin() { return admin; }
        public boolean isCache() { return cache; }
        public void setCache(boolean cache) { this.cache = cache; }
        public boolean isVirtualizer() { return virtualizer; }
        public void setVirtualizer(boolean virtualizer) { this.virtualizer = virtualizer; }

        public static class Templates {
            private String path = "./templates";
            public String getPath() { return path; }
            public void setPath(String path) { this.path = path; }
            public Path resolved() { return Paths.get(path); }
        }

        public static class Jobs {
            private int parallelism = Math.max(2, Runtime.getRuntime().availableProcessors());
            private Duration ttl = Duration.ofHours(2);
            public int getParallelism() { return parallelism; }
            public void setParallelism(int parallelism) { this.parallelism = parallelism; }
            public Duration getTtl() { return ttl; }
            public void setTtl(Duration ttl) { this.ttl = ttl; }
        }

        public static class Synthesis {
            /** Either a literal API key or {@code ${ENV_VAR}}. Defaults to env GEMINI_API_KEY. */
            private String geminiApiKey;
            private String geminiModel = "gemini-2.5-flash";
            private String assetsPath  = "./synthesis-assets";

            public String getGeminiApiKey() { return geminiApiKey; }
            public void setGeminiApiKey(String geminiApiKey) { this.geminiApiKey = geminiApiKey; }
            public String getGeminiModel() { return geminiModel; }
            public void setGeminiModel(String geminiModel) { this.geminiModel = geminiModel; }
            public String getAssetsPath() { return assetsPath; }
            public void setAssetsPath(String assetsPath) { this.assetsPath = assetsPath; }
        }

        public static class Admin {
            /**
             * Where company asset folders live, AND where {@code .leojasper/}
             * (the encrypted credentials + companies vault) is stored.
             * Move this off the install dir so a redeploy can't wipe the vault.
             */
            private String defaultAssetsRoot = "./assets";
            private Duration sessionTtl     = Duration.ofMinutes(30);
            private int trashRetentionDays  = 30;
            private final RateLimit rateLimit = new RateLimit();

            public String getDefaultAssetsRoot() { return defaultAssetsRoot; }
            public void setDefaultAssetsRoot(String s) { this.defaultAssetsRoot = s; }
            public Duration getSessionTtl()     { return sessionTtl; }
            public void setSessionTtl(Duration d) { this.sessionTtl = d; }
            public int getTrashRetentionDays()  { return trashRetentionDays; }
            public void setTrashRetentionDays(int d) { this.trashRetentionDays = d; }
            public RateLimit getRateLimit()     { return rateLimit; }

            public static class RateLimit {
                private int requestsPerMinute = 60;
                private int authLockThreshold = 5;
                private int authLockMinutes   = 5;
                public int getRequestsPerMinute() { return requestsPerMinute; }
                public void setRequestsPerMinute(int n) { this.requestsPerMinute = n; }
                public int getAuthLockThreshold() { return authLockThreshold; }
                public void setAuthLockThreshold(int n) { this.authLockThreshold = n; }
                public int getAuthLockMinutes()   { return authLockMinutes; }
                public void setAuthLockMinutes(int n)   { this.authLockMinutes = n; }
            }
        }
    }
}
