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

    record MasteredEventRow(
            String packageId,
            String titleSnapshot,
            String keywordsSnapshot,
            LocalDateTime occurredAt
    ) {}

    record LearningPlanRow(
            String planId,
            String ownerId,
            String title,
            String overview,
            int estimatedMinutes,
            LocalDateTime generatedAt,
            long version,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {}

    record LearningPlanPackageRow(
            String planId,
            String packageId,
            String titleSnapshot,
            String keywordsSnapshot,
            String packageStatusSnapshot,
            int position
    ) {}

    record LearningPlanStepRow(
            String stepId,
            String planId,
            String title,
            String description,
            String packageIdsSnapshot,
            int estimatedMinutes,
            LocalDateTime scheduledDate,
            int actualMinutes,
            String stageLabel,
            String reflection,
            String status,
            int position,
            LocalDateTime completedAt
    ) {}

    record LearningPlanStudySessionRow(
            String sessionId,
            String planId,
            String stepId,
            int minutes,
            String note,
            LocalDateTime studiedAt,
            LocalDateTime createdAt
    ) {}

    record LearningPlanVersionRow(
            String versionId,
            String planId,
            long version,
            String eventType,
            String inputSnapshot,
            String outputSnapshot,
            LocalDateTime createdAt
    ) {}

    record LearningPlanReplanProposalRow(
            String proposalId,
            String planId,
            String summary,
            String stepsSnapshot,
            String inputSnapshot,
            String outputSnapshot,
            LocalDateTime createdAt
    ) {}
}
