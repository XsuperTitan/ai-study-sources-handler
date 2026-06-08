package com.aisourceshandler.api;

import com.aisourceshandler.application.PackagePipeline;
import com.aisourceshandler.config.AppProperties;
import com.aisourceshandler.domain.Models.*;
import com.aisourceshandler.infrastructure.AiProviders;
import com.aisourceshandler.infrastructure.LocalStore;
import com.aisourceshandler.infrastructure.VideoSubtitleExtractor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
public class PackageController {
    private final LocalStore store;
    private final PackagePipeline pipeline;
    private final AppProperties properties;
    private final AiProviders ai;
    private final VideoSubtitleExtractor video;

    public PackageController(LocalStore store, PackagePipeline pipeline, AppProperties properties,
                             AiProviders ai, VideoSubtitleExtractor video) {
        this.store = store;
        this.pipeline = pipeline;
        this.properties = properties;
        this.ai = ai;
        this.video = video;
    }

    @PostMapping(value = "/packages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Map<String, Object>> createPackage(
            @RequestParam(required = false) List<MultipartFile> files,
            @RequestParam(required = false) String textContent,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String outputLanguage,
            @RequestParam(required = false) String noteStyle,
            @RequestParam(required = false) Boolean generateIllustration) {
        List<MultipartFile> uploads = files == null ? List.of() : files;
        validateUploads(uploads, textContent);
        UUID packageId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        PackageOptions options = new PackageOptions(defaulted(outputLanguage, "ZH_CN"),
                defaulted(noteStyle, "INTERVIEW"), generateIllustration == null || generateIllustration);
        SourcePackage sourcePackage = new SourcePackage(1, packageId, "local-user",
                defaulted(title, deriveTitle(uploads, textContent)), PackageType.MIXED, PackageStatus.QUEUED,
                JobStage.INGEST, 0, options, new ArrayList<>(), new ArrayList<>(), now, now);
        store.savePackage(sourcePackage);

        List<SourceItem> items = new ArrayList<>();
        int sequence = 0;
        for (MultipartFile file : uploads) {
            validateSignature(file);
            StoredAsset asset = store.storeUpload(packageId, file);
            SourceKind kind = kind(file.getOriginalFilename());
            SourceItem item = new SourceItem(UUID.randomUUID(), packageId, kind, asset.originalName(), asset.id(),
                    sequence++, Map.of("size", asset.size()));
            items.add(item);
        }
        if (textContent != null && !textContent.isBlank()) {
            StoredAsset asset = store.storeBytes(packageId, "pasted-text.txt", "text/plain",
                    textContent.getBytes(StandardCharsets.UTF_8), "original");
            items.add(new SourceItem(UUID.randomUUID(), packageId, SourceKind.PASTED_TEXT, "粘贴文本",
                    asset.id(), sequence, Map.of("size", asset.size())));
        }
        sourcePackage.sourceItemIds().addAll(items.stream().map(SourceItem::id).toList());
        store.saveSourceItems(packageId, items);
        store.savePackage(sourcePackage.withState(PackageStatus.QUEUED, JobStage.PARSE, 10, List.of()));
        UUID jobId = pipeline.submit(packageId);
        return ResponseEntity.accepted().body(created(packageId, jobId));
    }

    @PostMapping("/video-packages")
    ResponseEntity<Map<String, Object>> createVideo(@Valid @RequestBody VideoCreateRequest request) {
        UUID packageId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        PackageOptions options = new PackageOptions(defaulted(request.outputLanguage(), "ZH_CN"),
                defaulted(request.noteStyle(), "INTERVIEW"), request.generateIllustration() == null
                || request.generateIllustration());
        SourceItem item = new SourceItem(UUID.randomUUID(), packageId, SourceKind.VIDEO,
                defaulted(request.title(), "Bilibili 视频"), null, 0, Map.of("url", request.url()));
        SourcePackage sourcePackage = new SourcePackage(1, packageId, "local-user",
                defaulted(request.title(), "Bilibili 视频"), PackageType.VIDEO, PackageStatus.QUEUED,
                JobStage.PARSE, 10, options, new ArrayList<>(List.of(item.id())), new ArrayList<>(), now, now);
        store.savePackage(sourcePackage);
        store.saveSourceItems(packageId, List.of(item));
        UUID jobId = pipeline.submit(packageId);
        return ResponseEntity.accepted().body(created(packageId, jobId));
    }

