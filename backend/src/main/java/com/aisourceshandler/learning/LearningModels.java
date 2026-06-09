package com.aisourceshandler.learning;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class LearningModels {
    private LearningModels() {}

    public static final String PACKAGE_SUBJECT = "PACKAGE";

    public enum MasteryFilter {
        ACTIVE,
        MASTERED,
        ALL
    }

    public record MasteryState(
            String ownerId,
            String subjectType,
            UUID subjectId,
            boolean mastered,
            String masteryLevel,
            String titleSnapshot,
            List<String> keywordsSnapshot,
            OffsetDateTime firstMasteredAt,
            OffsetDateTime masteredAt,
            OffsetDateTime sourceDeletedAt,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {}

    public record MasteryView(
            UUID packageId,
            boolean mastered,
            OffsetDateTime masteredAt,
            OffsetDateTime updatedAt
    ) {
        public static MasteryView active(UUID packageId) {
            return new MasteryView(packageId, false, null, null);
        }
    }

    public record ActivityEvent(
            UUID eventId,
            String ownerId,
            String category,
            String eventType,
            String subjectType,
            UUID subjectId,
            UUID packageId,
            String titleSnapshot,
            List<String> keywordsSnapshot,
            Map<String, Object> context,
            OffsetDateTime occurredAt
    ) {}
}
