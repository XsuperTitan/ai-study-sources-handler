package com.aisourceshandler.api;

import com.aisourceshandler.learning.LearningPlanService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.aisourceshandler.learning.LearningModels.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class LearningPlanControllerTest {
    @Test
    void returnsCurrentPlan() throws Exception {
        LearningPlanService service = mock(LearningPlanService.class);
        when(service.currentPlan("local-user")).thenReturn(planView());
        MockMvc mvc = mvc(service);

        mvc.perform(get("/api/v1/learning/plan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Java plan"))
                .andExpect(jsonPath("$.progress").value(0));
    }

    @Test
    void savesPackageSelection() throws Exception {
        UUID packageId = UUID.randomUUID();
        LearningPlanService service = mock(LearningPlanService.class);
        when(service.savePackages(eq("local-user"), anyList())).thenReturn(planView());
        MockMvc mvc = mvc(service);

        mvc.perform(put("/api/v1/learning/plan/packages")
                        .contentType("application/json")
                        .content("{\"packageIds\":[\"" + packageId + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packages[0].packageId").exists());

        verify(service).savePackages("local-user", List.of(packageId));
    }

    @Test
    void generatesPlan() throws Exception {
        LearningPlanService service = mock(LearningPlanService.class);
        when(service.generatePlan("local-user")).thenReturn(planView());
        MockMvc mvc = mvc(service);

        mvc.perform(post("/api/v1/learning/plan/generate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].title").value("Read"));
    }

    @Test
    void resetsPlan() throws Exception {
        LearningPlanService service = mock(LearningPlanService.class);
        when(service.resetPlan("local-user")).thenReturn(LearningPlanView.empty());
        MockMvc mvc = mvc(service);

        mvc.perform(delete("/api/v1/learning/plan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progress").value(0))
                .andExpect(jsonPath("$.packages").isEmpty())
                .andExpect(jsonPath("$.steps").isEmpty());

        verify(service).resetPlan("local-user");
    }

    @Test
    void updatesPlanStep() throws Exception {
        UUID stepId = UUID.randomUUID();
        LearningPlanService service = mock(LearningPlanService.class);
        when(service.updateStep("local-user", stepId, true)).thenReturn(donePlanView(stepId));
        MockMvc mvc = mvc(service);

        mvc.perform(patch("/api/v1/learning/plan/steps/{stepId}", stepId)
                        .contentType("application/json")
                        .content("{\"completed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.progress").value(100));

        verify(service).updateStep("local-user", stepId, true);
    }

    @Test
    void surfacesServiceErrors() throws Exception {
        LearningPlanService service = mock(LearningPlanService.class);
        when(service.generatePlan("local-user")).thenThrow(new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE, "DEEPSEEK_NOT_CONFIGURED",
                "AI 服务尚未配置。", false));
        MockMvc mvc = mvc(service);

        mvc.perform(post("/api/v1/learning/plan/generate"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("DEEPSEEK_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void recordsStudySession() throws Exception {
        UUID stepId = UUID.randomUUID();
        LearningPlanService service = mock(LearningPlanService.class);
        when(service.recordStudySession(eq("local-user"), eq(stepId), eq(25), eq("notes"), any()))
                .thenReturn(planView());
        MockMvc mvc = mvc(service);

        mvc.perform(post("/api/v1/learning/plan/steps/{stepId}/sessions", stepId)
                        .contentType("application/json")
                        .content("{\"minutes\":25,\"note\":\"notes\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Java plan"));
    }

    @Test
    void updatesStepSchedule() throws Exception {
        UUID stepId = UUID.randomUUID();
        LearningPlanService service = mock(LearningPlanService.class);
        when(service.updateSchedule(eq("local-user"), eq(stepId), eq(LocalDate.parse("2026-06-18")),
                eq(35), eq("adjusted"))).thenReturn(planView());
        MockMvc mvc = mvc(service);

        mvc.perform(patch("/api/v1/learning/plan/steps/{stepId}/schedule", stepId)
                        .contentType("application/json")
                        .content("{\"scheduledDate\":\"2026-06-18\",\"estimatedMinutes\":35,\"reflection\":\"adjusted\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[0].title").value("Read"));
    }

    @Test
    void createsAndAppliesReplanProposal() throws Exception {
        UUID proposalId = UUID.randomUUID();
        LearningPlanService service = mock(LearningPlanService.class);
        when(service.replan(eq("local-user"), eq("busy"))).thenReturn(new LearningPlanReplanProposal(
                proposalId, "Move practice later", List.of(), java.time.OffsetDateTime.now()));
        when(service.applyReplan("local-user", proposalId)).thenReturn(planView());
        MockMvc mvc = mvc(service);

        mvc.perform(post("/api/v1/learning/plan/replan")
                        .contentType("application/json")
                        .content("{\"feedback\":\"busy\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposalId").value(proposalId.toString()));

        mvc.perform(post("/api/v1/learning/plan/replan/{proposalId}/apply", proposalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Java plan"));
    }

    private MockMvc mvc(LearningPlanService service) {
        return standaloneSetup(new LearningPlanController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .build();
    }

    private LearningPlanView planView() {
        UUID packageId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        return new LearningPlanView("Java plan", "Overview", 30, 0,
                "This week has 1 task.",
                List.of(),
                List.of(new LearningPlanPackage(packageId, "Java", List.of("Thread"), "READY", 0)),
                List.of(new LearningPlanStep(stepId, "Read", "Read source", List.of(packageId),
                        30, LocalDate.now(), 0, "Read", "", PlanStepStatus.IN_PROGRESS, 0, null)),
                null, null, 0, null);
    }

    private LearningPlanView donePlanView(UUID stepId) {
        return new LearningPlanView("Java plan", "Overview", 30, 100, "This week has 1 task.",
                List.of(),
                List.of(),
                List.of(new LearningPlanStep(stepId, "Read", "Read source", List.of(),
                        30, LocalDate.now(), 0, "Read", "", PlanStepStatus.DONE, 0,
                        java.time.OffsetDateTime.now())),
                null, null, 0, null);
    }
}
