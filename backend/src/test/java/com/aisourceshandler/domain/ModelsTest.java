package com.aisourceshandler.domain;

import com.aisourceshandler.domain.Models.*;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ModelsTest {
    @Test
    void packageStateTransitionKeepsIdentityAndUpdatesProgress() {
        UUID id = UUID.randomUUID();
        SourcePackage value = new SourcePackage(1, id, "local-user", "Title", PackageType.MIXED,
                PackageStatus.QUEUED, JobStage.PARSE, 10, PackageOptions.defaults(),
                new ArrayList<>(), new ArrayList<>(), OffsetDateTime.now(), OffsetDateTime.now());

        SourcePackage changed = value.withState(PackageStatus.PROCESSING, JobStage.VISION, 30, value.warnings());

        assertThat(changed.id()).isEqualTo(id);
        assertThat(changed.status()).isEqualTo(PackageStatus.PROCESSING);
        assertThat(changed.currentStage()).isEqualTo(JobStage.VISION);
        assertThat(changed.progress()).isEqualTo(30);
    }
}

