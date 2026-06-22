package com.aisourceshandler.application;

import com.aisourceshandler.api.ApiException;
import com.aisourceshandler.domain.Models.*;
import com.aisourceshandler.infrastructure.*;
import com.aisourceshandler.rag.RagService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Service
public class PackagePipeline {
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[\\[cite:(blk_[a-f0-9]{32})]]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE_FENCE_PATTERN = Pattern.compile("(?s)```.*?```");
    private static final Pattern MERMAID_NODE_PATTERN = Pattern.compile("\\b([A-Za-z][A-Za-z0-9_]*)\\s*(?:\\[|\\(|\\{)");
    private static final Pattern MERMAID_LABEL_PATTERN = Pattern.compile("(?:\\[|\\(|\\{)\\\"?([^\\]\\)\\}\\\"]+)\\\"?(?:\\]|\\)|\\})");
    private static final List<JobStage> ORDER = List.of(
            JobStage.PARSE, JobStage.VISION, JobStage.DIGEST, JobStage.NOTE, JobStage.REPORT,
            JobStage.RAG_INDEX, JobStage.ILLUSTRATION);
    public enum IllustrationVariant {
        ABSTRACT("abstract"),
        CLASSIC("classic"),
        WHITEBOARD("whiteboard");

        private final String wireName;

        IllustrationVariant(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static IllustrationVariant fromWireName(String value) {
            for (IllustrationVariant variant : values()) {
                if (variant.wireName.equalsIgnoreCase(value)) return variant;
            }
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ILLUSTRATION_VARIANT",
                    "Unsupported illustration variant: " + value, false);
        }
    }

    private final LocalStore store;
    private final DocumentParser parser;
    private final AiProviders ai;
    private final VideoSubtitleExtractor video;
    private final ObjectMapper mapper;
    private final Executor executor;
    private final RagService rag;

    public PackagePipeline(LocalStore store, DocumentParser parser, AiProviders ai, VideoSubtitleExtractor video,
                           ObjectMapper mapper, @Qualifier("jobExecutor") Executor executor, RagService rag) {
        this.store = store;
        this.parser = parser;
        this.ai = ai;
        this.video = video;
        this.mapper = mapper;
        this.executor = executor;
        this.rag = rag;
    }

    public UUID submit(UUID packageId) {
        return submit(packageId, JobStage.PARSE, 1);
    }

