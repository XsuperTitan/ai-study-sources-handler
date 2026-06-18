package com.aisourceshandler.learning;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.aisourceshandler.learning.LearningModels.*;

public interface LearningPlanRepository {
    record PlanRecord(
            UUID planId,
            String ownerId,
            String title,
            String overview,
            int estimatedMinutes,
            OffsetDateTime generatedAt,
            long version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {}

    Optional<PlanRecord> findActivePlan(String ownerId);

    PlanRecord ensureActivePlan(String ownerId);

    int deleteActivePlan(String ownerId);

    List<LearningPlanPackage> findPlanPackages(UUID planId);

    void replacePlanPackages(UUID planId, List<LearningPlanPackage> packages);

    List<LearningPlanStep> findPlanSteps(UUID planId);

    void replacePlanSteps(UUID planId, List<LearningPlanStep> steps);

    void updatePlanGenerated(PlanRecord plan);

    int updateStepStatus(String ownerId, UUID stepId, PlanStepStatus status, OffsetDateTime completedAt,
                         OffsetDateTime updatedAt);

    int updateStepSchedule(String ownerId, UUID stepId, LocalDate scheduledDate, int estimatedMinutes,
                           String reflection, OffsetDateTime updatedAt);

    int addStudySession(String ownerId, UUID stepId, LearningPlanStudySession session, OffsetDateTime updatedAt);

    void insertPlanVersion(UUID planId, long version, String eventType, String inputJson, String outputJson,
                           OffsetDateTime createdAt);

    void saveReplanProposal(UUID planId, LearningPlanReplanProposal proposal, String inputJson, String outputJson);

    Optional<LearningPlanReplanProposal> findReplanProposal(String ownerId, UUID proposalId);

    void deleteReplanProposals(UUID planId);

    int touchPlan(String ownerId, UUID planId, OffsetDateTime updatedAt);
}
