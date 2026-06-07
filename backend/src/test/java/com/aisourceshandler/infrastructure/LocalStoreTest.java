package com.aisourceshandler.infrastructure;

import com.aisourceshandler.domain.Models.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LocalStoreTest {
    @TempDir
    Path temp;

    @Test
    void atomicallyPersistsAndReadsPackage() {
        LocalStore store = new LocalStore(temp, new ObjectMapper().registerModule(new JavaTimeModule()));
        UUID id = UUID.randomUUID();
        SourcePackage value = new SourcePackage(1, id, "local-user", "Test", PackageType.MIXED,
                PackageStatus.QUEUED, JobStage.PARSE, 10, PackageOptions.defaults(),
                new ArrayList<>(), new ArrayList<>(), OffsetDateTime.now(), OffsetDateTime.now());

        store.savePackage(value);

        SourcePackage loaded = store.findPackage(id).orElseThrow();
        assertThat(loaded.id()).isEqualTo(value.id());
        assertThat(loaded.createdAt().toInstant()).isEqualTo(value.createdAt().toInstant());
        assertThat(store.findAllPackages()).extracting(SourcePackage::id).containsExactly(id);
    }

    @Test
    void storesUtf8Output() {
        LocalStore store = new LocalStore(temp, new ObjectMapper().registerModule(new JavaTimeModule()));
        UUID id = UUID.randomUUID();
        store.writeText(id, "outputs/note.md", "# 中文笔记");

        assertThat(store.readText(id, "outputs/note.md")).isEqualTo("# 中文笔记");
    }

    @Test
    void deletesEntirePackageDirectory() throws Exception {
        LocalStore store = new LocalStore(temp, new ObjectMapper().registerModule(new JavaTimeModule()));
        UUID id = UUID.randomUUID();
        SourcePackage value = new SourcePackage(1, id, "local-user", "Delete Me", PackageType.MIXED,
                PackageStatus.READY, JobStage.ILLUSTRATION, 100, PackageOptions.defaults(),
                new ArrayList<>(), new ArrayList<>(), OffsetDateTime.now(), OffsetDateTime.now());
        store.savePackage(value);
        store.writeText(id, "outputs/note.md", "# note");
        store.writeText(id, "assets/generated/image.png", "image");

        Path root = store.packageRoot(id);
        assertThat(Files.exists(root)).isTrue();

        store.deletePackage(id);

        assertThat(Files.exists(root)).isFalse();
        assertThat(store.findPackage(id)).isEmpty();
    }
}
