package com.aisourceshandler.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class StorageHealthIndicator implements HealthIndicator {
    private final Path storageRoot;

    public StorageHealthIndicator(Path storageRoot) {
        this.storageRoot = storageRoot;
    }

    @Override
    public Health health() {
        boolean writable = Files.exists(storageRoot) && Files.isWritable(storageRoot);
        return writable
                ? Health.up().withDetail("path", storageRoot.toString()).build()
                : Health.down().withDetail("path", storageRoot.toString()).build();
    }
}
