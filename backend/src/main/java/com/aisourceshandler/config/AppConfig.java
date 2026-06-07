package com.aisourceshandler.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AppConfig {
    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule()).findAndRegisterModules();
    }

    @Bean
    RestClient.Builder restClientBuilder() {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(20));
        requestFactory.setReadTimeout(Duration.ofSeconds(120));
        return RestClient.builder().requestFactory(requestFactory);
    }

    @Bean
    Path storageRoot(AppProperties properties) {
        return Path.of(properties.storageRoot()).toAbsolutePath().normalize();
    }

    @Bean("jobExecutor")
    Executor jobExecutor(AppProperties properties) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.jobs().corePoolSize());
        executor.setMaxPoolSize(properties.jobs().maxPoolSize());
        executor.setQueueCapacity(properties.jobs().queueCapacity());
        executor.setThreadNamePrefix("source-job-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
