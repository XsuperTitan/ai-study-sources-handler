package com.aisourceshandler.application;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class StartupRecovery {
    private final PackagePipeline pipeline;

    public StartupRecovery(PackagePipeline pipeline) {
        this.pipeline = pipeline;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        pipeline.recoverInterrupted();
    }
}

