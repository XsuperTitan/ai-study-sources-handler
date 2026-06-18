package com.aisourceshandler.api;

import com.aisourceshandler.TestProperties;
import com.aisourceshandler.application.PackagePipeline;
import com.aisourceshandler.application.PackagePipeline.IllustrationVariant;
import com.aisourceshandler.domain.Models.StoredAsset;
import com.aisourceshandler.infrastructure.AiProviders;
import com.aisourceshandler.infrastructure.LocalStore;
import com.aisourceshandler.infrastructure.VideoSubtitleExtractor;
import com.aisourceshandler.learning.LearningService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.aisourceshandler.domain.Models.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
                mock(VideoSubtitleExtractor.class),
                mock(LearningService.class)
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
    void defaultsMixedPackageIllustrationToEnabled() throws Exception {
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
                mock(VideoSubtitleExtractor.class),
                mock(LearningService.class)
        );
        MockMvc mvc = standaloneSetup(controller)
                .setControllerAdvice(new ApiErrorHandler())
                .build();

        mvc.perform(multipart("/api/v1/packages")
                        .part(textPart("title", "默认配置"))
                        .part(textPart("textContent", "线程池复用工作线程")))
                .andExpect(status().isAccepted());

        ArgumentCaptor<SourcePackage> captor = ArgumentCaptor.forClass(SourcePackage.class);
        verify(store, atLeastOnce()).savePackage(captor.capture());
        assertThat(captor.getAllValues().getFirst().options().generateIllustration()).isTrue();
        assertThat(captor.getAllValues().getFirst().options().autoTitle()).isFalse();
    }

    @Test
    void enablesAutoTitleWhenMixedPackageTitleIsBlank() throws Exception {
        LocalStore store = mock(LocalStore.class);
        PackagePipeline pipeline = mock(PackagePipeline.class);
        when(store.storeBytes(any(), anyString(), anyString(), any(), anyString()))
                .thenReturn(new StoredAsset(UUID.randomUUID(), "pasted-text.txt", "text/plain", 12,
                        "assets/text.txt"));
        when(pipeline.submit(any())).thenReturn(UUID.randomUUID());
        MockMvc mvc = standaloneSetup(new PackageController(
                store,
                pipeline,
                TestProperties.create(temp.toString(), "http://127.0.0.1"),
                mock(AiProviders.class),
                mock(VideoSubtitleExtractor.class),
                mock(LearningService.class)
        )).setControllerAdvice(new ApiErrorHandler()).build();

        mvc.perform(multipart("/api/v1/packages")
                        .part(textPart("textContent", "线程池复用工作线程")))
                .andExpect(status().isAccepted());

        ArgumentCaptor<SourcePackage> captor = ArgumentCaptor.forClass(SourcePackage.class);
        verify(store, atLeastOnce()).savePackage(captor.capture());
        assertThat(captor.getAllValues().getFirst().options().autoTitle()).isTrue();
    }

    @Test
    void defaultsVideoPackageIllustrationToEnabled() throws Exception {
        LocalStore store = mock(LocalStore.class);
        PackagePipeline pipeline = mock(PackagePipeline.class);
        when(pipeline.submit(any())).thenReturn(UUID.randomUUID());
        MockMvc mvc = standaloneSetup(new PackageController(
                store,
                pipeline,
                TestProperties.create(temp.toString(), "http://127.0.0.1"),
                mock(AiProviders.class),
                mock(VideoSubtitleExtractor.class),
                mock(LearningService.class)
        )).setControllerAdvice(new ApiErrorHandler()).build();

        mvc.perform(post("/api/v1/video-packages")
                        .contentType("application/json")
                        .content("""
                                {"url":"https://www.bilibili.com/video/BV1xx411c7mD","title":"视频资料"}
                                """))
                .andExpect(status().isAccepted());

        ArgumentCaptor<SourcePackage> captor = ArgumentCaptor.forClass(SourcePackage.class);
        verify(store, atLeastOnce()).savePackage(captor.capture());
        assertThat(captor.getAllValues().getFirst().options().generateIllustration()).isTrue();
        assertThat(captor.getAllValues().getFirst().options().autoTitle()).isFalse();
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

    @Test
    void filtersPackagesByStatusTypeAndTitle() throws Exception {
        SourcePackage ready = packageWithStatus(UUID.randomUUID(), PackageStatus.READY);
        SourcePackage failed = packageWithStatus(UUID.randomUUID(), PackageStatus.FAILED);
        failed = new SourcePackage(failed.schemaVersion(), failed.id(), failed.ownerId(), "Bilibili 资料",
                PackageType.VIDEO, failed.status(), failed.currentStage(), failed.progress(), failed.options(),
                failed.sourceItemIds(), failed.warnings(), failed.createdAt(), failed.updatedAt());
        LocalStore store = mock(LocalStore.class);
        when(store.findAllPackages()).thenReturn(List.of(ready, failed));
        MockMvc mvc = mvc(store);

        mvc.perform(get("/api/v1/packages")
                        .param("status", "FAILED")
                        .param("type", "VIDEO")
                        .param("q", "bili"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(failed.id().toString()))
                .andExpect(jsonPath("$[1]").doesNotExist());
    }

    @Test
    void filtersDefaultAndMasteredPackageViews() throws Exception {
        SourcePackage mastered = packageWithStatus(UUID.randomUUID(), PackageStatus.READY);
        SourcePackage active = packageWithStatus(UUID.randomUUID(), PackageStatus.READY);
        LocalStore store = mock(LocalStore.class);
        when(store.findAllPackages()).thenReturn(List.of(mastered, active));
        LearningService learning = mock(LearningService.class);
        when(learning.masteryFor(eq("local-user"), anyCollection())).thenReturn(Map.of(
                mastered.id(), new com.aisourceshandler.learning.LearningModels.MasteryView(
                        mastered.id(), true, OffsetDateTime.now(), OffsetDateTime.now())
        ));
        MockMvc mvc = mvc(store, learning);

        mvc.perform(get("/api/v1/packages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(active.id().toString()))
                .andExpect(jsonPath("$[1]").doesNotExist());

        mvc.perform(get("/api/v1/packages").param("mastery", "MASTERED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(mastered.id().toString()))
                .andExpect(jsonPath("$[0].mastery.mastered").value(true));
    }

    @Test
    void updatesMasteryForReadyPackage() throws Exception {
        UUID packageId = UUID.randomUUID();
        SourcePackage sourcePackage = packageWithStatus(packageId, PackageStatus.READY);
        LocalStore store = mock(LocalStore.class);
        when(store.findPackage(packageId)).thenReturn(Optional.of(sourcePackage));
        LearningService learning = mock(LearningService.class);
        when(learning.setMastery(eq(sourcePackage), anyList(), eq(true)))
                .thenReturn(new com.aisourceshandler.learning.LearningModels.MasteryView(
                        packageId, true, OffsetDateTime.now(), OffsetDateTime.now()));
        MockMvc mvc = mvc(store, learning);

        mvc.perform(put("/api/v1/packages/{id}/mastery", packageId)
                        .contentType("application/json")
                        .content("{\"mastered\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packageId").value(packageId.toString()))
                .andExpect(jsonPath("$.mastered").value(true));
    }

    @Test
    void blocksMasteryForProcessingPackage() throws Exception {
        UUID packageId = UUID.randomUUID();
        LocalStore store = mock(LocalStore.class);
        when(store.findPackage(packageId))
                .thenReturn(Optional.of(packageWithStatus(packageId, PackageStatus.PROCESSING)));
        LearningService learning = mock(LearningService.class);
        MockMvc mvc = mvc(store, learning);

        mvc.perform(put("/api/v1/packages/{id}/mastery", packageId)
                        .contentType("application/json")
                        .content("{\"mastered\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PACKAGE_NOT_READY_FOR_MASTERY"));

        verify(learning, never()).setMastery(any(), anyList(), anyBoolean());
    }

    @Test
    void packageListIncludesIllustrationAndPrioritizedCoverKeywords() throws Exception {
        UUID packageId = UUID.randomUUID();
        UUID illustrationId = UUID.randomUUID();
        UUID whiteboardId = UUID.randomUUID();
        SourcePackage sourcePackage = packageWithStatus(packageId, PackageStatus.READY);
        LocalStore store = mock(LocalStore.class);
        when(store.findAllPackages()).thenReturn(List.of(sourcePackage));
        when(store.readJsonOutput(packageId, "outputs/note.json", NoteOutput.class))
                .thenReturn(Optional.of(new NoteOutput(1, "Note", "outputs/note.md", 1, List.of(),
                        null, null, illustrationId, whiteboardId, "deepseek-chat", "note-v1", OffsetDateTime.now())));
        when(store.readJsonOutput(packageId, "outputs/report.json", StudyGuide.class))
                .thenReturn(Optional.of(studyGuide(
                        List.of("线程复用", "任务队列"),
                        List.of("任务队列", "拒绝策略"),
                        List.of("理解执行流程"))));
        MockMvc mvc = mvc(store);

        mvc.perform(get("/api/v1/packages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].cover.imageUrl")
                        .value("/api/v1/packages/" + packageId + "/assets/" + illustrationId))
                .andExpect(jsonPath("$[0].cover.visualVariants.classic.ready").value(true))
                .andExpect(jsonPath("$[0].cover.visualVariants.classic.imageUrl")
                        .value("/api/v1/packages/" + packageId + "/assets/" + illustrationId))
                .andExpect(jsonPath("$[0].cover.visualVariants.whiteboard.ready").value(true))
                .andExpect(jsonPath("$[0].cover.visualVariants.whiteboard.imageUrl")
                        .value("/api/v1/packages/" + packageId + "/assets/" + whiteboardId))
                .andExpect(jsonPath("$[0].cover.keywords[0]").value("线程复用"))
                .andExpect(jsonPath("$[0].cover.keywords[1]").value("任务队列"))
                .andExpect(jsonPath("$[0].cover.keywords[2]").value("拒绝策略"));
    }

    @Test
    void returnsPackageSummaryWhenRequestedIllustrationAlreadyExists() throws Exception {
        UUID packageId = UUID.randomUUID();
        UUID whiteboardId = UUID.randomUUID();
        SourcePackage sourcePackage = packageWithStatus(packageId, PackageStatus.READY);
        LocalStore store = mock(LocalStore.class);
        PackagePipeline pipeline = mock(PackagePipeline.class);
        when(store.findPackage(packageId)).thenReturn(Optional.of(sourcePackage));
        when(store.readJsonOutput(packageId, "outputs/note.json", NoteOutput.class))
                .thenReturn(Optional.of(new NoteOutput(1, "Note", "outputs/note.md", 1, List.of(),
                        null, null, null, whiteboardId, "deepseek-chat", "note-v1", OffsetDateTime.now())));
        MockMvc mvc = mvc(store, pipeline, learningService());

        mvc.perform(post("/api/v1/packages/{id}/illustrations/whiteboard/generate", packageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cover.visualVariants.whiteboard.ready").value(true))
                .andExpect(jsonPath("$.cover.visualVariants.whiteboard.imageUrl")
                        .value("/api/v1/packages/" + packageId + "/assets/" + whiteboardId));

        verify(pipeline, never()).submitIllustration(any(), any());
    }

    @Test
    void queuesMissingIllustrationVariant() throws Exception {
        UUID packageId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        SourcePackage sourcePackage = packageWithStatus(packageId, PackageStatus.READY);
        LocalStore store = mock(LocalStore.class);
        PackagePipeline pipeline = mock(PackagePipeline.class);
        when(store.findPackage(packageId)).thenReturn(Optional.of(sourcePackage));
        when(store.readJsonOutput(packageId, "outputs/note.json", NoteOutput.class))
                .thenReturn(Optional.of(new NoteOutput(1, "Note", "outputs/note.md", 1, List.of(),
                        null, null, UUID.randomUUID(), null, "deepseek-chat", "note-v1", OffsetDateTime.now())));
        when(pipeline.submitIllustration(packageId, IllustrationVariant.WHITEBOARD)).thenReturn(jobId);
        MockMvc mvc = mvc(store, pipeline, learningService());

        mvc.perform(post("/api/v1/packages/{id}/illustrations/whiteboard/generate", packageId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.packageId").value(packageId.toString()))
                .andExpect(jsonPath("$.rootJobId").value(jobId.toString()));

        verify(pipeline).submitIllustration(packageId, IllustrationVariant.WHITEBOARD);
    }

    @Test
    void rejectsUnknownIllustrationVariant() throws Exception {
        UUID packageId = UUID.randomUUID();
        LocalStore store = mock(LocalStore.class);
        when(store.findPackage(packageId)).thenReturn(Optional.of(packageWithStatus(packageId, PackageStatus.READY)));
        MockMvc mvc = mvc(store);

        mvc.perform(post("/api/v1/packages/{id}/illustrations/sketch/generate", packageId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_ILLUSTRATION_VARIANT"));
    }

    @Test
    void returnsSourceItemsWithAssetMetadata() throws Exception {
        UUID packageId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        LocalStore store = mock(LocalStore.class);
        when(store.findPackage(packageId)).thenReturn(Optional.of(packageWithStatus(packageId, PackageStatus.READY)));
        SourceItem item = new SourceItem(UUID.randomUUID(), packageId, SourceKind.IMAGE, "shot.png",
                assetId, 0, Map.of("size", 4));
        StoredAsset asset = new StoredAsset(assetId, "shot.png", "image/png", 4, "packages/x/shot.png");
        when(store.sourceItems(packageId)).thenReturn(List.of(item));
        when(store.packageAssets(packageId)).thenReturn(List.of(asset));
        when(store.asset(packageId, assetId)).thenReturn(Optional.of(asset));
        MockMvc mvc = mvc(store);

        mvc.perform(get("/api/v1/packages/{id}/sources", packageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].assetUrl").value("/api/v1/packages/" + packageId + "/assets/" + assetId))
                .andExpect(jsonPath("$.items[0].contentType").value("image/png"))
                .andExpect(jsonPath("$.assets[0].size").value(4));
    }

    @Test
    void packageDetailIncludesIllustrationAssetUrlWhenReady() throws Exception {
        UUID packageId = UUID.randomUUID();
        UUID illustrationId = UUID.randomUUID();
        LocalStore store = mock(LocalStore.class);
        when(store.findPackage(packageId)).thenReturn(Optional.of(packageWithStatus(packageId, PackageStatus.READY)));
        when(store.readJsonOutput(packageId, "outputs/note.json", NoteOutput.class))
                .thenReturn(Optional.of(new NoteOutput(1, "Note", "outputs/note.md", 1, List.of(),
                        "知识流程图", "outputs/knowledge-flow.mmd", illustrationId,
                        null, "deepseek-chat", "note-v1", OffsetDateTime.now())));
        when(store.readJsonOutput(packageId, "outputs/report.json", StudyGuide.class)).thenReturn(Optional.empty());
        MockMvc mvc = mvc(store);

        mvc.perform(get("/api/v1/packages/{id}", packageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outputs.diagramReady").value(true))
                .andExpect(jsonPath("$.outputs.diagramTitle").value("知识流程图"))
                .andExpect(jsonPath("$.outputs.diagramUrl").value("/api/v1/packages/" + packageId + "/diagram"))
                .andExpect(jsonPath("$.outputs.illustrationReady").value(true))
                .andExpect(jsonPath("$.outputs.illustrationAssetUrl")
                        .value("/api/v1/packages/" + packageId + "/assets/" + illustrationId));
    }

    @Test
    void returnsKnowledgeDiagramAsPlainText() throws Exception {
        UUID packageId = UUID.randomUUID();
        LocalStore store = mock(LocalStore.class);
        when(store.findPackage(packageId)).thenReturn(Optional.of(packageWithStatus(packageId, PackageStatus.READY)));
        when(store.readJsonOutput(packageId, "outputs/note.json", NoteOutput.class))
                .thenReturn(Optional.of(new NoteOutput(1, "Note", "outputs/note.md", 1, List.of(),
                        "知识流程图", "outputs/knowledge-flow.mmd", null,
                        null, "deepseek-chat", "note-v1", OffsetDateTime.now())));
        when(store.readText(packageId, "outputs/knowledge-flow.mmd")).thenReturn("flowchart TB\nA[概念] --> B[实践]");
        MockMvc mvc = mvc(store);

        mvc.perform(get("/api/v1/packages/{id}/diagram", packageId))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/plain;charset=UTF-8"))
                .andExpect(content().string("flowchart TB\nA[概念] --> B[实践]"));
    }

    @Test
    void returnsConflictWhenKnowledgeDiagramIsMissing() throws Exception {
        UUID packageId = UUID.randomUUID();
        LocalStore store = mock(LocalStore.class);
        when(store.findPackage(packageId)).thenReturn(Optional.of(packageWithStatus(packageId, PackageStatus.READY)));
        when(store.readJsonOutput(packageId, "outputs/note.json", NoteOutput.class)).thenReturn(Optional.empty());
        MockMvc mvc = mvc(store);

        mvc.perform(get("/api/v1/packages/{id}/diagram", packageId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DIAGRAM_NOT_READY"));
    }

    @Test
    void downloadsNoteAndReportAsMarkdownAttachments() throws Exception {
        UUID packageId = UUID.randomUUID();
        LocalStore store = mock(LocalStore.class);
        when(store.findPackage(packageId)).thenReturn(Optional.of(new SourcePackage(
                1, packageId, "local-user", "Java 并发/线程池", PackageType.MIXED,
                PackageStatus.READY, JobStage.ILLUSTRATION, 100, PackageOptions.defaults(),
                new ArrayList<>(), new ArrayList<>(), OffsetDateTime.now(), OffsetDateTime.now())));
        when(store.readText(packageId, "outputs/note.md")).thenReturn("# AI 笔记");
        when(store.readText(packageId, "outputs/report.md")).thenReturn("# 学习指南");
        MockMvc mvc = mvc(store);

        mvc.perform(get("/api/v1/packages/{id}/note.md", packageId))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/markdown;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("ai-note.md")))
                .andExpect(content().string("# AI 笔记"));

        mvc.perform(get("/api/v1/packages/{id}/report.md", packageId))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/markdown;charset=UTF-8"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("attachment")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, containsString("study-guide.md")))
                .andExpect(content().string("# 学习指南"));
    }

    @Test
    void returnsConflictWhenMarkdownDownloadIsNotReady() throws Exception {
        UUID packageId = UUID.randomUUID();
        LocalStore store = mock(LocalStore.class);
        when(store.findPackage(packageId)).thenReturn(Optional.of(packageWithStatus(packageId, PackageStatus.PROCESSING)));
        when(store.readText(packageId, "outputs/note.md"))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.CONFLICT,
                        "OUTPUT_NOT_READY", "结果尚未生成。", true));
        MockMvc mvc = mvc(store);

        mvc.perform(get("/api/v1/packages/{id}/note.md", packageId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OUTPUT_NOT_READY"));
    }

    private MockMvc mvc(LocalStore store) {
        return mvc(store, learningService());
    }

    private MockMvc mvc(LocalStore store, LearningService learning) {
        return mvc(store, mock(PackagePipeline.class), learning);
    }

    private MockMvc mvc(LocalStore store, PackagePipeline pipeline, LearningService learning) {
        return standaloneSetup(new PackageController(
                store,
                pipeline,
                TestProperties.create(temp.toString(), "http://127.0.0.1"),
                mock(AiProviders.class),
                mock(VideoSubtitleExtractor.class),
                learning
        )).setControllerAdvice(new ApiErrorHandler()).build();
    }

    private LearningService learningService() {
        LearningService learning = mock(LearningService.class);
        when(learning.masteryFor(anyString(), anyCollection())).thenReturn(Map.of());
        when(learning.masteryFor(anyString(), any(UUID.class)))
                .thenAnswer(invocation -> com.aisourceshandler.learning.LearningModels.MasteryView
                        .active(invocation.getArgument(1)));
        return learning;
    }

    private SourcePackage packageWithStatus(UUID id, PackageStatus status) {
        return new SourcePackage(1, id, "local-user", "Test", PackageType.MIXED,
                status, JobStage.PARSE, 10, PackageOptions.defaults(),
                new ArrayList<>(), new ArrayList<>(), OffsetDateTime.now(), OffsetDateTime.now());
    }

    private StudyGuide studyGuide(List<String> core, List<String> keyPoints, List<String> objectives) {
        return new StudyGuide(1, "overview", List.of(), "入门", 30, List.of(), objectives,
                List.of(), core, keyPoints, List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
    }

    private MockPart textPart(String name, String value) {
        return new MockPart(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
