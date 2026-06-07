package com.aisourceshandler.infrastructure;

import com.aisourceshandler.TestProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

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
}
