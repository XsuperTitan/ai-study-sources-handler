package com.aisourceshandler.rag;

import java.util.List;
import java.util.UUID;

public final class RagModels {
    private RagModels() {}

    public record RagStatus(boolean enabled, boolean embeddingConfigured, boolean chromaAvailable,
                            String collection, long indexedChunks, String message) {}

    public record RagAskRequest(String question, List<UUID> packageIds, Integer topK) {}

    public record RagCitation(String citationId, UUID packageId, String blockId, String title,
                              String excerpt, double score, String sourceKind, Integer pageNumber,
                              Integer paragraphNumber, Long startTimeMs, Long endTimeMs, String assetUrl) {}

    public record RagAnswer(String answerMarkdown, List<String> citationIds, List<RagCitation> citations) {}

    public record ReindexResult(int packagesIndexed, int chunksIndexed, List<UUID> failedPackageIds) {}
}
