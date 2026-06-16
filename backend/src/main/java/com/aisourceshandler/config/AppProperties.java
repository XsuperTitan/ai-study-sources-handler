package com.aisourceshandler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        boolean requireAiConfiguration,
        String storageRoot,
        Upload upload,
        Pdf pdf,
        Jobs jobs,
        Provider deepseek,
        Provider qwen,
        Provider wanx,
        Video video
) {
    public record Upload(int maxFiles, long maxTotalBytes, long maxImageBytes, long maxTextBytes, int maxPdfPages) {}
    public record Pdf(int visualPageLimit, int renderDpi) {}
    public record Jobs(int corePoolSize, int maxPoolSize, int queueCapacity) {}
    public record Provider(String apiKey, String baseUrl, String model,
                           Integer downloadTimeoutSeconds, Integer downloadRetries) {
        public boolean configured() {
            return apiKey != null && !apiKey.isBlank() && model != null && !model.isBlank();
        }

        public int effectiveDownloadTimeoutSeconds() {
            return downloadTimeoutSeconds == null || downloadTimeoutSeconds <= 0 ? 20 : downloadTimeoutSeconds;
        }

        public int effectiveDownloadRetries() {
            return downloadRetries == null || downloadRetries <= 0 ? 3 : downloadRetries;
        }
    }
    public record Video(String ytDlpPath, int timeoutSeconds, String cookiesFromBrowser) {}
}
