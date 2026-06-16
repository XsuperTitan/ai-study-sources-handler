package com.aisourceshandler;

import com.aisourceshandler.config.AppProperties;

public final class TestProperties {
    private TestProperties() {}

    public static AppProperties create(String root, String deepSeekBaseUrl) {
        return new AppProperties(
                false,
                root,
                new AppProperties.Upload(20, 104857600, 10485760, 2097152, 300),
                new AppProperties.Pdf(12, 144),
                new AppProperties.Jobs(1, 2, 8),
                new AppProperties.Provider("test-key", deepSeekBaseUrl, "deepseek-chat", null, null),
                new AppProperties.Provider("test-key", "http://localhost", "qwen-vl-max", null, null),
                new AppProperties.Provider("", "http://localhost", "wanx", null, null),
                new AppProperties.Video("yt-dlp", 5, "")
        );
    }
}

