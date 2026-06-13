package com.aisourceshandler;

import com.aisourceshandler.config.AppProperties;
import com.aisourceshandler.config.RagProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
@EnableConfigurationProperties({AppProperties.class, RagProperties.class})
public class AiSourcesHandlerApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiSourcesHandlerApplication.class, args);
    }
}