    public UUID retry(UUID failedJobId) {
        ProcessingJob failed = store.findJob(failedJobId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "任务不存在。", false));
        if (failed.status() != JobStatus.FAILED && failed.status() != JobStatus.INTERRUPTED) {
            throw new ApiException(HttpStatus.CONFLICT, "JOB_NOT_RETRYABLE", "只有失败或中断任务可以重试。", false);
        }
        if (!failed.retryable()) {
            throw new ApiException(HttpStatus.CONFLICT, "JOB_NOT_RETRYABLE", "该错误不可重试。", false);
        }
        SourcePackage sourcePackage = requiredPackage(failed.packageId());
        List<String> warnings = sourcePackage.warnings().stream()
                .filter(warning -> !Objects.equals(warning, failed.errorMessage())
                        && (failed.errorMessage() == null || !warning.endsWith(failed.errorMessage())))
                .toList();
        store.savePackage(sourcePackage.withState(sourcePackage.status(), sourcePackage.currentStage(),
                sourcePackage.progress(), warnings));
        return submit(failed.packageId(), failed.stage(), failed.attempt() + 1);
    }

    public UUID submitIllustration(UUID packageId, IllustrationVariant variant) {
        return submitIllustration(packageId, variant, false);
    }

    public UUID submitIllustration(UUID packageId, IllustrationVariant variant, boolean replace) {
        SourcePackage sourcePackage = requiredPackage(packageId);
        if (sourcePackage.status() != PackageStatus.READY && sourcePackage.status() != PackageStatus.PARTIALLY_READY) {
            throw new ApiException(HttpStatus.CONFLICT, "PACKAGE_NOT_READY_FOR_ILLUSTRATION",
                    "Package is not ready for illustration generation.", false);
        }
        NoteOutput noteOutput = store.readJsonOutput(packageId, "outputs/note.json", NoteOutput.class)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "NOTE_NOT_READY",
                        "Note output is required before generating an illustration.", true));
        if (assetId(noteOutput, variant) != null && !replace) {
            throw new ApiException(HttpStatus.CONFLICT, "ILLUSTRATION_ALREADY_READY",
                    "Illustration variant is already ready.", false);
        }
        UUID jobId = UUID.randomUUID();
        ProcessingJob queued = new ProcessingJob(1, jobId, packageId, JobStage.ILLUSTRATION, JobStatus.QUEUED, 1, 0,
                null, null, false, "illustration:" + variant.wireName(), null, null, JobMetrics.empty());
        store.saveJob(queued);
        try {
            executor.execute(() -> runIllustrationVariant(packageId, variant, jobId));
        } catch (RejectedExecutionException exception) {
            store.saveJob(queued.failed("JOB_QUEUE_FULL", "Job queue is full.", true));
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "JOB_QUEUE_FULL", "Job queue is full.", true);
        }
        return jobId;
    }

    public void recoverInterrupted() {
        for (SourcePackage sourcePackage : store.findAllPackages()) {
            if (sourcePackage.status() == PackageStatus.PROCESSING || sourcePackage.status() == PackageStatus.QUEUED) {
                store.savePackage(sourcePackage.withState(PackageStatus.INTERRUPTED, sourcePackage.currentStage(),
                        sourcePackage.progress(), append(sourcePackage.warnings(), "服务重启导致任务中断，可手动重试。")));
                for (ProcessingJob job : store.jobs(sourcePackage.id())) {
                    if (job.status() == JobStatus.RUNNING || job.status() == JobStatus.QUEUED) {
                        store.saveJob(new ProcessingJob(job.schemaVersion(), job.id(), job.packageId(), job.stage(),
                                JobStatus.INTERRUPTED, job.attempt(), job.progress(), "JOB_INTERRUPTED",
                                "服务重启导致任务中断。", true, job.inputFingerprint(), job.startedAt(),
                                OffsetDateTime.now(), job.metrics()));
                    }
                }
            }
        }
    }

    private UUID submit(UUID packageId, JobStage start, int attempt) {
        UUID rootJobId = UUID.randomUUID();
        ProcessingJob queued = new ProcessingJob(1, rootJobId, packageId, start, JobStatus.QUEUED, attempt, 0,
                null, null, false, "", null, null, JobMetrics.empty());
        store.saveJob(queued);
        try {
            executor.execute(() -> run(packageId, start, attempt, rootJobId));
        } catch (RejectedExecutionException exception) {
            store.saveJob(queued.failed("JOB_QUEUE_FULL", "本地任务队列已满。", true));
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "JOB_QUEUE_FULL", "本地任务队列已满。", true);
        }
        return rootJobId;
    }

    private void runIllustrationVariant(UUID packageId, IllustrationVariant variant, UUID jobId) {
        ProcessingJob job = store.findJob(jobId).orElseThrow();
        store.saveJob(job.running());
        SourcePackage sourcePackage = requiredPackage(packageId);
        store.savePackage(sourcePackage.withState(PackageStatus.PROCESSING, JobStage.ILLUSTRATION,
                progressBefore(JobStage.ILLUSTRATION), sourcePackage.warnings()));
        long started = System.currentTimeMillis();
        try {
            JobMetrics metrics = generateIllustrationVariant(packageId, variant);
            metrics = new JobMetrics(System.currentTimeMillis() - started, metrics.provider(), metrics.model(),
                    metrics.inputTokens(), metrics.outputTokens(), metrics.externalRequestCount());
            store.saveJob(store.findJob(jobId).orElseThrow().succeeded(metrics));
            sourcePackage = requiredPackage(packageId);
            PackageStatus finalStatus = sourcePackage.warnings().isEmpty()
                    ? PackageStatus.READY : PackageStatus.PARTIALLY_READY;
            store.savePackage(sourcePackage.withState(finalStatus, JobStage.ILLUSTRATION, 100,
                    sourcePackage.warnings()));
        } catch (Exception exception) {
            ApiException apiException = exception instanceof ApiException value ? value
                    : new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "IMAGE_GENERATION_FAILED",
                    "Illustration generation failed.", true, exception);
            store.saveJob(store.findJob(jobId).orElseThrow()
                    .failed(apiException.errorCode(), apiException.getMessage(), apiException.retryable()));
            sourcePackage = requiredPackage(packageId);
            store.savePackage(sourcePackage.withState(PackageStatus.PARTIALLY_READY, JobStage.ILLUSTRATION, 100,
                    append(sourcePackage.warnings(), apiException.getMessage())));
        }
    }

    private void run(UUID packageId, JobStage start, int attempt, UUID firstJobId) {
        SourcePackage sourcePackage = requiredPackage(packageId);
        store.savePackage(sourcePackage.withState(PackageStatus.PROCESSING, start,
                progressBefore(start), sourcePackage.warnings()));
        UUID currentJobId = firstJobId;
        try {
            int startIndex = ORDER.indexOf(start);
            for (int index = startIndex; index < ORDER.size(); index++) {
                JobStage stage = ORDER.get(index);
                if (stage == JobStage.ILLUSTRATION && !sourcePackage.options().generateIllustration()) {
                    continue;
                }
                ProcessingJob job = index == startIndex
                        ? store.findJob(currentJobId).orElseThrow()
                        : new ProcessingJob(1, UUID.randomUUID(), packageId, stage, JobStatus.QUEUED, 1, 0,
                        null, null, false, "", null, null, JobMetrics.empty());
                currentJobId = job.id();
                store.saveJob(job.running());
                sourcePackage = requiredPackage(packageId);
                store.savePackage(sourcePackage.withState(PackageStatus.PROCESSING, stage,
                        progressBefore(stage), sourcePackage.warnings()));
                long started = System.currentTimeMillis();
                JobMetrics metrics;
                try {
                    metrics = executeStage(packageId, stage);
                } catch (RuntimeException exception) {
                    if (stage != JobStage.RAG_INDEX) throw exception;
                    ApiException failure = exception instanceof ApiException value ? value
                            : new ApiException(HttpStatus.BAD_GATEWAY, "RAG_INDEX_FAILED",
                            "RAG 索引失败。", true, exception);
                    store.saveJob(store.findJob(job.id()).orElseThrow()
                            .failed(failure.errorCode(), failure.getMessage(), failure.retryable()));
                    warnPackage(packageId, "RAG 索引失败：" + failure.getMessage());
                    continue;
                }
                metrics = new JobMetrics(System.currentTimeMillis() - started, metrics.provider(), metrics.model(),
                        metrics.inputTokens(), metrics.outputTokens(), metrics.externalRequestCount());
                store.saveJob(store.findJob(job.id()).orElseThrow().succeeded(metrics));
            }
            sourcePackage = requiredPackage(packageId);
            PackageStatus finalStatus = sourcePackage.warnings().isEmpty()
                    ? PackageStatus.READY : PackageStatus.PARTIALLY_READY;
            store.savePackage(sourcePackage.withState(finalStatus, JobStage.ILLUSTRATION,
                    100, sourcePackage.warnings()));
        } catch (Exception exception) {
            ApiException apiException = exception instanceof ApiException value ? value
                    : new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PIPELINE_FAILED",
                    "资料处理失败。", true);
            ProcessingJob current = store.findJob(currentJobId).orElse(null);
            if (current != null) {
                store.saveJob(current.failed(apiException.errorCode(), apiException.getMessage(),
                        apiException.retryable()));
            }
            sourcePackage = requiredPackage(packageId);
            if (sourcePackage.currentStage() == JobStage.ILLUSTRATION) {
                store.savePackage(sourcePackage.withState(PackageStatus.PARTIALLY_READY, JobStage.ILLUSTRATION,
                        100, append(sourcePackage.warnings(), apiException.getMessage())));
            } else {
                store.savePackage(sourcePackage.withState(PackageStatus.FAILED, sourcePackage.currentStage(),
                        sourcePackage.progress(), append(sourcePackage.warnings(), apiException.getMessage())));
            }
        }
    }

    private JobMetrics executeStage(UUID packageId, JobStage stage) {
        return switch (stage) {
            case PARSE -> parse(packageId);
            case VISION -> vision(packageId);
            case DIGEST -> digest(packageId);
            case NOTE -> note(packageId);
            case REPORT -> report(packageId);
            case RAG_INDEX -> ragIndex(packageId);
            case ILLUSTRATION -> illustrations(packageId);
            default -> JobMetrics.empty();
        };
    }

    private JobMetrics ragIndex(UUID packageId) {
        int chunks = rag.indexPackage(packageId);
        return chunks == 0 ? JobMetrics.empty()
                : new JobMetrics(0, "qwen-embedding/chroma", null, 0, 0, 1);
    }

    private JobMetrics parse(UUID packageId) {
        SourcePackage sourcePackage = requiredPackage(packageId);
        List<SourceItem> items = store.sourceItems(packageId);
        if (sourcePackage.packageType() == PackageType.VIDEO) {
            SourceItem item = items.getFirst();
            String url = String.valueOf(item.metadata().get("url"));
            VideoSubtitleExtractor.VideoResult result = video.extract(packageId, item, url);
            store.saveContentBlocks(packageId, result.blocks());
            return new JobMetrics(0, "yt-dlp", null, 0, 0, 1);
        }
        DocumentParser.ParseResult result = parser.parse(packageId, items);
        store.saveContentBlocks(packageId, result.blocks());
        store.writeJsonOutput(packageId, "normalized/vision-inputs.json", result.visionInputs());
        return JobMetrics.empty();
    }

    private JobMetrics vision(UUID packageId) {
        List<DocumentParser.VisionInput> inputs = store.readJsonOutput(packageId, "normalized/vision-inputs.json",
                DocumentParser.VisionInput[].class).map(List::of).orElse(List.of());
        if (inputs.isEmpty()) return JobMetrics.empty();
        List<ContentBlock> blocks = new ArrayList<>(store.contentBlocks(packageId));
        int sequence = blocks.stream().mapToInt(ContentBlock::sequence).max().orElse(-1) + 1;
        for (DocumentParser.VisionInput input : inputs) {
            AiProviders.VisionResult result = ai.analyzeImage(packageId, input);
            StringBuilder content = new StringBuilder(result.visibleText());
            if (!result.knowledgePoints().isEmpty()) {
                content.append("\n\n知识点：\n- ").append(String.join("\n- ", result.knowledgePoints()));
            }
            if (!result.codeBlocks().isEmpty()) {
                content.append("\n\n代码：\n```\n").append(String.join("\n\n", result.codeBlocks())).append("\n```");
            }
            blocks.add(new ContentBlock("blk_" + UUID.randomUUID().toString().replace("-", ""), packageId,
                    input.sourceItemId(), BlockType.VISION_TEXT, sequence++, content.toString(),
                    new SourceRef(SourceRefKind.IMAGE_ASSET, null, null, input.assetId(), null, null),
                    result.confidence(), Map.of("title", result.title(), "uncertainItems", result.uncertainItems())));
        }
        store.saveContentBlocks(packageId, blocks);
        return new JobMetrics(0, "qwen-vl", null, 0, 0, inputs.size());
    }

    private JobMetrics digest(UUID packageId) {
        List<List<ContentBlock>> groups = groupBlocks(store.contentBlocks(packageId), 20000, 80);
        List<JsonNode> digests = new ArrayList<>();
        int inputTokens = 0;
        int outputTokens = 0;
        int requests = 0;
        String model = null;
        for (List<ContentBlock> group : groups) {
            String formatted = formatBlocks(group, Integer.MAX_VALUE);
            AiProviders.AiResult result = withOneContentRetry(() -> ai.deepSeekJson(prompt("digest-system.txt"),
                    "请分析以下资料分组并输出 JSON：\n" + formatted));
            try {
                digests.add(mapper.readTree(result.content()));
            } catch (IOException exception) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "DEEPSEEK_JSON_INVALID",
                        "摘要 JSON 无效。", true);
            }
            inputTokens += result.metrics().inputTokens();
            outputTokens += result.metrics().outputTokens();
            requests += result.metrics().externalRequestCount();
            model = result.metrics().model();
        }
        try {
            store.writeText(packageId, "normalized/digest.json",
                    mapper.writeValueAsString(Map.of("groups", digests)));
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_WRITE_FAILED",
                    "摘要保存失败。", true);
        }
        return new JobMetrics(0, "deepseek", model, inputTokens, outputTokens, requests);
    }

    private JobMetrics note(UUID packageId) {
        String digest = store.readText(packageId, "normalized/digest.json");
        AiProviders.AiResult result = withOneContentRetry(() -> ai.deepSeekJson(prompt("note-system.txt"),
                "资料标题：" + requiredPackage(packageId).title() + "\n摘要 JSON：\n" + digest));
        try {
            JsonNode json = mapper.readTree(result.content());
            String markdown = json.path("markdown").asText();
            if (markdown.isBlank()) throw new IOException("missing markdown");
            validateCitations(packageId, markdown);
            store.writeText(packageId, "outputs/note.md", markdown);
            SourcePackage sourcePackage = requiredPackage(packageId);
            SourcePackage titledPackage = applyGeneratedTitle(sourcePackage, json.path("title").asText(""));
            if (!Objects.equals(titledPackage.title(), sourcePackage.title())) {
                store.savePackage(titledPackage);
            }
            String noteTitle = normalizeGeneratedTitle(json.path("title").asText(""));
            if (noteTitle.isBlank()) noteTitle = titledPackage.title();
            int citations = count(markdown, "[[cite:");
            String diagramTitle = null;
            String diagramFile = null;
            JobMetrics diagramMetrics = null;
            try {
                GeneratedDiagram diagram = knowledgeDiagram(packageId, digest, noteTitle);
                diagramFile = "outputs/knowledge-flow.mmd";
                store.writeText(packageId, diagramFile, diagram.mermaid());
                diagramTitle = diagram.title();
                diagramMetrics = diagram.metrics();
            } catch (ApiException exception) {
                warnPackage(packageId, "知识流程图生成失败：" + exception.getMessage());
            }
            store.writeJsonOutput(packageId, "outputs/note.json",
                    new NoteOutput(1, noteTitle,
                            "outputs/note.md", citations, imageAssets(packageId), diagramTitle, diagramFile,
                            null, null, null, result.metrics().model(), "note-v1", OffsetDateTime.now()));
            return mergeMetrics(result.metrics(), diagramMetrics);
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DEEPSEEK_JSON_INVALID",
                    "笔记 JSON 缺少 markdown 字段。", true);
        }
    }

    private JobMetrics report(UUID packageId) {
        String digest = store.readText(packageId, "normalized/digest.json");
        String note = store.readText(packageId, "outputs/note.md");
        AiProviders.AiResult result = withOneContentRetry(() -> ai.deepSeekJson(prompt("report-system.txt"),
                "摘要：\n" + digest + "\n笔记：\n" + note));
        try {
            StudyGuide guide = normalizeStudyGuide(mapper.readTree(result.content()));
            store.writeJsonOutput(packageId, "outputs/report.json", guide);
            store.writeText(packageId, "outputs/report.md", renderGuide(guide));
            return result.metrics();
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DEEPSEEK_JSON_INVALID",
                    "学习指南 JSON 结构无效。", true, exception);
        }
    }

    static StudyGuide normalizeStudyGuide(JsonNode root) throws IOException {
        String overview = text(root, "overview", "");
        if (overview.isBlank()) {
            throw new IOException("Study guide overview is missing");
        }
        return new StudyGuide(
                root.path("schemaVersion").asInt(1),
                overview,
                textList(root.path("targetAudience")),
                text(root, "difficulty", "未标注"),
                root.path("estimatedMinutes").asInt(30),
                textList(root.path("prerequisites")),
                textList(root.path("learningObjectives")),
                textList(root.path("recommendedSequence")),
                textList(root.path("coreKnowledgePoints")),
                textList(root.path("keyPoints")),
                textList(root.path("difficultPoints")),
                textList(root.path("commonMistakes")),
                textList(root.path("interviewFocus")),
                textList(root.path("exercises")),
                reviewSchedule(root.path("reviewSchedule")),
                textList(root.path("completenessWarnings")),
                textList(root.path("aiRiskWarnings"))
        );
    }

    private static String text(JsonNode root, String field, String fallback) {
        String value = root.path(field).asText("");
        return value.isBlank() ? fallback : value;
    }

    private static List<String> textList(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray()) {
            String value = displayText(node);
            return value.isBlank() ? List.of() : List.of(value);
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            String value = displayText(item);
            if (!value.isBlank()) values.add(value);
        });
        return values;
    }

    private static String displayText(JsonNode node) {
        if (node.isValueNode()) return node.asText("").strip();
        for (String field : List.of("title", "question", "content", "name", "focus", "description")) {
            String value = node.path(field).asText("");
            if (!value.isBlank()) return value.strip();
        }
        return node.toString();
    }

    private static List<Map<String, Object>> reviewSchedule(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<Map<String, Object>> values = new ArrayList<>();
        node.forEach(item -> {
            if (item.isObject()) {
                values.add(Map.of(
                        "afterDays", item.path("afterDays").asInt(0),
                        "focus", text(item, "focus", displayText(item))
                ));
            } else {
                values.add(Map.of("afterDays", 0, "focus", displayText(item)));
            }
        });
        return values;
    }

    private JobMetrics illustrations(UUID packageId) {
        int requests = 0;
        ApiException firstFailure = null;
        for (IllustrationVariant variant : IllustrationVariant.values()) {
            NoteOutput noteOutput = store.readJsonOutput(packageId, "outputs/note.json", NoteOutput.class).orElseThrow();
            if (assetId(noteOutput, variant) != null) continue;
            try {
                generateIllustrationVariant(packageId, variant);
                requests++;
            } catch (ApiException exception) {
                if (firstFailure == null) firstFailure = exception;
                warnPackage(packageId, "Wanx " + variant.wireName() + " illustration failed: " + exception.getMessage());
            }
        }
        NoteOutput noteOutput = store.readJsonOutput(packageId, "outputs/note.json", NoteOutput.class).orElseThrow();
        if (Arrays.stream(IllustrationVariant.values()).allMatch(variant -> assetId(noteOutput, variant) == null)
                && firstFailure != null) {
            throw firstFailure;
        }
        return requests == 0 ? JobMetrics.empty() : new JobMetrics(0, "wanx", null, 0, 0, requests);
    }

    private JobMetrics generateIllustrationVariant(UUID packageId, IllustrationVariant variant) {
        String digest = store.readText(packageId, "normalized/digest.json");
        JsonNode digestJson;
        try {
            digestJson = mapper.readTree(digest);
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "IMAGE_GENERATION_FAILED",
                    "Illustration prompt cannot be built from invalid digest JSON.", true, exception);
        }
        String systemPrompt = switch (variant) {
            case ABSTRACT -> prompt("illustration-abstract-system.txt");
            case CLASSIC -> prompt("illustration-classic-system.txt");
            case WHITEBOARD -> prompt("illustration-whiteboard-system.txt");
        };
        String title = requiredPackage(packageId).title();
        String builtPrompt = switch (variant) {
            case ABSTRACT -> buildAbstractIllustrationPrompt(systemPrompt, title, digestJson);
            case CLASSIC -> buildClassicIllustrationPrompt(systemPrompt, title, digestJson);
            case WHITEBOARD -> buildWhiteboardIllustrationPrompt(systemPrompt, title, digestJson);
        };
        Optional<StoredAsset> asset = ai.generateIllustration(packageId, builtPrompt,
                variant.wireName() + "-illustration.png");
        if (asset.isEmpty()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "IMAGE_GENERATION_FAILED",
                    "Wanx is not configured or returned no image.", true);
        }
        writeIllustrationAsset(packageId, variant, asset.get().id());
        return new JobMetrics(0, "wanx", null, 0, 0, 1);
    }

    private UUID assetId(NoteOutput noteOutput, IllustrationVariant variant) {
        return switch (variant) {
            case ABSTRACT -> noteOutput.abstractIllustrationAssetId();
            case CLASSIC -> noteOutput.illustrationAssetId();
            case WHITEBOARD -> noteOutput.whiteboardIllustrationAssetId();
        };
    }

    private void writeIllustrationAsset(UUID packageId, IllustrationVariant variant, UUID assetId) {
        NoteOutput noteOutput = store.readJsonOutput(packageId, "outputs/note.json", NoteOutput.class).orElseThrow();
        UUID abstractAssetId = variant == IllustrationVariant.ABSTRACT
                ? assetId : noteOutput.abstractIllustrationAssetId();
        UUID classicAssetId = variant == IllustrationVariant.CLASSIC ? assetId : noteOutput.illustrationAssetId();
        UUID whiteboardAssetId = variant == IllustrationVariant.WHITEBOARD
                ? assetId : noteOutput.whiteboardIllustrationAssetId();
        store.writeJsonOutput(packageId, "outputs/note.json",
                new NoteOutput(noteOutput.schemaVersion(), noteOutput.title(), noteOutput.markdownFile(),
                        noteOutput.citationCount(), noteOutput.sourceImageAssetIds(), noteOutput.diagramTitle(),
                        noteOutput.diagramMermaidFile(), abstractAssetId, classicAssetId, whiteboardAssetId,
                        noteOutput.model(), noteOutput.promptVersion(), noteOutput.generatedAt()));
    }

    private JobMetrics illustration(UUID packageId) {
        String digest = store.readText(packageId, "normalized/digest.json");
        JsonNode digestJson;
        try {
            digestJson = mapper.readTree(digest);
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "IMAGE_GENERATION_FAILED",
                    "插图 Prompt 构造失败：摘要 JSON 无效。", true, exception);
        }
        Optional<StoredAsset> asset = ai.generateIllustration(packageId,
                buildIllustrationPrompt(prompt("illustration-system.txt"), requiredPackage(packageId).title(),
                        digestJson));
        if (asset.isEmpty()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "IMAGE_GENERATION_FAILED",
                    "万相未配置或未返回图片。", true);
        }
        NoteOutput noteOutput = store.readJsonOutput(packageId, "outputs/note.json", NoteOutput.class).orElseThrow();
        store.writeJsonOutput(packageId, "outputs/note.json",
                new NoteOutput(noteOutput.schemaVersion(), noteOutput.title(), noteOutput.markdownFile(),
                        noteOutput.citationCount(), noteOutput.sourceImageAssetIds(), noteOutput.diagramTitle(),
                        noteOutput.diagramMermaidFile(), noteOutput.abstractIllustrationAssetId(), asset.get().id(),
                        noteOutput.whiteboardIllustrationAssetId(), noteOutput.model(), noteOutput.promptVersion(),
                        noteOutput.generatedAt()));
        return new JobMetrics(0, "wanx", null, 0, 0, 1);
    }

    private GeneratedDiagram knowledgeDiagram(UUID packageId, String digest, String noteTitle) {
        AiProviders.AiResult result = withOneContentRetry(() -> ai.deepSeekJson(prompt("diagram-system.txt"),
                "资料标题：" + requiredPackage(packageId).title()
                        + "\n笔记标题：" + noteTitle
                        + "\n摘要 JSON：\n" + digest));
        try {
            JsonNode json = mapper.readTree(result.content());
            String mermaid = renderKnowledgeDiagram(json.path("nodes"), json.path("edges"));
            String title = cleanPromptText(json.path("title").asText("知识流程图"), 28);
            return new GeneratedDiagram(title.isBlank() ? "知识流程图" : title, mermaid, result.metrics());
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DIAGRAM_GENERATION_FAILED",
                    "知识流程图 JSON 结构无效。", true, exception);
        }
    }

    private AiProviders.AiResult withOneContentRetry(java.util.function.Supplier<AiProviders.AiResult> call) {
        try {
            return call.get();
        } catch (ApiException exception) {
            if (!exception.retryable()) throw exception;
            return call.get();
        }
    }

    private String formatBlocks(List<ContentBlock> blocks, int maxChars) {
        StringBuilder output = new StringBuilder();
        for (ContentBlock block : blocks) {
            String header = "[BLOCK:" + block.id() + "]\nSOURCE:" + block.sourceRef().kind()
                    + " page=" + block.sourceRef().pageNumber()
                    + " paragraph=" + block.sourceRef().paragraphNumber()
                    + " startMs=" + block.sourceRef().startTimeMs() + "\nCONTENT:\n";
            if (output.length() + header.length() + block.content().length() > maxChars) break;
            output.append(header).append(block.content()).append("\n\n");
        }
        if (output.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_CONTENT_BLOCKS",
                    "没有可用于生成笔记的内容。", false);
        }
        return output.toString();
    }

    private List<List<ContentBlock>> groupBlocks(List<ContentBlock> blocks, int maxChars, int maxBlocks) {
        if (blocks.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_CONTENT_BLOCKS",
                    "没有可用于生成笔记的内容。", false);
        }
        List<List<ContentBlock>> groups = new ArrayList<>();
        List<ContentBlock> current = new ArrayList<>();
        int currentChars = 0;
        for (ContentBlock block : blocks) {
            int blockChars = block.content().length() + 160;
            if (!current.isEmpty() && (current.size() >= maxBlocks || currentChars + blockChars > maxChars)) {
                groups.add(List.copyOf(current));
                current.clear();
                currentChars = 0;
            }
            current.add(block);
            currentChars += blockChars;
        }
        if (!current.isEmpty()) groups.add(List.copyOf(current));
        return groups;
    }

    private String renderGuide(StudyGuide guide) {
        return "# 学习指南\n\n" + guide.overview() + "\n\n"
                + section("学习目标", guide.learningObjectives())
                + section("推荐顺序", guide.recommendedSequence())
                + section("核心知识点", guide.coreKnowledgePoints())
                + section("重点", guide.keyPoints())
                + section("难点", guide.difficultPoints())
                + section("常见错误", guide.commonMistakes())
                + section("面试重点", guide.interviewFocus())
                + section("练习", guide.exercises());
    }

    private String section(String title, List<String> values) {
        if (values == null || values.isEmpty()) return "";
        return "## " + title + "\n\n- " + String.join("\n- ", values) + "\n\n";
    }

    private String prompt(String name) {
        try {
            return new ClassPathResource("prompts/v1/" + name).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Prompt resource missing: " + name, exception);
        }
    }

    private SourcePackage requiredPackage(UUID packageId) {
        return store.findPackage(packageId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PACKAGE_NOT_FOUND", "资料包不存在。", false));
    }

    private int progressBefore(JobStage stage) {
        return switch (stage) {
            case INGEST -> 0;
            case PARSE -> 10;
            case VISION -> 30;
            case DIGEST -> 50;
            case NOTE -> 65;
            case REPORT -> 80;
            case RAG_INDEX -> 90;
            case ILLUSTRATION -> 95;
        };
    }

    private List<String> append(List<String> values, String value) {
        List<String> result = new ArrayList<>(values == null ? List.of() : values);
        result.add(value);
        return result;
    }

    private void warnPackage(UUID packageId, String warning) {
        SourcePackage sourcePackage = requiredPackage(packageId);
        if (sourcePackage.warnings() != null && sourcePackage.warnings().contains(warning)) return;
        store.savePackage(sourcePackage.withState(sourcePackage.status(), sourcePackage.currentStage(),
                sourcePackage.progress(), append(sourcePackage.warnings(), warning)));
    }

    private int count(String value, String needle) {
        int count = 0;
        for (int index = 0; (index = value.indexOf(needle, index)) >= 0; index += needle.length()) count++;
        return count;
    }

    private List<UUID> imageAssets(UUID packageId) {
        return store.sourceItems(packageId).stream().filter(item -> item.kind() == SourceKind.IMAGE)
                .map(SourceItem::assetId).toList();
    }

    private void validateCitations(UUID packageId, String markdown) {
        Set<String> knownBlocks = store.contentBlocks(packageId).stream()
                .map(ContentBlock::id)
                .collect(java.util.stream.Collectors.toSet());
        validateCitations(markdown, knownBlocks);
    }

    static void validateCitations(String markdown, Set<String> knownBlocks) {
        Matcher matcher = CITATION_PATTERN.matcher(markdown);
        List<String> missing = new ArrayList<>();
        while (matcher.find()) {
            String blockId = matcher.group(1);
            if (!knownBlocks.contains(blockId)) missing.add(blockId);
        }
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "NOTE_CITATION_INVALID",
                    "AI 笔记包含不存在的来源引用：" + String.join(", ", missing.stream().limit(3).toList()),
                    true);
        }
    }

    private JobMetrics mergeMetrics(JobMetrics primary, JobMetrics secondary) {
        if (secondary == null) return primary;
        return new JobMetrics(primary.durationMs() + secondary.durationMs(), primary.provider(),
                primary.model(), primary.inputTokens() + secondary.inputTokens(),
                primary.outputTokens() + secondary.outputTokens(),
                primary.externalRequestCount() + secondary.externalRequestCount());
    }

    static String buildClassicIllustrationPrompt(String systemPrompt, String title, JsonNode digest) {
        List<String> overviews = new ArrayList<>();
        List<String> points = new ArrayList<>();
        JsonNode groups = digest.path("groups");
        if (groups.isArray()) {
            for (JsonNode group : groups) {
                String overview = cleanPromptText(group.path("overview").asText(""), 120);
                if (!overview.isBlank()) overviews.add(overview);
                JsonNode sections = group.path("sections");
                if (!sections.isArray()) continue;
                for (JsonNode section : sections) {
                    if (points.size() >= 10) break;
                    String sectionTitle = cleanPromptText(section.path("title").asText(""), 32);
                    addUnique(points, sectionTitle);
                    JsonNode knowledgePoints = section.path("knowledgePoints");
                    if (knowledgePoints.isArray()) {
                        for (JsonNode point : knowledgePoints) {
                            if (points.size() >= 10) break;
                            addUnique(points, cleanPromptText(displayText(point), 32));
                        }
                    }
                }
            }
        }
        String cleanTitle = cleanPromptText(title, 56);
        List<String> concepts = points.stream().limit(5).toList();
        return systemPrompt.strip()
                + "\nVisual brief:"
                + "\nCanvas: soft pastel infographic with a 16:9 information-architecture layout inside a square-safe cover canvas."
                + "\nReadable title: " + cleanTitle
                + "\nShort topic summary: " + String.join("; ", overviews.stream().limit(2).toList())
                + "\nReadable short labels: " + numbered(concepts)
                + "\nCentral node: circular theme badge with a simple hierarchy icon and the package title."
                + "\nSurrounding panels: 3-5 rounded panels with one Chinese heading, optional English subtitle, 2-3 short labels, and one clean hand-drawn icon each."
                + "\nFlow: curved arrows connect panels to the central node; preserve a clear left-to-right and top-to-bottom reading path."
                + "\nPalette and texture: white paper, subtle grid, soft blue, mint green, lavender, warm beige, thin borders, watercolor header swatches."
                + "\nTypography: readable large short Chinese text, optional concise English subtitle, no dense paragraphs, no crowded micro text."
                + "\nIcon metaphor: " + visualMetaphor(cleanTitle, concepts)
                + "\nNegative: no garbled text, no illegible text, no crowded micro text, no logo, no watermark, no photorealism, no 3D glass, no dark sci-fi, no hologram, no real UI screenshot, no code block, no complex table.";
    }

    static String buildIllustrationPrompt(String systemPrompt, String title, JsonNode digest) {
        return buildClassicIllustrationPrompt(systemPrompt, title, digest);
    }

    static String buildAbstractIllustrationPrompt(String systemPrompt, String title, JsonNode digest) {
        List<String> overviews = new ArrayList<>();
        List<IllustrationSection> storyPanels = new ArrayList<>();
        JsonNode groups = digest.path("groups");
        if (groups.isArray()) {
            for (JsonNode group : groups) {
                String overview = cleanPromptText(group.path("overview").asText(""), 140);
                if (!overview.isBlank()) overviews.add(overview);
                JsonNode sections = group.path("sections");
                if (!sections.isArray()) continue;
                for (JsonNode section : sections) {
                    if (storyPanels.size() >= 5) break;
                    String sectionTitle = cleanPromptText(section.path("title").asText(""), 28);
                    List<String> labels = new ArrayList<>();
                    JsonNode knowledgePoints = section.path("knowledgePoints");
                    if (knowledgePoints.isArray()) {
                        for (JsonNode point : knowledgePoints) {
                            if (labels.size() >= 3) break;
                            addUnique(labels, cleanPromptText(displayText(point), 18));
                        }
                    }
                    if (!sectionTitle.isBlank() || !labels.isEmpty()) {
                        storyPanels.add(new IllustrationSection(
                                sectionTitle.isBlank() ? "Memory cue" : sectionTitle,
                                List.copyOf(labels),
                                visualMetaphor(sectionTitle, labels)));
                    }
                }
            }
        }
        String cleanTitle = cleanPromptText(title, 56);
        List<String> concepts = storyPanels.stream().map(IllustrationSection::title).limit(5).toList();
        return systemPrompt.strip()
                + "\nVisual brief:"
                + "\nCanvas: vintage hand-drawn abstract memory sketchnote poster, 16:9 composition inside a square-safe cover canvas."
                + "\nReadable title in ornate banner: " + cleanTitle
                + "\nTopic summary: " + String.join("; ", overviews.stream().limit(2).toList())
                + "\nMemory story panels: " + formatAbstractSections(storyPanels)
                + "\nReadable short labels: " + numbered(concepts)
                + "\nMain mnemonic metaphor: " + visualMetaphor(cleanTitle, concepts)
                + "\nComposition: decorative top banner, 3-5 numbered panels, ribbon headings, dotted travel path, arrows, symbolic scenes, and ornamental corners."
                + "\nStyle: aged parchment, sepia ink, visible cross-hatching, watercolor wash, colored pencil accents, filigree, gears, clocks, leaves, magnifying glass, stars, and hand-drawn icons."
                + "\nTypography: large readable handwritten Chinese labels only, short headings and compact keywords, no dense paragraphs or micro text."
                + "\nNegative: no garbled text, no illegible text, no crowded micro text, no logo, no watermark, no photorealism, no 3D glass, no dark sci-fi, no hologram, no real UI screenshot, no code block, no complex table.";
    }

    static String buildWhiteboardIllustrationPrompt(String systemPrompt, String title, JsonNode digest) {
        List<String> overviews = new ArrayList<>();
        List<IllustrationSection> illustrationSections = new ArrayList<>();
        JsonNode groups = digest.path("groups");
        if (groups.isArray()) {
            for (JsonNode group : groups) {
                String overview = cleanPromptText(group.path("overview").asText(""), 120);
                if (!overview.isBlank()) overviews.add(overview);
                JsonNode digestSections = group.path("sections");
                if (!digestSections.isArray()) continue;
                for (JsonNode section : digestSections) {
                    if (sectionsFull(illustrationSections)) break;
                    String sectionTitle = cleanPromptText(section.path("title").asText(""), 32);
                    List<String> labels = new ArrayList<>();
                    JsonNode knowledgePoints = section.path("knowledgePoints");
                    if (knowledgePoints.isArray()) {
                        for (JsonNode point : knowledgePoints) {
                            if (labels.size() >= 3) break;
                            addUnique(labels, cleanPromptText(displayText(point), 18));
                        }
                    }
                    if (!sectionTitle.isBlank() || !labels.isEmpty()) {
                        illustrationSections.add(new IllustrationSection(sectionTitle.isBlank() ? "核心概念" : sectionTitle,
                                List.copyOf(labels), visualMetaphor(sectionTitle, labels)));
                    }
                }
            }
        }
        String cleanTitle = cleanPromptText(title, 56);
        List<String> concepts = illustrationSections.stream().map(IllustrationSection::title).limit(4).toList();
        String visualFocus = visualMetaphor(cleanTitle, concepts);
        return systemPrompt.strip()
                + "\n视觉 brief："
                + "\n画面类型：1:1 手绘白板速记图，像老师在铝框白板上画出的知识总览海报，完整展示不要裁切。"
                + "\n图片标题：" + cleanTitle
                + "\n主题摘要：" + String.join("；", overviews.stream().limit(2).toList())
                + "\n核心概念：" + numbered(concepts)
                + "\n四个分区：" + formatIllustrationSections(illustrationSections)
                + "\n主体隐喻：" + visualFocus
                + "\n文字要求：页面会在图片外渲染真实标题、关键词和摘要；图片内只使用伪文字笔触、空白标签、短横线和抽象标记作为视觉增强，不要尝试写可读中文或英文。"
                + "\n构图：白板内用一条竖线和一条横线分成四个分区，每个分区都有一个大图标、少量空白标签和箭头/圈注；边缘保留白板铝框。"
                + "\n风格：真实白板表面，轻微反光和擦痕，粗黑手写 marker 主线，红蓝 marker 用于箭头、下划线、圈注、感叹号和灯泡；教学感强，清晰活泼。"
                + "\n留白：整体留白充足，元素不要贴边，缩略图尺寸下能看清四区结构。"
                + "\n说明：图片内文字只作为视觉增强；系统真实标题、关键词和笔记内容会在页面正文区独立显示。"
                + "\n负面：不要 Logo、水印、真实 UI 截图、照片风、3D 科幻、蓝色全息、过暗背景、密集公式、真实代码文本、复杂表格、坐标轴。";
    }

    private record IllustrationSection(String title, List<String> labels, String metaphor) {}

    private static boolean sectionsFull(List<IllustrationSection> sections) {
        return sections.size() >= 4;
    }

    private static String formatIllustrationSections(List<IllustrationSection> sections) {
        List<IllustrationSection> effective = new ArrayList<>(sections.stream().limit(4).toList());
        List<IllustrationSection> fallbacks = List.of(
                new IllustrationSection("主题总览", List.of("对象", "关系", "路径"), "中心主题写在白板顶部，四周用箭头串联关键对象。"),
                new IllustrationSection("核心概念", List.of("定义", "结构", "边界"), "概念卡片用粗黑线框和蓝色圈注突出层级关系。"),
                new IllustrationSection("关键流程", List.of("输入", "处理", "输出"), "流程箭头穿过齿轮、清单和检查点。"),
                new IllustrationSection("易错提醒", List.of("限制", "冲突", "实践"), "红色感叹号和叉号标出容易混淆的地方。")
        );
        for (IllustrationSection fallback : fallbacks) {
            if (effective.size() >= 4) break;
            effective.add(fallback);
        }
        List<String> values = new ArrayList<>();
        for (int index = 0; index < effective.size(); index++) {
            IllustrationSection section = effective.get(index);
            String labels = section.labels().isEmpty() ? "定义、关系、实践" : String.join("、", section.labels());
            values.add((index + 1) + ". " + section.title() + "（" + labels + "；视觉：" + section.metaphor() + "）");
        }
        return String.join("；", values);
    }

    private static void addUnique(List<String> values, String value) {
        if (value != null && !value.isBlank() && !values.contains(value)) values.add(value);
    }

    private static String formatAbstractSections(List<IllustrationSection> sections) {
        List<IllustrationSection> effective = new ArrayList<>(sections.stream().limit(5).toList());
        List<IllustrationSection> fallbacks = List.of(
                new IllustrationSection("Theme map", List.of("topic", "route", "cue"),
                        "a parchment map with the topic as a landmark and dotted paths between concepts"),
                new IllustrationSection("Core idea", List.of("definition", "structure", "boundary"),
                        "a magnifying glass over a central badge with arrows to related symbols"),
                new IllustrationSection("Process memory", List.of("input", "change", "output"),
                        "gears, clock hands, and ribbon arrows showing transformation over time"),
                new IllustrationSection("Mistake warning", List.of("trap", "contrast", "practice"),
                        "red exclamation marks, crossed paths, and small checklist cards")
        );
        for (IllustrationSection fallback : fallbacks) {
            if (effective.size() >= 4) break;
            effective.add(fallback);
        }
        List<String> values = new ArrayList<>();
        for (int index = 0; index < effective.size(); index++) {
            IllustrationSection section = effective.get(index);
            String labels = section.labels().isEmpty()
                    ? "concept, relation, memory cue"
                    : String.join(", ", section.labels());
            values.add((index + 1) + ". " + section.title()
                    + " | labels: " + labels + " | visual: " + section.metaphor());
        }
        return String.join("; ", values);
    }

    private static String numbered(List<String> values) {
        if (values.isEmpty()) return "1. 核心主题对象；2. 概念关系；3. 学习路径";
        List<String> numbered = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            numbered.add((index + 1) + ". " + values.get(index));
        }
        return String.join("；", numbered);
    }

    private static String visualMetaphor(String title, List<String> concepts) {
        String corpus = (title + " " + String.join(" ", concepts)).toLowerCase(Locale.ROOT);
        if (corpus.matches(".*(java|python|代码|编程|开发|线程|并发|接口|api|debug|测试|部署|工程).*")) {
            return "戴耳机的小程序员在电脑前推进任务，旁边有代码窗口图标、清单、测试放大镜和发布火箭，表现从理解到实现的开发流程。";
        }
        if (corpus.matches(".*(agent|langgraph|状态|工作流|编排|tool|流程|项目|协作|计划).*")) {
            return "小角色站在流程白板前调度多个工具节点，节点用箭头和勾选圆点连接，表现任务编排、条件路由和反馈闭环。";
        }
        if (corpus.matches(".*(向量|vector|数据库|检索|rag|embedding|索引).*")) {
            return "数据库管道连接搜索放大镜、向量点阵和知识卡片，表现语义簇、近邻匹配和知识召回。";
        }
        if (corpus.matches(".*(算法|复杂度|排序|搜索|递归|动态规划|贪心|图论|路径).*")) {
            return "手绘算法路线图从起点穿过分支节点、循环箭头和检查点，表现拆解、选择、验证和优化。";
        }
        if (corpus.matches(".*(分类|classif|监督|无监督|聚类|cluster).*")) {
            return "分叉分类树连接多个样本贴纸簇，表现类别边界、样本归属和学习路径。";
        }
        if (corpus.matches(".*(神经|neural|cnn|rnn|lstm|gru|卷积|循环|残差|网络|transformer|attention).*")) {
            return "手绘神经网络白板由多层节点、信号箭头和注意力聚光线组成，表现层级特征、连接权重和信息流。";
        }
        if (corpus.matches(".*(过拟合|泛化|正则|dropout|归一化|normalization).*")) {
            return "被修剪的模型树枝旁放着训练清单、约束夹子和误差仪表，表现泛化、约束和误差控制。";
        }
        if (corpus.matches(".*(优化|梯度|sgd|momentum|adam|学习率).*")) {
            return "沿等高线下降的手绘优化路径连接参数旋钮、迭代脚印和收敛旗帜，表现方向选择与逐步改进。";
        }
        if (corpus.matches(".*(考试|面试|复习|题目|练习|知识点|学习|指南).*")) {
            return "学习伙伴在白板前整理便签、灯泡、错题放大镜和复习路径，表现目标拆解、重点巩固和练习反馈。";
        }
        return "学习伙伴在白板前把资料拆成图标化知识卡片，用箭头连接成学习路径，表现层级、关系和复盘节奏。";
    }

    static String renderKnowledgeDiagram(JsonNode nodesJson, JsonNode edgesJson) {
        if (!nodesJson.isArray() || nodesJson.size() < 5 || nodesJson.size() > 24 || !edgesJson.isArray()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DIAGRAM_GENERATION_FAILED",
                    "知识流程图节点数量必须控制在 5-24 个。", true);
        }
        LinkedHashMap<String, String> nodes = new LinkedHashMap<>();
        for (JsonNode node : nodesJson) {
            String id = node.path("id").asText("").strip();
            String label = cleanPromptText(node.path("label").asText(""), 24);
            if (!id.matches("[A-Za-z][A-Za-z0-9_]{0,31}") || label.isBlank() || nodes.putIfAbsent(id, label) != null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "DIAGRAM_GENERATION_FAILED",
                        "知识流程图节点结构无效。", true);
            }
        }
        List<String> edges = new ArrayList<>();
        for (JsonNode edge : edgesJson) {
            String from = edge.path("from").asText("").strip();
            String to = edge.path("to").asText("").strip();
            if (!nodes.containsKey(from) || !nodes.containsKey(to) || from.equals(to)) continue;
            String label = cleanPromptText(edge.path("label").asText(""), 12);
            edges.add(from + (label.isBlank() ? " --> " : " -- \"" + escapeMermaid(label) + "\" --> ") + to);
        }
        if (edges.size() < Math.max(1, nodes.size() - 1)) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DIAGRAM_GENERATION_FAILED",
                    "知识流程图缺少完整的学习路径。", true);
        }
        StringBuilder mermaid = new StringBuilder("flowchart TB\n");
        nodes.forEach((id, label) -> mermaid.append("    ").append(id).append("[\"")
                .append(escapeMermaid(label)).append("\"]\n"));
        edges.forEach(edge -> mermaid.append("    ").append(edge).append('\n'));
        return normalizeKnowledgeDiagram(mermaid.toString());
    }

    private static String escapeMermaid(String value) {
        return value.replace("\\", "／").replace("\"", "”")
                .replace("[", "【").replace("]", "】");
    }

    static SourcePackage applyGeneratedTitle(SourcePackage sourcePackage, String generatedTitle) {
        if (sourcePackage.options() == null || !sourcePackage.options().autoTitle()) return sourcePackage;
        String normalized = normalizeGeneratedTitle(generatedTitle);
        return normalized.isBlank() ? sourcePackage : sourcePackage.withTitle(normalized);
    }

    static String normalizeGeneratedTitle(String raw) {
        String value = cleanPromptText(raw, 160)
                .replaceAll("^[\"'“”‘’《》]+|[\"'“”‘’《》]+$", "")
                .replaceAll("(?i)\\.(pdf|txt|md|png|jpe?g|webp)$", "")
                .strip();
        if (value.isBlank() || !value.matches(".*[\\p{L}\\p{N}].*")) return "";
        if (Set.of("学习资料", "资料", "学习笔记", "笔记", "untitled", "title")
                .contains(value.toLowerCase(Locale.ROOT))) return "";
        return value.length() <= 80 ? value : value.substring(0, 80).strip();
    }

    static String normalizeKnowledgeDiagram(String raw) {
        String mermaid = stripMermaidFence(raw).replace("\r\n", "\n").replace('\r', '\n').strip();
        if (!mermaid.startsWith("flowchart TB")) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DIAGRAM_GENERATION_FAILED",
                    "知识流程图必须使用 Mermaid flowchart TB。", true);
        }
        if (mermaid.contains("[[cite:") || mermaid.matches("(?is).*\\bblk_[a-f0-9]{32}\\b.*")) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DIAGRAM_GENERATION_FAILED",
                    "知识流程图不能包含内部引用 ID。", true);
        }
        Set<String> nodes = new LinkedHashSet<>();
        Matcher nodeMatcher = MERMAID_NODE_PATTERN.matcher(mermaid);
        while (nodeMatcher.find()) nodes.add(nodeMatcher.group(1));
        if (nodes.size() < 5 || nodes.size() > 24) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DIAGRAM_GENERATION_FAILED",
                    "知识流程图节点数量必须控制在 5-24 个。", true);
        }
        Matcher labelMatcher = MERMAID_LABEL_PATTERN.matcher(mermaid);
        while (labelMatcher.find()) {
            if (labelMatcher.group(1).strip().length() > 24) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "DIAGRAM_GENERATION_FAILED",
                        "知识流程图节点标签过长。", true);
            }
        }
        return mermaid;
    }

    private static String stripMermaidFence(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (!value.startsWith("```")) return value;
        value = value.replaceFirst("(?is)^```(?:mermaid)?\\s*", "");
        return value.replaceFirst("(?is)```$", "").strip();
    }

    private static String cleanPromptText(String raw, int maxChars) {
        String value = CODE_FENCE_PATTERN.matcher(raw == null ? "" : raw).replaceAll(" ");
        value = CITATION_PATTERN.matcher(value).replaceAll(" ");
        value = value.replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("[*_`>\\[\\]{}|]", " ")
                .replaceAll("\\s+", " ")
                .strip();
        return value.length() <= maxChars ? value : value.substring(0, maxChars).strip();
    }

    private record GeneratedDiagram(String title, String mermaid, JobMetrics metrics) {}
}
