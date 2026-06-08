package com.aisourceshandler.infrastructure;

import com.aisourceshandler.TestProperties;
import com.aisourceshandler.api.ApiException;
import com.aisourceshandler.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiProvidersTest {
    @TempDir
    Path temp;

    private WireMockServer server;

    @BeforeEach
    void start() {
        server = new WireMockServer(0);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void readsDeepSeekJsonAndUsage() {
        server.stubFor(post("/chat/completions").willReturn(okJson("""
                {
                  "choices":[{"message":{"content":"{\\"overview\\":\\"ok\\",\\"sections\\":[]}"}}],
                  "usage":{"prompt_tokens":12,"completion_tokens":8}
                }
                """)));
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        LocalStore store = new LocalStore(temp, mapper);
        RestClient.Builder restClient = RestClient.builder()
                .requestFactory(new SimpleClientHttpRequestFactory());
        AiProviders providers = new AiProviders(
                TestProperties.create(temp.toString(), "http://127.0.0.1:" + server.port()),
                restClient, mapper, store);

        AiProviders.AiResult result = providers.deepSeekJson("output json", "content");

        assertThat(result.content()).contains("\"overview\":\"ok\"");
        assertThat(result.metrics().inputTokens()).isEqualTo(12);
        server.verify(postRequestedFor(urlEqualTo("/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer test-key")));
    }

    @Test
    void retriesDeepSeekAfterRateLimit() {
        server.stubFor(post("/chat/completions")
                .inScenario("rate-limit")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
                .willSetStateTo("ok"));
        server.stubFor(post("/chat/completions")
                .inScenario("rate-limit")
                .whenScenarioStateIs("ok")
                .willReturn(okJson("""
                        {
                          "choices":[{"message":{"content":"{\\"overview\\":\\"ok\\"}"}}],
                          "usage":{"prompt_tokens":1,"completion_tokens":2}
                        }
                        """)));
        AiProviders providers = providers(TestProperties.create(temp.toString(), serverBaseUrl()));

        AiProviders.AiResult result = providers.deepSeekJson("output json", "content");

        assertThat(result.content()).contains("\"overview\":\"ok\"");
        server.verify(2, postRequestedFor(urlEqualTo("/chat/completions")));
    }

    @Test
    void rejectsEmptyDeepSeekContent() {
        server.stubFor(post("/chat/completions").willReturn(okJson("""
                {"choices":[{"message":{"content":""}}],"usage":{}}
                """)));
        AiProviders providers = providers(TestProperties.create(temp.toString(), serverBaseUrl()));

        assertThatThrownBy(() -> providers.deepSeekJson("output json", "content"))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo("DEEPSEEK_RESPONSE_EMPTY");
    }

    @Test
    void rejectsInvalidDeepSeekJson() {
        server.stubFor(post("/chat/completions").willReturn(okJson("""
                {"choices":[{"message":{"content":"not json"}}],"usage":{}}
                """)));
        AiProviders providers = providers(TestProperties.create(temp.toString(), serverBaseUrl()));

        assertThatThrownBy(() -> providers.deepSeekJson("output json", "content"))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo("DEEPSEEK_JSON_INVALID");
    }

    @Test
    void rejectsInvalidQwenVisionResponse() {
        server.stubFor(post("/chat/completions").willReturn(okJson("""
                {"choices":[{"message":{"content":"not json"}}]}
                """)));
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        LocalStore store = new LocalStore(temp, mapper);
        UUID packageId = UUID.randomUUID();
        var asset = store.storeBytes(packageId, "shot.png", "image/png",
                "not-a-real-image".getBytes(StandardCharsets.UTF_8), "original");
        AiProviders providers = providers(propertiesWithQwen(serverBaseUrl()), mapper, store);

        assertThatThrownBy(() -> providers.analyzeImage(packageId,
                        new DocumentParser.VisionInput(UUID.randomUUID(), asset.id(), "shot.png")))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo("VISION_RESPONSE_INVALID");
    }

    private String serverBaseUrl() {
        return "http://127.0.0.1:" + server.port();
    }

    private AiProviders providers(AppProperties properties) {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        return providers(properties, mapper, new LocalStore(temp, mapper));
    }

    private AiProviders providers(AppProperties properties, ObjectMapper mapper, LocalStore store) {
        RestClient.Builder restClient = RestClient.builder()
                .requestFactory(new SimpleClientHttpRequestFactory());
        return new AiProviders(properties, restClient, mapper, store);
    }

    private AppProperties propertiesWithQwen(String baseUrl) {
        return new AppProperties(
                false,
                temp.toString(),
                new AppProperties.Upload(20, 104857600, 10485760, 2097152, 300),
                new AppProperties.Pdf(12, 144),
                new AppProperties.Jobs(1, 2, 8),
                new AppProperties.Provider("test-key", baseUrl, "deepseek-chat"),
                new AppProperties.Provider("test-key", baseUrl, "qwen-vl-max"),
                new AppProperties.Provider("", baseUrl, "wanx"),
                new AppProperties.Video("yt-dlp", 5, "")
        );
    }
}
