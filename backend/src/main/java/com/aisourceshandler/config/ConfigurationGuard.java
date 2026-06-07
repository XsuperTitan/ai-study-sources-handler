package com.aisourceshandler.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class ConfigurationGuard {
    private final AppProperties properties;

    public ConfigurationGuard(AppProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void validate() {
        if (!properties.requireAiConfiguration()) return;
        if (!properties.deepseek().configured()) {
            throw new IllegalStateException("DEEPSEEK_API_KEY is required");
        }
        if (!properties.qwen().configured()) {
            throw new IllegalStateException("DASHSCOPE_API_KEY and QWEN_VL_MODEL are required");
        }
    }
}
