package com.aisourceshandler.learning;

import com.aisourceshandler.api.ApiException;
import com.aisourceshandler.domain.Models.*;
import com.aisourceshandler.infrastructure.AiProviders;
import com.aisourceshandler.infrastructure.LocalStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.aisourceshandler.learning.LearningModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LearningPlanServiceTest {
    @Test
    void savesPackageSelectionWithStableOrderAndDeduplication() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        LearningPlanRepository repository = mockRepository();
        LocalStore store = mock(LocalStore.class);
        when(store.findPackage(first)).thenReturn(Optional.of(sourcePackage(first, PackageStatus.READY, "Java")));
        when(store.findPackage(second)).thenReturn(Optional.of(sourcePackage(second, PackageStatus.PROCESSING, "Kafka")));
        when(store.readJsonOutput(any(), eq("outputs/report.json"), eq(StudyGuide.class))).thenReturn(Optional.empty());
        LearningPlanService service = service(repository, store, mock(AiProviders.class));

        service.savePackages("local-user", List.of(first, second, first));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LearningPlanPackage>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).replacePlanPackages(any(), captor.capture());
        assertThat(captor.getValue()).extracting(LearningPlanPackage::packageId)
                .containsExactly(first, second);
        assertThat(captor.getValue()).extracting(LearningPlanPackage::position)
                .containsExactly(0, 1);
    }

    @Test
    void blocksGenerationWhenASelectedPackageIsNotReady() {
        UUID packageId = UUID.randomUUID();
        LearningPlanRepository repository = mockRepository();
        when(repository.findPlanPackages(any())).thenReturn(List.of(
                new LearningPlanPackage(packageId, "Processing", List.of(), "PROCESSING", 0)));
        LocalStore store = mock(LocalStore.class);
        when(store.findPackage(packageId))
                .thenReturn(Optional.of(sourcePackage(packageId, PackageStatus.PROCESSING, "Processing")));
        LearningPlanService service = service(repository, store, mock(AiProviders.class));

        assertThatThrownBy(() -> service.generatePlan("local-user"))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.errorCode())
                                .isEqualTo("LEARNING_PLAN_PACKAGE_NOT_READY"));

        verify(repository, never()).replacePlanSteps(any(), anyList());
    }

    @Test
    void generatesStepsFromAiJsonAndCalculatesProgress() {
        UUID packageId = UUID.randomUUID();
        LearningPlanRepository repository = mockRepository();
        when(repository.findPlanPackages(any())).thenReturn(List.of(
                new LearningPlanPackage(packageId, "Java", List.of("Thread"), "READY", 0)));
        LocalStore store = mock(LocalStore.class);
        when(store.findPackage(packageId)).thenReturn(Optional.of(sourcePackage(packageId, PackageStatus.READY, "Java")));
        when(store.readJsonOutput(any(), eq("outputs/report.json"), eq(StudyGuide.class)))
                .thenReturn(Optional.of(studyGuide()));
        AiProviders ai = mock(AiProviders.class);
        when(ai.deepSeekJson(anyString(), anyString())).thenReturn(new AiProviders.AiResult("""
                {
                  "title": "Java 并发计划",
                  "overview": "先理解线程，再做复盘。",
                  "estimatedMinutes": 45,
                  "steps": [{
                    "title": "线程池基础",
                    "description": "阅读资料并完成复述。",
                    "packageIds": ["%s"],
                    "estimatedMinutes": 45
                  }]
                }
                """.formatted(packageId), JobMetrics.empty()));
        LearningPlanService service = service(repository, store, ai);

        LearningPlanView result = service.generatePlan("local-user");

        assertThat(result.title()).isEqualTo("Java 并发计划");
        assertThat(result.progress()).isZero();
        assertThat(result.steps()).singleElement().satisfies(step -> {
            assertThat(step.status()).isEqualTo(PlanStepStatus.IN_PROGRESS);
            assertThat(step.packageIds()).containsExactly(packageId);
        });
        verify(repository).updatePlanGenerated(any());
        verify(repository).replacePlanSteps(any(), anyList());
    }

    @Test
    void invalidAiJsonIsRetryable() {
        UUID packageId = UUID.randomUUID();
        LearningPlanRepository repository = mockRepository();
        when(repository.findPlanPackages(any())).thenReturn(List.of(
                new LearningPlanPackage(packageId, "Java", List.of(), "READY", 0)));
        LocalStore store = mock(LocalStore.class);
        when(store.findPackage(packageId)).thenReturn(Optional.of(sourcePackage(packageId, PackageStatus.READY, "Java")));
        AiProviders ai = mock(AiProviders.class);
        when(ai.deepSeekJson(anyString(), anyString()))
                .thenReturn(new AiProviders.AiResult("{bad json", JobMetrics.empty()));
        LearningPlanService service = service(repository, store, ai);

        assertThatThrownBy(() -> service.generatePlan("local-user"))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo("LEARNING_PLAN_RESPONSE_INVALID");
                    assertThat(exception.retryable()).isTrue();
                });
    }

    @Test
    void marksStepDoneAndReturnsUpdatedPlan() {
        UUID stepId = UUID.randomUUID();
        LearningPlanRepository repository = mockRepository();
        when(repository.updateStepStatus(eq("local-user"), eq(stepId), eq(PlanStepStatus.DONE),
                any(), any())).thenReturn(1);
        when(repository.findActivePlan("local-user")).thenReturn(Optional.of(plan()));
        when(repository.findPlanSteps(any())).thenReturn(List.of(
                new LearningPlanStep(stepId, "Read", "Read", List.of(), 10,
                        LocalDate.now(), 0, "理解", "", PlanStepStatus.DONE, 0, OffsetDateTime.now())));
        LearningPlanService service = service(repository, mock(LocalStore.class), mock(AiProviders.class));

        LearningPlanView result = service.updateStep("local-user", stepId, true);

        assertThat(result.progress()).isEqualTo(100);
        assertThat(result.steps().getFirst().status()).isEqualTo(PlanStepStatus.DONE);
    }

    @Test
    void recordsStudySessionWithoutCompletingStep() {
        UUID stepId = UUID.randomUUID();
        LearningPlanRepository repository = mockRepository();
        when(repository.addStudySession(eq("local-user"), eq(stepId), any(), any())).thenReturn(1);
        when(repository.findActivePlan("local-user")).thenReturn(Optional.of(plan()));
        when(repository.findPlanSteps(any())).thenReturn(List.of(
                new LearningPlanStep(stepId, "Read", "Read", List.of(), 20,
                        LocalDate.now(), 15, "理解", "good", PlanStepStatus.TODO, 0, null)));
        LearningPlanService service = service(repository, mock(LocalStore.class), mock(AiProviders.class));

        LearningPlanView result = service.recordStudySession("local-user", stepId, 15, "good", null);

        assertThat(result.steps().getFirst().actualMinutes()).isEqualTo(15);
        assertThat(result.steps().getFirst().status()).isEqualTo(PlanStepStatus.IN_PROGRESS);
        verify(repository).addStudySession(eq("local-user"), eq(stepId), any(), any());
    }

    @Test
    void createsReplanProposalWithoutReplacingCurrentSteps() {
        UUID packageId = UUID.randomUUID();
        UUID doneStepId = UUID.randomUUID();
        LearningPlanRepository repository = mockRepository();
        when(repository.findPlanPackages(any())).thenReturn(List.of(
                new LearningPlanPackage(packageId, "Java", List.of("Thread"), "READY", 0)));
        when(repository.findPlanSteps(any())).thenReturn(List.of(
                new LearningPlanStep(doneStepId, "Done", "Done", List.of(packageId), 10,
                        LocalDate.now(), 10, "理解", "", PlanStepStatus.DONE, 0, OffsetDateTime.now())));
        LocalStore store = mock(LocalStore.class);
        when(store.findPackage(packageId)).thenReturn(Optional.of(sourcePackage(packageId, PackageStatus.READY, "Java")));
        AiProviders ai = mock(AiProviders.class);
        when(ai.deepSeekJson(anyString(), anyString())).thenReturn(new AiProviders.AiResult("""
                {"title":"Replan","overview":"Move practice later","estimatedMinutes":20,
                 "steps":[{"title":"Practice","description":"Do drills","packageIds":["%s"],"estimatedMinutes":20}]}
                """.formatted(packageId), JobMetrics.empty()));
        LearningPlanService service = service(repository, store, ai);

        LearningPlanReplanProposal proposal = service.replan("local-user", "busy today");

        assertThat(proposal.steps()).hasSize(2);
        assertThat(proposal.steps().getFirst().stepId()).isEqualTo(doneStepId);
        verify(repository, never()).replacePlanSteps(any(), anyList());
        verify(repository).saveReplanProposal(any(), eq(proposal), anyString(), anyString());
    }

    @Test
    void resetsActivePlanToEmptyView() {
        LearningPlanRepository repository = mockRepository();
        LearningPlanService service = service(repository, mock(LocalStore.class), mock(AiProviders.class));

        LearningPlanView result = service.resetPlan("local-user");

        assertThat(result.packages()).isEmpty();
        assertThat(result.steps()).isEmpty();
        assertThat(result.progress()).isZero();
        verify(repository).deleteActivePlan("local-user");
    }

    private LearningPlanRepository mockRepository() {
        LearningPlanRepository repository = mock(LearningPlanRepository.class);
        when(repository.ensureActivePlan(anyString())).thenReturn(plan());
        when(repository.findPlanPackages(any())).thenReturn(List.of());
        when(repository.findPlanSteps(any())).thenReturn(List.of());
        return repository;
    }

    private LearningPlanService service(LearningPlanRepository repository, LocalStore store, AiProviders ai) {
        return new LearningPlanService(repository, store, ai, new ObjectMapper());
    }

    private LearningPlanRepository.PlanRecord plan() {
        OffsetDateTime now = OffsetDateTime.now();
        return new LearningPlanRepository.PlanRecord(UUID.randomUUID(), "local-user", "", "", 0, null, 0, now, now);
    }

    private SourcePackage sourcePackage(UUID id, PackageStatus status, String title) {
        OffsetDateTime now = OffsetDateTime.now();
        return new SourcePackage(1, id, "local-user", title,
                PackageType.MIXED, status, JobStage.ILLUSTRATION, 100,
                PackageOptions.defaults(), new ArrayList<>(), new ArrayList<>(), now, now);
    }

    private StudyGuide studyGuide() {
        return new StudyGuide(1, "overview", List.of(), "入门", 30, List.of(),
                List.of("理解线程池"), List.of("先看概念"), List.of("线程池"),
                List.of("任务队列"), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());
    }
}
