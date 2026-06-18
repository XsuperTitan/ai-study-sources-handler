package com.aisourceshandler.learning;

import java.time.LocalDate;
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

    public record MasteredEventSnapshot(
            UUID packageId,
            String title,
            List<String> keywords,
            OffsetDateTime occurredAt
    ) {}

    public record LearningTrendPoint(LocalDate date, int masteredCount) {}

    public record LearningKeyword(String keyword, int count, OffsetDateTime lastMasteredAt) {}

    public record RecentMastered(
            UUID packageId,
            String title,
            List<String> keywords,
            OffsetDateTime masteredAt
    ) {}

    public record DeletedMastered(
            UUID packageId,
            String title,
            List<String> keywords,
            OffsetDateTime masteredAt,
            OffsetDateTime deletedAt
    ) {}

    public record LearningOverview(
            int masteredTotal,
            int deletedMasteredTotal,
            int masteredThisWeek,
            int currentStreakDays,
            List<LearningTrendPoint> trend,
            List<LearningKeyword> recentKeywords,
            List<RecentMastered> recentMastered,
            List<DeletedMastered> deletedMastered
    ) {}

    public enum PlanStepStatus {
        TODO,
        IN_PROGRESS,
        DONE
    }

    public record LearningPlanPackage(
            UUID packageId,
            String title,
            List<String> keywords,
            String status,
            int position
    ) {}

    public record LearningPlanStep(
            UUID stepId,
            String title,
            String description,
            List<UUID> packageIds,
            int estimatedMinutes,
            LocalDate scheduledDate,
            int actualMinutes,
            String stageLabel,
            String reflection,
            PlanStepStatus status,
            int position,
            OffsetDateTime completedAt
    ) {}

    public record LearningPlanStudySession(
            UUID sessionId,
            UUID stepId,
            int minutes,
            String note,
            OffsetDateTime studiedAt
    ) {}

    public record LearningPlanReplanProposal(
            UUID proposalId,
            String summary,
            List<LearningPlanStep> steps,
            OffsetDateTime createdAt
    ) {}

    public record LearningPlanView(
            String title,
            String overview,
            int estimatedMinutes,
            int progress,
            String weeklySummary,
            List<LearningPlanStep> todaySteps,
            List<LearningPlanPackage> packages,
            List<LearningPlanStep> steps,
            OffsetDateTime generatedAt,
            OffsetDateTime updatedAt,
            long version,
            LearningPlanReplanProposal pendingReplanProposal
    ) {
        public static LearningPlanView empty() {
            return new LearningPlanView("", "", 0, 0, "", List.of(), List.of(), List.of(), null, null, 0, null);
        }
    }
}
