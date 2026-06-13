package com.aisourceshandler.infrastructure;

import com.aisourceshandler.api.ApiException;
import com.aisourceshandler.config.AppProperties;
import com.aisourceshandler.config.RagProperties;
import com.aisourceshandler.domain.Models.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class AiProviders {
    private static final Logger log = LoggerFactory.getLogger(AiProviders.class);

    public record AiResult(String content, JobMetrics metrics) {}
    public record VisionResult(String title, String visibleText, List<String> knowledgePoints,
                               List<String> codeBlocks, List<String> uncertainItems, double confidence) {}

    private final AppProperties properties;
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper mapper;
    private final LocalStore store;
    private final RagProperties ragProperties;

    @Autowired
    public AiProviders(AppProperties properties, RagProperties ragProperties,
                       RestClient.Builder restClientBuilder, ObjectMapper mapper,
                       LocalStore store) {
        this.properties = properties;
        this.ragProperties = ragProperties;
        this.restClientBuilder = restClientBuilder;
        this.mapper = mapper;
        this.store = store;
    }

    public AiProviders(AppProperties properties, RestClient.Builder restClientBuilder, ObjectMapper mapper,
                       LocalStore store) {
        this(properties, new RagProperties(false,
                        new RagProperties.Embedding("", "", "", 1024),
                        new RagProperties.Chroma("http://localhost:8000", "default_tenant",
                                "default_database", "ai_sources_content_v1"),
                        1800, 200, 12),
                restClientBuilder, mapper, store);
    }

    public boolean deepSeekConfigured() { return properties.deepseek().configured(); }
    public boolean qwenConfigured() { return properties.qwen().configured(); }
    public boolean wanxConfigured() { return properties.wanx().configured(); }
    public boolean embeddingConfigured() {
        return ragProperties.enabled() && ragProperties.embedding().configured();
    }

    public List<float[]> embedTexts(List<String> texts, String textType) {
        if (!embeddingConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMBEDDING_NOT_CONFIGURED",
                    "千问 Embedding 尚未配置。", false);
        }
        if (texts.isEmpty()) return List.of();
        List<float[]> embeddings = new ArrayList<>();
        for (int offset = 0; offset < texts.size(); offset += 10) {
            List<String> batch = texts.subList(offset, Math.min(offset + 10, texts.size()));
            Map<String, Object> request = Map.of(
                    "model", ragProperties.embedding().model(),
                    "input", Map.of("texts", batch),
                    "parameters", Map.of(
                            "text_type", textType,
                            "dimensions", ragProperties.embedding().dimensions(),
                            "output_type", "dense"
                    )
            );
            JsonNode response = postJson(ragProperties.embedding().baseUrl()
                            + "/api/v1/services/embeddings/text-embedding/text-embedding",
                    ragProperties.embedding().apiKey(), request, "EMBEDDING_REQUEST_FAILED");
            JsonNode values = response.path("output").path("embeddings");
            if (!values.isArray() || values.size() != batch.size()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "EMBEDDING_RESPONSE_INVALID",
                        "千问 Embedding 返回数量不匹配。", true);
            }
            values.forEach(value -> {
                JsonNode vector = value.path("embedding");
                if (!vector.isArray() || vector.size() != ragProperties.embedding().dimensions()) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "EMBEDDING_RESPONSE_INVALID",
                            "千问 Embedding 返回维度不匹配。", true);
                }
                float[] output = new float[vector.size()];
                for (int index = 0; index < vector.size(); index++) output[index] = (float) vector.get(index).asDouble();
                embeddings.add(output);
            });
        }
        return List.copyOf(embeddings);
    }

    public AiResult deepSeekJson(String systemPrompt, String userPrompt) {
        requireConfigured(properties.deepseek(), "DEEPSEEK_NOT_CONFIGURED");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", properties.deepseek().model());
        request.put("temperature", 0.2);
        request.put("max_tokens", 8192);
        request.put("response_format", Map.of("type", "json_object"));
        request.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        JsonNode response = postJson(properties.deepseek().baseUrl() + "/chat/completions",
                properties.deepseek().apiKey(), request, "DEEPSEEK_REQUEST_FAILED");
        String content = response.path("choices").path(0).path("message").path("content").asText();
        if (content.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DEEPSEEK_RESPONSE_EMPTY",
                    "DeepSeek 返回了空内容。", true);
        }
        try {
            mapper.readTree(content);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DEEPSEEK_JSON_INVALID",
                    "DeepSeek 返回了无效 JSON。", true);
        }
        JsonNode usage = response.path("usage");
        return new AiResult(content, new JobMetrics(0, "deepseek", properties.deepseek().model(),
                usage.path("prompt_tokens").asInt(), usage.path("completion_tokens").asInt(), 1));
    }

    public VisionResult analyzeImage(UUID packageId, DocumentParser.VisionInput input) {
        requireConfigured(properties.qwen(), "QWEN_VL_NOT_CONFIGURED");
        StoredAsset asset = store.asset(packageId, input.assetId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", "图片资源不存在。", false));
        try {
            byte[] bytes = Files.readAllBytes(store.resolveAsset(packageId, input.assetId()));
            String dataUrl = "data:" + asset.contentType() + ";base64," + Base64.getEncoder().encodeToString(bytes);
            Map<String, Object> request = Map.of(
                    "model", properties.qwen().model(),
                    "temperature", 0.1,
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", List.of(
                                    Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)),
                                    Map.of("type", "text", "text",
                                            "分析这张学习资料图片。只输出 JSON，字段为 title、visibleText、knowledgePoints、codeBlocks、uncertainItems、confidence。保留代码换行。")
                            )
                    ))
            );
            JsonNode response = postJson(properties.qwen().baseUrl() + "/chat/completions",
                    properties.qwen().apiKey(), request, "VISION_RESPONSE_INVALID");
            String content = response.path("choices").path(0).path("message").path("content").asText();
            content = stripFence(content);
            JsonNode json = mapper.readTree(content);
            return new VisionResult(
                    json.path("title").asText(input.label()),
                    json.path("visibleText").asText(),
                    strings(json.path("knowledgePoints")),
                    strings(json.path("codeBlocks")),
                    strings(json.path("uncertainItems")),
                    json.path("confidence").asDouble(0.8)
            );
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "VISION_RESPONSE_INVALID",
                    "千问 VL 返回内容无法解析。", true);
        }
    }

    public Optional<StoredAsset> generateIllustration(UUID packageId, String prompt) {
        if (!wanxConfigured()) return Optional.empty();
        String model = properties.wanx().model();
        String boundedPrompt = boundWanxPrompt(model, prompt);
        Map<String, Object> request = Map.of(
                "model", model,
                "input", Map.of("prompt", boundedPrompt),
                "parameters", Map.of("size", "1280*720", "n", 1, "watermark", false)
        );
        JsonNode submitted;
        try {
            submitted = restClientBuilder.build().post()
                    .uri(properties.wanx().baseUrl() + "/api/v1/services/aigc/text2image/image-synthesis")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.wanx().apiKey())
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .header("X-DashScope-Async", "enable")
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "IMAGE_GENERATION_FAILED",
                    "万相任务提交失败。", true, exception);
        }
        String taskId = submitted.path("output").path("task_id").asText();
        if (taskId.isBlank()) {
            return downloadInlineImage(packageId, submitted);
        }
        RestClient client = restClientBuilder.baseUrl(properties.wanx().baseUrl()).build();
        JsonNode lastStatus = submitted;
        for (int attempt = 0; attempt < 36; attempt++) {
            try {
                Thread.sleep(Duration.ofSeconds(5));
                JsonNode status = client.get()
                        .uri("/api/v1/tasks/{taskId}", taskId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.wanx().apiKey())
                        .retrieve()
                        .body(JsonNode.class);
                lastStatus = status;
                String state = status.path("output").path("task_status").asText();
                if ("SUCCEEDED".equals(state)) return downloadInlineImage(packageId, status);
                if ("FAILED".equals(state) || "CANCELED".equals(state) || "UNKNOWN".equals(state)) {
                    String code = status.path("code").asText(status.path("output").path("code").asText(state));
                    String message = status.path("message").asText(status.path("output").path("message").asText(""));
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "IMAGE_GENERATION_FAILED",
                            "万相插图生成失败：" + code + (message.isBlank() ? "" : "，" + message), true);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            } catch (ApiException exception) {
                throw exception;
            } catch (Exception exception) {
                log.warn("Wanx task poll failed taskId={} attempt={}", taskId, attempt + 1, exception);
            }
        }
        log.warn("Wanx task timed out taskId={} lastStatus={}", taskId, lastStatus);
        throw new ApiException(HttpStatus.BAD_GATEWAY, "IMAGE_GENERATION_FAILED",
                "万相插图生成超时，请稍后重试。", true);
    }

    private Optional<StoredAsset> downloadInlineImage(UUID packageId, JsonNode response) {
        String url = response.path("output").path("results").path(0).path("url").asText();
        if (url.isBlank()) {
            JsonNode choices = response.path("output").path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                url = choices.path(0).path("message").path("content").path(0).path("image").asText();
            }
        }
        if (url.isBlank()) return Optional.empty();
        byte[] bytes;
        try {
            bytes = restClientBuilder.build().get()
                    .uri(URI.create(url))
                    .retrieve()
                    .body(byte[].class);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "IMAGE_GENERATION_FAILED",
                    "万相图片下载失败。", true, exception);
        }
        if (bytes == null || bytes.length == 0) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "IMAGE_GENERATION_FAILED",
                    "万相图片下载失败：返回为空。", true);
        }
        if (bytes.length > 20 * 1024 * 1024) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "IMAGE_GENERATION_FAILED",
                    "万相图片下载失败：图片超过 20 MB。", true);
        }
        try {
            return Optional.of(store.storeBytes(packageId, "illustration.png", "image/png", bytes, "generated"));
        } catch (ApiException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_WRITE_FAILED",
                    "万相图片已生成，但保存到本地失败。", true, exception);
        }
    }

    private String boundWanxPrompt(String model, String prompt) {
        int limit = model.startsWith("wanx2.1") || model.startsWith("wan2.2") ? 500 : 1800;
        if (prompt.length() <= limit) return prompt;
        return prompt.substring(0, Math.max(0, limit - 20)) + "\n简洁技术插图。";
    }

    private JsonNode postJson(String url, String apiKey, Object request, String errorCode) {
        RestClient client = restClientBuilder.build();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return client.post().uri(url)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body(request)
                        .retrieve()
                        .body(JsonNode.class);
            } catch (RestClientResponseException exception) {
                boolean retryable = exception.getStatusCode().value() == 429
                        || exception.getStatusCode().value() == 502
                        || exception.getStatusCode().value() == 503
                        || exception.getStatusCode().value() == 504;
                if (retryable && attempt < 2) {
                    sleep(retryDelayMs(exception, attempt));
                    continue;
                }
                String code = exception.getStatusCode().value() == 429 ? "AI_RATE_LIMITED" : errorCode;
                throw new ApiException(HttpStatus.BAD_GATEWAY, code,
                        "外部 AI 服务调用失败（HTTP " + exception.getStatusCode().value() + "）。", retryable);
            } catch (Exception exception) {
                if (attempt == 0) {
                    sleep(0);
                    continue;
                }
                throw new ApiException(HttpStatus.BAD_GATEWAY, errorCode, "外部 AI 服务连接失败。", true, exception);
            }
        }
        throw new ApiException(HttpStatus.BAD_GATEWAY, errorCode, "外部 AI 服务连接失败。", true);
    }

    private long retryDelayMs(RestClientResponseException exception, int attempt) {
        if (exception.getStatusCode().value() == 429 && exception.getResponseHeaders() != null) {
            String retryAfter = exception.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
            if (retryAfter != null) {
                try {
                    return Math.max(0, Long.parseLong(retryAfter.strip()) * 1000);
                } catch (NumberFormatException ignored) {
                    return 2000;
                }
            }
            return 2000;
        }
        return attempt == 0 ? 1000 : 3000;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_REQUEST_INTERRUPTED",
                    "外部 AI 服务调用被中断。", true, exception);
        }
    }

    private void requireConfigured(AppProperties.Provider provider, String code) {
        if (!provider.configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, "AI 服务尚未配置。", false);
        }
    }

    private List<String> strings(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(item.asText()));
        return values;
    }

    private String stripFence(String value) {
        Matcher matcher = Pattern.compile("(?s)```(?:json)?\\s*(.*?)\\s*```").matcher(value);
        return matcher.find() ? matcher.group(1) : value;
    }
}
