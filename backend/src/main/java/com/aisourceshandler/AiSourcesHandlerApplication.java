package com.aisourceshandler;

import com.aisourceshandler.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class AiSourcesHandlerApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiSourcesHandlerApplication.class, args);
    }
}

