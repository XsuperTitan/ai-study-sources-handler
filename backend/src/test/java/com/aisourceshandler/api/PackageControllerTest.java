package com.aisourceshandler.api;

import com.aisourceshandler.TestProperties;
import com.aisourceshandler.application.PackagePipeline;
import com.aisourceshandler.domain.Models.StoredAsset;
import com.aisourceshandler.infrastructure.AiProviders;
import com.aisourceshandler.infrastructure.LocalStore;
import com.aisourceshandler.infrastructure.VideoSubtitleExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.UUID;

import static com.aisourceshandler.domain.Models.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class PackageControllerTest {
    @TempDir
    Path temp;

    @Test
    void acceptsBrowserMultipartTextPartsWithoutContentType() throws Exception {
        LocalStore store = mock(LocalStore.class);
        PackagePipeline pipeline = mock(PackagePipeline.class);
        when(store.storeBytes(any(), anyString(), anyString(), any(), anyString()))
                .thenReturn(new StoredAsset(UUID.randomUUID(), "pasted-text.txt", "text/plain", 12,
                        "assets/text.txt"));
        when(pipeline.submit(any())).thenReturn(UUID.randomUUID());

        PackageController controller = new PackageController(
                store,
                pipeline,
                TestProperties.create(temp.toString(), "http://127.0.0.1"),
                mock(AiProviders.class),
                mock(VideoSubtitleExtractor.class)
        );
        MockMvc mvc = standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler())
                .build();

        mvc.perform(multipart("/api/v1/packages")
                        .part(textPart("title", "测试资料"))
                        .part(textPart("textContent", "线程池复用工作线程"))
                        .part(textPart("outputLanguage", "ZH_CN"))
                        .part(textPart("noteStyle", "INTERVIEW"))
                        .part(textPart("generateIllustration", "false")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.packageId").isNotEmpty());

        verify(pipeline).submit(any());
    }

    @Test
    void deletesFinishedPackage() throws Exception {
        UUID packageId = UUID.randomUUID();
        LocalStore store = mock(LocalStore.class);
        when(store.findPackage(packageId)).thenReturn(java.util.Optional.of(packageWithStatus(packageId, PackageStatus.READY)));
        MockMvc mvc = mvc(store);

        mvc.perform(delete("/api/v1/packages/{id}", packageId))
                .andExpect(status().isNoContent());

        verify(store).deletePackage(packageId);
    }

    @Test
    void blocksDeletingActivePackage() throws Exception {
        UUID packageId = UUID.randomUUID();
        LocalStore store = mock(LocalStore.class);
        when(store.findPackage(packageId)).thenReturn(java.util.Optional.of(packageWithStatus(packageId, PackageStatus.PROCESSING)));
        MockMvc mvc = mvc(store);

        mvc.perform(delete("/api/v1/packages/{id}", packageId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACKAGE_DELETE_BLOCKED"));

        verify(store, never()).deletePackage(any());
    }

    @Test
    void returnsNotFoundWhenDeletingMissingPackage() throws Exception {
        UUID packageId = UUID.randomUUID();
        LocalStore store = mock(LocalStore.class);
        when(store.findPackage(packageId)).thenReturn(java.util.Optional.empty());
        MockMvc mvc = mvc(store);

        mvc.perform(delete("/api/v1/packages/{id}", packageId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PACKAGE_NOT_FOUND"));
    }

    private MockMvc mvc(LocalStore store) {
        return standaloneSetup(new PackageController(
                store,
                mock(PackagePipeline.class),
                TestProperties.create(temp.toString(), "http://127.0.0.1"),
                mock(AiProviders.class),
                mock(VideoSubtitleExtractor.class)
        )).setControllerAdvice(new ApiErrorHandler()).build();
    }

    private SourcePackage packageWithStatus(UUID id, PackageStatus status) {
        return new SourcePackage(1, id, "local-user", "Test", PackageType.MIXED,
                status, JobStage.PARSE, 10, PackageOptions.defaults(),
                new ArrayList<>(), new ArrayList<>(), OffsetDateTime.now(), OffsetDateTime.now());
    }

    private MockPart textPart(String name, String value) {
        return new MockPart(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
