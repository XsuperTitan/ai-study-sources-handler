package com.aisourceshandler.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Models {
    private Models() {}

    public enum PackageType { MIXED, VIDEO }
    public enum PackageStatus { QUEUED, PROCESSING, READY, PARTIALLY_READY, FAILED, INTERRUPTED }
    public enum SourceKind { PDF, TEXT_FILE, PASTED_TEXT, IMAGE, VIDEO }
    public enum BlockType { HEADING, TEXT, CODE, TABLE_TEXT, IMAGE, VISION_TEXT, TRANSCRIPT }
    public enum SourceRefKind { PDF_PAGE, TEXT_PARAGRAPH, IMAGE_ASSET, VIDEO_TIME_RANGE, AI_SUPPLEMENT }
    public enum JobStage { INGEST, PARSE, VISION, DIGEST, NOTE, REPORT, RAG_INDEX, ILLUSTRATION }
    public enum JobStatus { QUEUED, RUNNING, SUCCEEDED, FAILED, SKIPPED, INTERRUPTED }

    public record PackageOptions(String outputLanguage, String noteStyle, boolean generateIllustration,
                                 boolean autoTitle) {
        public static PackageOptions defaults() {
            return new PackageOptions("ZH_CN", "INTERVIEW", true, false);
        }
    }

    public record SourcePackage(
            int schemaVersion,
            UUID id,
            String ownerId,
            String title,
            PackageType packageType,
            PackageStatus status,
            JobStage currentStage,
            int progress,
            PackageOptions options,
            List<UUID> sourceItemIds,
            List<String> warnings,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        public SourcePackage withState(PackageStatus nextStatus, JobStage stage, int nextProgress, List<String> nextWarnings) {
            return new SourcePackage(schemaVersion, id, ownerId, title, packageType, nextStatus, stage,
                    nextProgress, options, sourceItemIds, nextWarnings, createdAt, OffsetDateTime.now());
        }

        public SourcePackage withTitle(String nextTitle) {
            return new SourcePackage(schemaVersion, id, ownerId, nextTitle, packageType, status, currentStage,
                    progress, options, sourceItemIds, warnings, createdAt, OffsetDateTime.now());
        }
    }

    public record SourceItem(
            UUID id,
            UUID packageId,
            SourceKind kind,
            String originalName,
            UUID assetId,
            int sequence,
            Map<String, Object> metadata
    ) {}

    public record SourceRef(
            SourceRefKind kind,
            Integer pageNumber,
            Integer paragraphNumber,
            UUID assetId,
            Long startTimeMs,
            Long endTimeMs
    ) {}

    public record ContentBlock(
            String id,
            UUID packageId,
            UUID sourceItemId,
            BlockType type,
            int sequence,
            String content,
            SourceRef sourceRef,
            double confidence,
            Map<String, Object> metadata
    ) {}

    public record JobMetrics(
            long durationMs,
            String provider,
            String model,
            int inputTokens,
            int outputTokens,
            int externalRequestCount
    ) {
        public static JobMetrics empty() {
            return new JobMetrics(0, null, null, 0, 0, 0);
        }
    }

    public record ProcessingJob(
            int schemaVersion,
            UUID id,
            UUID packageId,
            JobStage stage,
            JobStatus status,
            int attempt,
            int progress,
            String errorCode,
            String errorMessage,
            boolean retryable,
            String inputFingerprint,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            JobMetrics metrics
    ) {
        public ProcessingJob running() {
            return new ProcessingJob(schemaVersion, id, packageId, stage, JobStatus.RUNNING, attempt, progress,
                    null, null, false, inputFingerprint, OffsetDateTime.now(), null, metrics);
        }

        public ProcessingJob succeeded(JobMetrics nextMetrics) {
            return new ProcessingJob(schemaVersion, id, packageId, stage, JobStatus.SUCCEEDED, attempt, 100,
                    null, null, false, inputFingerprint, startedAt, OffsetDateTime.now(), nextMetrics);
        }

        public ProcessingJob failed(String code, String message, boolean canRetry) {
            return new ProcessingJob(schemaVersion, id, packageId, stage, JobStatus.FAILED, attempt, progress,
                    code, message, canRetry, inputFingerprint, startedAt, OffsetDateTime.now(), metrics);
        }
    }

    public record StoredAsset(UUID id, String originalName, String contentType, long size, String relativePath) {}

    public record NoteOutput(
            int schemaVersion,
            String title,
            String markdownFile,
            int citationCount,
            List<UUID> sourceImageAssetIds,
            String diagramTitle,
            String diagramMermaidFile,
            UUID abstractIllustrationAssetId,
            UUID illustrationAssetId,
            UUID whiteboardIllustrationAssetId,
            String model,
            String promptVersion,
            OffsetDateTime generatedAt
    ) {}

    public record StudyGuide(
            int schemaVersion,
            String overview,
            List<String> targetAudience,
            String difficulty,
            int estimatedMinutes,
            List<String> prerequisites,
            List<String> learningObjectives,
            List<String> recommendedSequence,
            List<String> coreKnowledgePoints,
            List<String> keyPoints,
            List<String> difficultPoints,
            List<String> commonMistakes,
            List<String> interviewFocus,
            List<String> exercises,
            List<Map<String, Object>> reviewSchedule,
            List<String> completenessWarnings,
            List<String> aiRiskWarnings
    ) {}
}
