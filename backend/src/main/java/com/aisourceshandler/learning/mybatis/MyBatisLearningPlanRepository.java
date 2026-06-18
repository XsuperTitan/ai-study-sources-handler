package com.aisourceshandler.learning.mybatis;

import com.aisourceshandler.learning.LearningPlanRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.aisourceshandler.learning.LearningModels.*;
import static com.aisourceshandler.learning.mybatis.LearningRows.*;

@Repository
public class MyBatisLearningPlanRepository implements LearningPlanRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<UUID>> UUID_LIST = new TypeReference<>() {};
    private static final TypeReference<List<LearningPlanStep>> STEP_LIST = new TypeReference<>() {};

    private final LearningMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisLearningPlanRepository(LearningMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public java.util.Optional<PlanRecord> findActivePlan(String ownerId) {
        return mapper.findActivePlan(ownerId).map(this::domain);
    }

    @Override
    public PlanRecord ensureActivePlan(String ownerId) {
        return findActivePlan(ownerId).orElseGet(() -> {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            UUID planId = UUID.randomUUID();
            mapper.insertActivePlan(new LearningPlanRow(
                    planId.toString(), ownerId, "", "", 0, null, 0, local(now), local(now)));
            return findActivePlan(ownerId).orElseGet(() ->
                    new PlanRecord(planId, ownerId, "", "", 0, null, 0, now, now));
        });
    }

    @Override
    public int deleteActivePlan(String ownerId) {
        return mapper.deleteActivePlan(ownerId);
    }

    @Override
    public List<LearningPlanPackage> findPlanPackages(UUID planId) {
        return mapper.findPlanPackages(planId.toString()).stream()
                .map(row -> new LearningPlanPackage(
                        UUID.fromString(row.packageId()), row.titleSnapshot(), stringList(row.keywordsSnapshot()),
                        row.packageStatusSnapshot(), row.position()))
                .toList();
    }

    @Override
    @Transactional
    public void replacePlanPackages(UUID planId, List<LearningPlanPackage> packages) {
        mapper.deletePlanPackages(planId.toString());
        packages.forEach(planPackage -> mapper.insertPlanPackage(new LearningPlanPackageRow(
                planId.toString(), planPackage.packageId().toString(), planPackage.title(),
                json(planPackage.keywords()), planPackage.status(), planPackage.position())));
    }

    @Override
    public List<LearningPlanStep> findPlanSteps(UUID planId) {
        return mapper.findPlanSteps(planId.toString()).stream()
                .map(row -> new LearningPlanStep(
                        UUID.fromString(row.stepId()), row.title(), row.description(),
                        uuidList(row.packageIdsSnapshot()), row.estimatedMinutes(),
                        date(row.scheduledDate()), row.actualMinutes(), row.stageLabel(), row.reflection(),
                        PlanStepStatus.valueOf(row.status()), row.position(), offset(row.completedAt())))
                .toList();
    }

    @Override
    @Transactional
    public void replacePlanSteps(UUID planId, List<LearningPlanStep> steps) {
        mapper.deletePlanSteps(planId.toString());
        steps.forEach(step -> mapper.insertPlanStep(new LearningPlanStepRow(
                step.stepId().toString(), planId.toString(), step.title(), step.description(),
                json(step.packageIds()), step.estimatedMinutes(), local(step.scheduledDate()),
                step.actualMinutes(), step.stageLabel(), step.reflection(), step.status().name(), step.position(),
                local(step.completedAt()))));
    }

    @Override
    public void updatePlanGenerated(PlanRecord plan) {
        mapper.updatePlanGenerated(new LearningPlanRow(
                plan.planId().toString(), plan.ownerId(), plan.title(), plan.overview(), plan.estimatedMinutes(),
                local(plan.generatedAt()), plan.version(), local(plan.createdAt()), local(plan.updatedAt())));
    }

    @Override
    public int updateStepStatus(String ownerId, UUID stepId, PlanStepStatus status, OffsetDateTime completedAt,
                                OffsetDateTime updatedAt) {
        return mapper.updatePlanStepStatus(ownerId, stepId.toString(), status.name(), local(completedAt),
                local(updatedAt));
    }

    @Override
    public int updateStepSchedule(String ownerId, UUID stepId, LocalDate scheduledDate, int estimatedMinutes,
                                  String reflection, OffsetDateTime updatedAt) {
        return mapper.updatePlanStepSchedule(ownerId, stepId.toString(), local(scheduledDate), estimatedMinutes,
                reflection, local(updatedAt));
    }

    @Override
    public int addStudySession(String ownerId, UUID stepId, LearningPlanStudySession session,
                               OffsetDateTime updatedAt) {
        return mapper.insertStudySession(ownerId, new LearningPlanStudySessionRow(
                session.sessionId().toString(), null, stepId.toString(), session.minutes(), session.note(),
                local(session.studiedAt()), local(updatedAt)), local(updatedAt)) > 0
                ? mapper.incrementStepActualMinutes(ownerId, stepId.toString(), session.minutes(), local(updatedAt))
                : 0;
    }

    @Override
    public void insertPlanVersion(UUID planId, long version, String eventType, String inputJson, String outputJson,
                                  OffsetDateTime createdAt) {
        mapper.insertPlanVersion(new LearningPlanVersionRow(UUID.randomUUID().toString(), planId.toString(), version,
                eventType, inputJson, outputJson, local(createdAt)));
    }

    @Override
    public void saveReplanProposal(UUID planId, LearningPlanReplanProposal proposal, String inputJson,
                                   String outputJson) {
        mapper.insertReplanProposal(new LearningPlanReplanProposalRow(
                proposal.proposalId().toString(), planId.toString(), proposal.summary(), json(proposal.steps()),
                inputJson, outputJson, local(proposal.createdAt())));
    }

    @Override
    public java.util.Optional<LearningPlanReplanProposal> findReplanProposal(String ownerId, UUID proposalId) {
        return mapper.findReplanProposal(ownerId, proposalId.toString()).map(row -> new LearningPlanReplanProposal(
                UUID.fromString(row.proposalId()), row.summary(), stepList(row.stepsSnapshot()),
                offset(row.createdAt())));
    }

    @Override
    public void deleteReplanProposals(UUID planId) {
        mapper.deleteReplanProposals(planId.toString());
    }

    @Override
    public int touchPlan(String ownerId, UUID planId, OffsetDateTime updatedAt) {
        return mapper.touchPlan(ownerId, planId.toString(), local(updatedAt));
    }

    private PlanRecord domain(LearningPlanRow row) {
        return new PlanRecord(UUID.fromString(row.planId()), row.ownerId(), row.title(), row.overview(),
                row.estimatedMinutes(), offset(row.generatedAt()), row.version(), offset(row.createdAt()),
                offset(row.updatedAt()));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize learning plan snapshot", exception);
        }
    }

    private List<String> stringList(String value) {
        try {
            return objectMapper.readValue(value, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to read learning plan keywords", exception);
        }
    }

    private List<UUID> uuidList(String value) {
        try {
            return objectMapper.readValue(value, UUID_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to read learning plan package ids", exception);
        }
    }

    private List<LearningPlanStep> stepList(String value) {
        try {
            return objectMapper.readValue(value, STEP_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to read learning plan proposal steps", exception);
        }
    }

    private LocalDateTime local(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private LocalDateTime local(LocalDate value) {
        return value == null ? null : value.atStartOfDay();
    }

    private OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private LocalDate date(LocalDateTime value) {
        return value == null ? null : value.toLocalDate();
    }
}
