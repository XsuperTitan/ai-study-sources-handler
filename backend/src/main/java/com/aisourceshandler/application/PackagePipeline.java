package com.aisourceshandler.application;

import com.aisourceshandler.api.ApiException;
import com.aisourceshandler.domain.Models.*;
import com.aisourceshandler.infrastructure.*;
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
            JobStage.PARSE, JobStage.VISION, JobStage.DIGEST, JobStage.NOTE, JobStage.REPORT, JobStage.ILLUSTRATION);

    private final LocalStore store;
    private final DocumentParser parser;
    private final AiProviders ai;
    private final VideoSubtitleExtractor video;
    private final ObjectMapper mapper;
    private final Executor executor;

    public PackagePipeline(LocalStore store, DocumentParser parser, AiProviders ai, VideoSubtitleExtractor video,
                           ObjectMapper mapper, @Qualifier("jobExecutor") Executor executor) {
        this.store = store;
        this.parser = parser;
        this.ai = ai;
        this.video = video;
        this.mapper = mapper;
        this.executor = executor;
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
                .filter(warning -> !Objects.equals(warning, failed.errorMessage()))
                .toList();
        store.savePackage(sourcePackage.withState(sourcePackage.status(), sourcePackage.currentStage(),
                sourcePackage.progress(), warnings));
        return submit(failed.packageId(), failed.stage(), failed.attempt() + 1);
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
                JobMetrics metrics = executeStage(packageId, stage);
                metrics = new JobMetrics(System.currentTimeMillis() - started, metrics.provider(), metrics.model(),
                        metrics.inputTokens(), metrics.outputTokens(), metrics.externalRequestCount());
                store.saveJob(store.findJob(job.id()).orElseThrow().succeeded(metrics));
            }
            sourcePackage = requiredPackage(packageId);
            store.savePackage(sourcePackage.withState(PackageStatus.READY, JobStage.ILLUSTRATION,
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
            case ILLUSTRATION -> illustration(packageId);
            default -> JobMetrics.empty();
        };
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
            int citations = count(markdown, "[[cite:");
            String diagramTitle = null;
            String diagramFile = null;
            JobMetrics diagramMetrics = null;
            try {
                GeneratedDiagram diagram = knowledgeDiagram(packageId, digest,
                        json.path("title").asText(requiredPackage(packageId).title()));
                diagramFile = "outputs/knowledge-flow.mmd";
                store.writeText(packageId, diagramFile, diagram.mermaid());
                diagramTitle = diagram.title();
                diagramMetrics = diagram.metrics();
            } catch (ApiException exception) {
                warnPackage(packageId, "知识流程图生成失败：" + exception.getMessage());
            }
            store.writeJsonOutput(packageId, "outputs/note.json",
                    new NoteOutput(1, json.path("title").asText(requiredPackage(packageId).title()),
                            "outputs/note.md", citations, imageAssets(packageId), diagramTitle, diagramFile,
                            null, result.metrics().model(), "note-v1", OffsetDateTime.now()));
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
                        noteOutput.diagramMermaidFile(), asset.get().id(), noteOutput.model(),
                        noteOutput.promptVersion(), noteOutput.generatedAt()));
        return new JobMetrics(0, "wanx", null, 0, 0, 1);
    }

    private GeneratedDiagram knowledgeDiagram(UUID packageId, String digest, String noteTitle) {
        AiProviders.AiResult result = withOneContentRetry(() -> ai.deepSeekJson(prompt("diagram-system.txt"),
                "资料标题：" + requiredPackage(packageId).title()
                        + "\n笔记标题：" + noteTitle
                        + "\n摘要 JSON：\n" + digest));
        try {
            JsonNode json = mapper.readTree(result.content());
            String mermaid = normalizeKnowledgeDiagram(json.path("mermaid").asText());
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

    static String buildIllustrationPrompt(String systemPrompt, String title, JsonNode digest) {
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
                    if (points.size() >= 8) break;
                      String sectionTitle = cleanPromptText(section.path("title").asText(""), 32);
                    if (!sectionTitle.isBlank()) points.add(sectionTitle);
                    JsonNode knowledgePoints = section.path("knowledgePoints");
                    if (knowledgePoints.isArray()) {
                        for (JsonNode point : knowledgePoints) {
                            if (points.size() >= 8) break;
                              String value = cleanPromptText(displayText(point), 32);
                            if (!value.isBlank() && !points.contains(value)) points.add(value);
                        }
                    }
                }
            }
        }
        return systemPrompt.strip()
                + "\n标题：" + cleanPromptText(title, 48)
                + "\n摘要：" + String.join("；", overviews.stream().limit(2).toList())
                + "\n概念：" + String.join("；", points.stream().limit(6).toList())
                + "\n构图：一个核心主题对象，加 3-5 个与概念一一对应的模块；专属隐喻优先，不画通用科技装置。";
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
        if (nodes.size() < 5 || nodes.size() > 9) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DIAGRAM_GENERATION_FAILED",
                    "知识流程图节点数量必须控制在 5-9 个。", true);
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
