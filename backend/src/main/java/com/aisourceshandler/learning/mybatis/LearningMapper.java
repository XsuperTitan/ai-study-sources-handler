package com.aisourceshandler.learning.mybatis;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.aisourceshandler.learning.mybatis.LearningRows.*;

@Mapper
interface LearningMapper {
    Optional<MasteryStateRow> findState(@Param("ownerId") String ownerId,
                                        @Param("subjectType") String subjectType,
                                        @Param("subjectId") String subjectId);

    List<MasteryStateRow> findStates(@Param("ownerId") String ownerId,
                                     @Param("subjectType") String subjectType,
                                     @Param("subjectIds") Collection<String> subjectIds);

    List<MasteryStateRow> findMasteredStates(@Param("ownerId") String ownerId,
                                             @Param("subjectType") String subjectType);

    List<MasteredEventRow> findMasteredEventsSince(@Param("ownerId") String ownerId,
                                                   @Param("since") LocalDateTime since);

    int insertMasteredIfAbsent(MasteryStateRow state);

    int updateMasteryIfChanged(MasteryStateRow state);

    int markSourceDeleted(@Param("ownerId") String ownerId,
                          @Param("subjectType") String subjectType,
                          @Param("subjectId") String subjectId,
                          @Param("titleSnapshot") String titleSnapshot,
                          @Param("keywordsSnapshot") String keywordsSnapshot,
                          @Param("sourceDeletedAt") LocalDateTime sourceDeletedAt);

    void insertEvent(ActivityEventRow event);

    Optional<LearningPlanRow> findActivePlan(@Param("ownerId") String ownerId);

    int insertActivePlan(LearningPlanRow plan);

    int updatePlanGenerated(LearningPlanRow plan);

    List<LearningPlanPackageRow> findPlanPackages(@Param("planId") String planId);

    void deletePlanPackages(@Param("planId") String planId);

    void insertPlanPackage(LearningPlanPackageRow planPackage);

    List<LearningPlanStepRow> findPlanSteps(@Param("planId") String planId);

    void deletePlanSteps(@Param("planId") String planId);

    void insertPlanStep(LearningPlanStepRow step);

    int updatePlanStepStatus(@Param("ownerId") String ownerId,
                             @Param("stepId") String stepId,
                             @Param("status") String status,
                             @Param("completedAt") LocalDateTime completedAt,
                             @Param("updatedAt") LocalDateTime updatedAt);

    int updatePlanStepSchedule(@Param("ownerId") String ownerId,
                               @Param("stepId") String stepId,
                               @Param("scheduledDate") LocalDateTime scheduledDate,
                               @Param("estimatedMinutes") int estimatedMinutes,
                               @Param("reflection") String reflection,
                               @Param("updatedAt") LocalDateTime updatedAt);

    int insertStudySession(@Param("ownerId") String ownerId,
                           @Param("session") LearningPlanStudySessionRow session,
                           @Param("updatedAt") LocalDateTime updatedAt);

    int incrementStepActualMinutes(@Param("ownerId") String ownerId,
                                   @Param("stepId") String stepId,
                                   @Param("minutes") int minutes,
                                   @Param("updatedAt") LocalDateTime updatedAt);

    void insertPlanVersion(LearningPlanVersionRow version);

    void insertReplanProposal(LearningPlanReplanProposalRow proposal);

    Optional<LearningPlanReplanProposalRow> findReplanProposal(@Param("ownerId") String ownerId,
                                                               @Param("proposalId") String proposalId);

    void deleteReplanProposals(@Param("planId") String planId);

    int touchPlan(@Param("ownerId") String ownerId,
                  @Param("planId") String planId,
                  @Param("updatedAt") LocalDateTime updatedAt);
}