    @GetMapping("/packages")
    List<SourcePackage> packages(@RequestParam(defaultValue = "20") int limit,
                                 @RequestParam(required = false) String status,
                                 @RequestParam(required = false) String type,
                                 @RequestParam(required = false) String q) {
        PackageStatus statusFilter = enumFilter(status, PackageStatus.class, "INVALID_PACKAGE_STATUS");
        PackageType typeFilter = enumFilter(type, PackageType.class, "INVALID_PACKAGE_TYPE");
        String query = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        return store.findAllPackages().stream()
                .filter(value -> statusFilter == null || value.status() == statusFilter)
                .filter(value -> typeFilter == null || value.packageType() == typeFilter)
                .filter(value -> query.isBlank() || value.title().toLowerCase(Locale.ROOT).contains(query))
                .limit(Math.max(1, Math.min(limit, 100)))
                .toList();
    }

    @GetMapping("/packages/{packageId}")
    Map<String, Object> packageDetail(@PathVariable UUID packageId) {
        SourcePackage value = requiredPackage(packageId);
        Optional<NoteOutput> note = store.readJsonOutput(packageId, "outputs/note.json", NoteOutput.class);
        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("noteReady", note.isPresent());
        outputs.put("reportReady", store.readJsonOutput(packageId, "outputs/report.json", StudyGuide.class).isPresent());
        String diagramFile = note.map(NoteOutput::diagramMermaidFile).filter(file -> !file.isBlank()).orElse(null);
        outputs.put("diagramReady", diagramFile != null);
        if (diagramFile != null) {
            outputs.put("diagramTitle", note.map(NoteOutput::diagramTitle)
                    .filter(title -> title != null && !title.isBlank()).orElse("知识流程图"));
            outputs.put("diagramUrl", "/api/v1/packages/" + packageId + "/diagram");
        }
        UUID illustrationAssetId = note.map(NoteOutput::illustrationAssetId).orElse(null);
        outputs.put("illustrationReady", illustrationAssetId != null);
        if (illustrationAssetId != null) {
            outputs.put("illustrationAssetId", illustrationAssetId);
            outputs.put("illustrationAssetUrl", assetUrl(packageId, illustrationAssetId));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", value.id());
        response.put("title", value.title());
        response.put("packageType", value.packageType());
        response.put("status", value.status());
        response.put("currentStage", value.currentStage());
        response.put("progress", value.progress());
        response.put("warnings", value.warnings());
        response.put("createdAt", value.createdAt());
        response.put("outputs", outputs);
        return response;
    }

    @GetMapping("/packages/{packageId}/sources")
    Map<String, Object> sources(@PathVariable UUID packageId) {
        requiredPackage(packageId);
        List<Map<String, Object>> items = store.sourceItems(packageId).stream()
                .map(item -> sourceItemResponse(packageId, item))
                .toList();
        List<Map<String, Object>> assets = store.packageAssets(packageId).stream()
                .map(asset -> assetResponse(packageId, asset))
                .toList();
        return Map.of("items", items, "assets", assets);
    }

    @GetMapping("/packages/{packageId}/jobs")
    List<ProcessingJob> jobs(@PathVariable UUID packageId) {
        requiredPackage(packageId);
        return store.jobs(packageId);
    }

    @DeleteMapping("/packages/{packageId}")
    ResponseEntity<Void> deletePackage(@PathVariable UUID packageId) {
        SourcePackage sourcePackage = requiredPackage(packageId);
        if (sourcePackage.status() == PackageStatus.QUEUED || sourcePackage.status() == PackageStatus.PROCESSING) {
            throw new ApiException(HttpStatus.CONFLICT, "PACKAGE_DELETE_BLOCKED",
                    "资料包仍在处理中，完成或失败后再删除。", false);
        }
        store.deletePackage(packageId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/jobs/{jobId}/retry")
    ResponseEntity<Map<String, Object>> retry(@PathVariable UUID jobId) {
        UUID nextJob = pipeline.retry(jobId);
        ProcessingJob job = store.findJob(nextJob).orElseThrow();
        return ResponseEntity.accepted().body(created(job.packageId(), nextJob));
    }

    @GetMapping(value = "/packages/{packageId}/note", produces = "text/markdown;charset=UTF-8")
    String note(@PathVariable UUID packageId) {
        requiredPackage(packageId);
        return store.readText(packageId, "outputs/note.md");
    }

    @GetMapping(value = "/packages/{packageId}/note.md", produces = "text/markdown;charset=UTF-8")
    ResponseEntity<String> noteMarkdown(@PathVariable UUID packageId) {
        SourcePackage sourcePackage = requiredPackage(packageId);
        return markdownDownload(packageId, "outputs/note.md", sourcePackage.title(), "ai-note");
    }

    @GetMapping("/packages/{packageId}/report")
    ResponseEntity<?> report(@PathVariable UUID packageId,
                             @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {
        requiredPackage(packageId);
        if (accept != null && accept.contains("text/markdown")) {
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                    .body(store.readText(packageId, "outputs/report.md"));
        }
        StudyGuide guide = store.readJsonOutput(packageId, "outputs/report.json", StudyGuide.class)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "REPORT_NOT_READY",
                        "学习指南尚未生成。", true));
        return ResponseEntity.ok(guide);
    }

    @GetMapping(value = "/packages/{packageId}/report.md", produces = "text/markdown;charset=UTF-8")
    ResponseEntity<String> reportMarkdown(@PathVariable UUID packageId) {
        SourcePackage sourcePackage = requiredPackage(packageId);
        return markdownDownload(packageId, "outputs/report.md", sourcePackage.title(), "study-guide");
    }

    @GetMapping(value = "/packages/{packageId}/diagram", produces = "text/plain;charset=UTF-8")
    ResponseEntity<String> diagram(@PathVariable UUID packageId) {
        requiredPackage(packageId);
        NoteOutput note = store.readJsonOutput(packageId, "outputs/note.json", NoteOutput.class)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "DIAGRAM_NOT_READY",
                        "知识流程图尚未生成。", true));
        if (note.diagramMermaidFile() == null || note.diagramMermaidFile().isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, "DIAGRAM_NOT_READY", "知识流程图尚未生成。", true);
        }
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                    .body(store.readText(packageId, note.diagramMermaidFile()));
        } catch (ApiException exception) {
            if ("OUTPUT_NOT_READY".equals(exception.errorCode())) {
                throw new ApiException(HttpStatus.CONFLICT, "DIAGRAM_NOT_READY", "知识流程图尚未生成。", true);
            }
            throw exception;
        }
    }

    @GetMapping("/packages/{packageId}/citations/{blockId}")
    Map<String, Object> citation(@PathVariable UUID packageId, @PathVariable String blockId) {
        ContentBlock block = store.contentBlocks(packageId).stream().filter(value -> value.id().equals(blockId))
                .findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CITATION_NOT_FOUND",
                        "引用不存在。", false));
        SourceRef ref = block.sourceRef();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("blockId", block.id());
        response.put("sourceKind", ref.kind());
        response.put("displayName", displayName(block));
        String assetUrl = ref.assetId() == null ? null
                : "/api/v1/packages/" + packageId + "/assets/" + ref.assetId();
        if (ref.kind() == SourceRefKind.VIDEO_TIME_RANGE) {
            assetUrl = store.sourceItems(packageId).stream()
                    .filter(item -> item.id().equals(block.sourceItemId()))
                    .map(item -> String.valueOf(item.metadata().get("url")))
                    .findFirst().orElse(null);
        }
        response.put("assetUrl", assetUrl);
        response.put("pageNumber", ref.pageNumber());
        response.put("paragraphNumber", ref.paragraphNumber());
        response.put("startTimeMs", ref.startTimeMs());
        response.put("endTimeMs", ref.endTimeMs());
        response.put("excerpt", block.content().substring(0, Math.min(500, block.content().length())));
        return response;
    }

    @GetMapping("/packages/{packageId}/assets/{assetId}")
    ResponseEntity<FileSystemResource> asset(@PathVariable UUID packageId, @PathVariable UUID assetId)
            throws IOException {
        requiredPackage(packageId);
        StoredAsset metadata = store.asset(packageId, assetId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", "资源不存在。", false));
        var resource = new FileSystemResource(store.resolveAsset(packageId, assetId));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(metadata.contentType()))
                .contentLength(Files.size(resource.getFile().toPath()))
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + assetId + "\"")
                .body(resource);
    }

    @GetMapping("/capabilities")
    Map<String, Object> capabilities() {
        return Map.of(
                "deepseek", Map.of("available", ai.deepSeekConfigured(), "model", properties.deepseek().model()),
                "qwenVl", Map.of("available", ai.qwenConfigured(), "model", properties.qwen().model()),
                "wanx", Map.of("available", ai.wanxConfigured(), "model", properties.wanx().model()),
                "video", Map.of("available", video.available(), "provider", "yt-dlp")
        );
    }

    private void validateUploads(List<MultipartFile> files, String text) {
        if (files.isEmpty() && (text == null || text.isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_PACKAGE", "至少提供一个文件或粘贴文本。", false);
        }
        if (files.size() > properties.upload().maxFiles()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD_FILE_LIMIT_EXCEEDED", "文件数量超过限制。", false);
        }
        long total = files.stream().mapToLong(MultipartFile::getSize).sum()
                + (text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length);
        if (total > properties.upload().maxTotalBytes()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD_TOTAL_SIZE_EXCEEDED", "资料包总大小超过限制。", false);
        }
        for (MultipartFile file : files) {
            if (file.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "不能上传空文件。", false);
            SourceKind kind = kind(file.getOriginalFilename());
            if (kind == SourceKind.IMAGE && file.getSize() > properties.upload().maxImageBytes()) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "IMAGE_SIZE_EXCEEDED", "单张图片超过限制。", false);
            }
            if (kind == SourceKind.TEXT_FILE && file.getSize() > properties.upload().maxTextBytes()) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "TEXT_SIZE_EXCEEDED", "文本文件超过限制。", false);
            }
        }
    }

    private void validateSignature(MultipartFile file) {
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        try {
            byte[] header = file.getInputStream().readNBytes(12);
            boolean valid = name.endsWith(".pdf") ? starts(header, "%PDF-".getBytes(StandardCharsets.US_ASCII))
                    : name.endsWith(".png") ? starts(header, new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47})
                    : name.endsWith(".jpg") || name.endsWith(".jpeg") ? starts(header, new byte[]{(byte) 0xff, (byte) 0xd8})
                    : name.endsWith(".webp") ? header.length >= 12
                        && new String(header, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                        && new String(header, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")
                    : true;
            if (!valid) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "FILE_SIGNATURE_MISMATCH",
                        "文件扩展名与内容签名不匹配：" + name, false);
            }
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FILE_READ_FAILED", "无法读取上传文件。", false);
        }
    }

    private boolean starts(byte[] value, byte[] prefix) {
        if (value.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) if (value[index] != prefix[index]) return false;
        return true;
    }

    private SourceKind kind(String originalName) {
        String name = Optional.ofNullable(originalName).orElse("").toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) return SourceKind.PDF;
        if (name.endsWith(".txt") || name.endsWith(".md")) return SourceKind.TEXT_FILE;
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".webp")) {
            return SourceKind.IMAGE;
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FILE_TYPE",
                "仅支持 PDF、TXT、MD、PNG、JPG、JPEG 和 WEBP。", false);
    }

    private SourcePackage requiredPackage(UUID packageId) {
        return store.findPackage(packageId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PACKAGE_NOT_FOUND", "资料包不存在。", false));
    }

    private Map<String, Object> created(UUID packageId, UUID jobId) {
        return Map.of("packageId", packageId, "rootJobId", jobId, "status", "QUEUED",
                "statusUrl", "/api/v1/packages/" + packageId);
    }

    private ResponseEntity<String> markdownDownload(UUID packageId, String relativePath, String title, String suffix) {
        String fileName = safeDownloadName(title, suffix);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString())
                .body(store.readText(packageId, relativePath));
    }

    private String safeDownloadName(String title, String suffix) {
        String base = Optional.ofNullable(title).orElse("learning-material")
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", " ")
                .replaceAll("\\s+", "-")
                .replaceAll("^-+|-+$", "")
                .strip();
        if (base.isBlank()) base = "learning-material";
        if (base.length() > 80) base = base.substring(0, 80).replaceAll("-+$", "");
        return base + "-" + suffix + ".md";
    }

    private String deriveTitle(List<MultipartFile> files, String text) {
        if (!files.isEmpty()) return Optional.ofNullable(files.getFirst().getOriginalFilename()).orElse("学习资料");
        return text == null ? "学习资料" : text.strip().substring(0, Math.min(30, text.strip().length()));
    }

    private String defaulted(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private <T extends Enum<T>> T enumFilter(String rawValue, Class<T> type, String errorCode) {
        if (rawValue == null || rawValue.isBlank()) return null;
        try {
            return Enum.valueOf(type, rawValue.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, errorCode, "筛选参数不受支持：" + rawValue, false);
        }
    }

    private Map<String, Object> sourceItemResponse(UUID packageId, SourceItem item) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", item.id());
        response.put("kind", item.kind());
        response.put("originalName", item.originalName());
        response.put("sequence", item.sequence());
        response.put("metadata", item.metadata());
        if (item.assetId() != null) {
            response.put("assetId", item.assetId());
            response.put("assetUrl", assetUrl(packageId, item.assetId()));
            store.asset(packageId, item.assetId()).ifPresent(asset -> {
                response.put("contentType", asset.contentType());
                response.put("size", asset.size());
            });
        } else if (item.kind() == SourceKind.VIDEO) {
            response.put("sourceUrl", item.metadata().get("url"));
        }
        return response;
    }

    private Map<String, Object> assetResponse(UUID packageId, StoredAsset asset) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", asset.id());
        response.put("originalName", asset.originalName());
        response.put("contentType", asset.contentType());
        response.put("size", asset.size());
        response.put("assetUrl", assetUrl(packageId, asset.id()));
        return response;
    }

    private String assetUrl(UUID packageId, UUID assetId) {
        return "/api/v1/packages/" + packageId + "/assets/" + assetId;
    }

    private String displayName(ContentBlock block) {
        SourceRef ref = block.sourceRef();
        if (ref.pageNumber() != null) return "PDF 第 " + ref.pageNumber() + " 页";
        if (ref.paragraphNumber() != null) return "文本第 " + ref.paragraphNumber() + " 段";
        if (ref.startTimeMs() != null) return "视频 " + ref.startTimeMs() / 1000 + " 秒";
        return "原始图片";
    }

    public record VideoCreateRequest(@NotBlank String url, String title, String outputLanguage,
                                     String noteStyle, Boolean generateIllustration) {}
}
