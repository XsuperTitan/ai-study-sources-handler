package com.aisourceshandler.api;

import com.aisourceshandler.learning.LearningPlanService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.aisourceshandler.learning.LearningModels.*;

@RestController
@RequestMapping("/api/v1/learning/plan")
public class LearningPlanController {
    private final LearningPlanService learningPlan;

    public LearningPlanController(LearningPlanService learningPlan) {
        this.learningPlan = learningPlan;
    }

    @GetMapping
    LearningPlanView currentPlan() {
        return learningPlan.currentPlan("local-user");
    }

    @DeleteMapping
    LearningPlanView resetPlan() {
        return learningPlan.resetPlan("local-user");
    }

    @PutMapping("/packages")
    LearningPlanView savePackages(@Valid @RequestBody PlanPackagesRequest request) {
        return learningPlan.savePackages("local-user", request.packageIds());
    }

    @PostMapping("/generate")
    LearningPlanView generatePlan() {
        return learningPlan.generatePlan("local-user");
    }

    @PatchMapping("/steps/{stepId}")
    LearningPlanView updateStep(@PathVariable UUID stepId, @Valid @RequestBody PlanStepUpdateRequest request) {
        return learningPlan.updateStep("local-user", stepId, request.completed());
    }

    @PostMapping("/steps/{stepId}/sessions")
    LearningPlanView recordStudySession(@PathVariable UUID stepId,
                                        @Valid @RequestBody PlanStudySessionRequest request) {
        return learningPlan.recordStudySession("local-user", stepId, request.minutes(), request.note(),
                request.studiedAt());
    }

    @PatchMapping("/steps/{stepId}/schedule")
    LearningPlanView updateSchedule(@PathVariable UUID stepId,
                                    @Valid @RequestBody PlanStepScheduleRequest request) {
        return learningPlan.updateSchedule("local-user", stepId, request.scheduledDate(),
                request.estimatedMinutes(), request.reflection());
    }

    @PostMapping("/replan")
    LearningPlanReplanProposal replan(@RequestBody(required = false) PlanReplanRequest request) {
        return learningPlan.replan("local-user", request == null ? "" : request.feedback());
    }

    @PostMapping("/replan/{proposalId}/apply")
    LearningPlanView applyReplan(@PathVariable UUID proposalId) {
        return learningPlan.applyReplan("local-user", proposalId);
    }

    public record PlanPackagesRequest(@NotNull List<UUID> packageIds) {}

    public record PlanStepUpdateRequest(@NotNull Boolean completed) {}

    public record PlanStudySessionRequest(@Min(1) int minutes, String note, OffsetDateTime studiedAt) {}

    public record PlanStepScheduleRequest(LocalDate scheduledDate, @Min(0) int estimatedMinutes,
                                          String reflection) {}

    public record PlanReplanRequest(String feedback) {}
}
