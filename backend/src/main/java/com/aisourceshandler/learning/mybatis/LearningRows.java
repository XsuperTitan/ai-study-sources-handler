package com.aisourceshandler.learning.mybatis;

import java.time.LocalDateTime;

final class LearningRows {
    private LearningRows() {}

    record MasteryStateRow(
            String ownerId,
            String subjectType,
            String subjectId,
            boolean mastered,
            String masteryLevel,
            String titleSnapshot,
            String keywordsSnapshot,
            LocalDateTime firstMasteredAt,
            LocalDateTime lastMasteredAt,
            LocalDateTime sourceDeletedAt,
            long version,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {}

    record ActivityEventRow(
            String eventId,
            String ownerId,
            String eventCategory,
            String eventType,
            String subjectType,
            String subjectId,
            String packageId,
            String titleSnapshot,
            String keywordsSnapshot,
            String contextJson,
            LocalDateTime occurredAt
    ) {}
}
