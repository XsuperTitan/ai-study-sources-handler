package com.aisourceshandler.learning;

import com.aisourceshandler.api.ApiException;
import com.aisourceshandler.domain.Models.*;
import com.aisourceshandler.infrastructure.AiProviders;
import com.aisourceshandler.infrastructure.LocalStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static com.aisourceshandler.learning.LearningModels.*;

@Service
public class LearningPlanService {
    private static final Set<PackageStatus> GENERATABLE_STATUSES =
            EnumSet.of(PackageStatus.READY, PackageStatus.PARTIALLY_READY);

    private final LearningPlanRepository repository;
    private final LocalStore store;
    private final AiProviders ai;
    private final ObjectMapper mapper;

    public LearningPlanService(LearningPlanRepository repository, LocalStore store, AiProviders ai,
                               ObjectMapper mapper) {
        this.repository = repository;
        this.store = store;
        this.ai = ai;
        this.mapper = mapper.findAndRegisterModules();
    }

    public LearningPlanView currentPlan(String ownerId) {
        return repository.findActivePlan(ownerId)
                .map(plan -> view(plan, repository.findPlanPackages(plan.planId()),
                        repository.findPlanSteps(plan.planId())))
                .orElseGet(LearningPlanView::empty);
    }

    @Transactional
    public LearningPlanView savePackages(String ownerId, List<UUID> packageIds) {
        LearningPlanRepository.PlanRecord plan = repository.ensureActivePlan(ownerId);
        List<LearningPlanPackage> packages = snapshots(packageIds);
        repository.replacePlanPackages(plan.planId(), packages);
        return view(plan, packages, repository.findPlanSteps(plan.planId()));
    }

    @Transactional
    public LearningPlanView generatePlan(String ownerId) {
        LearningPlanRepository.PlanRecord existing = repository.ensureActivePlan(ownerId);
        List<LearningPlanPackage> selected = repository.findPlanPackages(existing.planId());
        if (selected.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "LEARNING_PLAN_EMPTY",
                    "请先把资料卡片加入学习计划。", false);
        }

        List<SourcePackage> sourcePackages = selected.stream()
                .map(item -> requiredPackage(item.packageId()))
                .toList();
        sourcePackages.stream()
                .filter(item -> !GENERATABLE_STATUSES.contains(item.status()))
                .findFirst()
                .ifPresent(item -> {
                    throw new ApiException(HttpStatus.CONFLICT, "LEARNING_PLAN_PACKAGE_NOT_READY",
                            "资料包处理完成后才能生成学习计划：" + item.title(), false);
                });

        String input = userPrompt(sourcePackages);
        AiProviders.AiResult result = ai.deepSeekJson(systemPrompt(), input);
        GeneratedPlan generated = parseGeneratedPlan(result.content(), sourcePackages);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        LearningPlanRepository.PlanRecord next = new LearningPlanRepository.PlanRecord(
                existing.planId(), existing.ownerId(), generated.title(), generated.overview(),
                generated.estimatedMinutes(), now, existing.version() + 1, existing.createdAt(), now);
        repository.updatePlanGenerated(next);
        repository.replacePlanSteps(existing.planId(), generated.steps());
        repository.deleteReplanProposals(existing.planId());
        repository.insertPlanVersion(existing.planId(), next.version(), "GENERATE", input, result.content(), now);
        return view(next, selected, generated.steps());
    }

    @Transactional
    public LearningPlanView updateStep(String ownerId, UUID stepId, boolean completed) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int changed = repository.updateStepStatus(ownerId, stepId,
                completed ? PlanStepStatus.DONE : PlanStepStatus.TODO,
                completed ? now : null, now);
        if (changed == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "LEARNING_PLAN_STEP_NOT_FOUND",
                    "学习计划步骤不存在。", false);
        }
        return currentPlan(ownerId);
    }

    @Transactional
    public LearningPlanView recordStudySession(String ownerId, UUID stepId, int minutes, String note,
                                               OffsetDateTime studiedAt) {
        if (minutes <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LEARNING_PLAN_SESSION_INVALID",
                    "学习时长需要大于 0 分钟。", false);
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        LearningPlanStudySession session = new LearningPlanStudySession(UUID.randomUUID(), stepId, minutes,
                note == null ? "" : note.strip(), studiedAt == null ? now : studiedAt);
        int changed = repository.addStudySession(ownerId, stepId, session, now);
        if (changed == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "LEARNING_PLAN_STEP_NOT_FOUND",
                    "学习计划步骤不存在。", false);
        }
        return currentPlan(ownerId);
    }

    @Transactional
    public LearningPlanView updateSchedule(String ownerId, UUID stepId, LocalDate scheduledDate,
                                           int estimatedMinutes, String reflection) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int changed = repository.updateStepSchedule(ownerId, stepId, scheduledDate, Math.max(0, estimatedMinutes),
                reflection == null ? "" : reflection.strip(), now);
        if (changed == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "LEARNING_PLAN_STEP_NOT_FOUND",
                    "学习计划步骤不存在。", false);
        }
        return currentPlan(ownerId);
    }

    @Transactional
    public LearningPlanReplanProposal replan(String ownerId, String feedback) {
        LearningPlanRepository.PlanRecord plan = repository.ensureActivePlan(ownerId);
        List<LearningPlanPackage> packages = repository.findPlanPackages(plan.planId());
        List<LearningPlanStep> steps = repository.findPlanSteps(plan.planId());
        if (steps.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "LEARNING_PLAN_EMPTY",
                    "请先生成学习计划，再进行重排。", false);
        }
        String input = replanUserPrompt(plan, packages, withDerivedStepStatuses(steps), feedback);
        AiProviders.AiResult result = ai.deepSeekJson(replanSystemPrompt(), input);
        List<SourcePackage> sourcePackages = packages.stream()
                .map(item -> requiredPackage(item.packageId()))
                .toList();
        GeneratedPlan generated = parseGeneratedPlan(result.content(), sourcePackages);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        LearningPlanReplanProposal proposal = new LearningPlanReplanProposal(UUID.randomUUID(),
                textNode(result.content(), "已根据当前进度生成重排建议。"),
                mergeCompletedSteps(steps, generated.steps()), now);
        repository.deleteReplanProposals(plan.planId());
        repository.saveReplanProposal(plan.planId(), proposal, input, result.content());
        return proposal;
    }

    @Transactional
    public LearningPlanView applyReplan(String ownerId, UUID proposalId) {
        LearningPlanRepository.PlanRecord plan = repository.ensureActivePlan(ownerId);
        LearningPlanReplanProposal proposal = repository.findReplanProposal(ownerId, proposalId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "LEARNING_PLAN_REPLAN_NOT_FOUND",
                        "重排建议不存在或已失效。", false));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        repository.replacePlanSteps(plan.planId(), proposal.steps());
        repository.touchPlan(ownerId, plan.planId(), now);
        repository.insertPlanVersion(plan.planId(), plan.version() + 1, "REPLAN",
                json(Map.of("proposalId", proposalId, "summary", proposal.summary())),
                json(Map.of("steps", proposal.steps())), now);
        repository.deleteReplanProposals(plan.planId());
        LearningPlanRepository.PlanRecord next = new LearningPlanRepository.PlanRecord(
                plan.planId(), plan.ownerId(), plan.title(), plan.overview(), plan.estimatedMinutes(),
                plan.generatedAt(), plan.version() + 1, plan.createdAt(), now);
        return view(next, repository.findPlanPackages(plan.planId()), proposal.steps());
    }

    private List<LearningPlanPackage> snapshots(List<UUID> rawPackageIds) {
        LinkedHashSet<UUID> packageIds = new LinkedHashSet<>(rawPackageIds == null ? List.of() : rawPackageIds);
        List<LearningPlanPackage> output = new ArrayList<>();
        int position = 0;
        for (UUID packageId : packageIds) {
            SourcePackage sourcePackage = requiredPackage(packageId);
            output.add(new LearningPlanPackage(packageId, snapshotTitle(sourcePackage.title()),
                    coverKeywords(packageId), sourcePackage.status().name(), position++));
        }
        return List.copyOf(output);
    }

    private LearningPlanView view(LearningPlanRepository.PlanRecord plan, List<LearningPlanPackage> packages,
                                  List<LearningPlanStep> rawSteps) {
        List<LearningPlanStep> steps = withDerivedStepStatuses(rawSteps);
        long done = steps.stream().filter(step -> step.status() == PlanStepStatus.DONE).count();
        int progress = steps.isEmpty() ? 0 : (int) Math.round(done * 100.0 / steps.size());
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<LearningPlanStep> todaySteps = steps.stream()
                .filter(step -> today.equals(step.scheduledDate()))
                .toList();
        return new LearningPlanView(plan.title(), plan.overview(), plan.estimatedMinutes(), progress,
                weeklySummary(steps), todaySteps, packages, steps, plan.generatedAt(), plan.updatedAt(),
                plan.version(), null);
    }

    private List<LearningPlanStep> withDerivedStepStatuses(List<LearningPlanStep> rawSteps) {
        boolean foundCurrent = false;
        List<LearningPlanStep> output = new ArrayList<>();
        for (LearningPlanStep step : rawSteps) {
            PlanStepStatus status = step.status();
            if (status != PlanStepStatus.DONE && !foundCurrent) {
                status = PlanStepStatus.IN_PROGRESS;
                foundCurrent = true;
            } else if (status != PlanStepStatus.DONE) {
                status = PlanStepStatus.TODO;
            }
            output.add(new LearningPlanStep(step.stepId(), step.title(), step.description(), step.packageIds(),
                    step.estimatedMinutes(), step.scheduledDate(), step.actualMinutes(), step.stageLabel(),
                    step.reflection(), status, step.position(), step.completedAt()));
        }
        return List.copyOf(output);
    }

    private String weeklySummary(List<LearningPlanStep> steps) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate weekEnd = today.plusDays(6);
        List<LearningPlanStep> week = steps.stream()
                .filter(step -> step.scheduledDate() != null
                        && !step.scheduledDate().isBefore(today)
                        && !step.scheduledDate().isAfter(weekEnd))
                .toList();
        int minutes = week.stream().mapToInt(LearningPlanStep::estimatedMinutes).sum();
        if (week.isEmpty()) return steps.isEmpty() ? "" : "本周暂无排期，建议从当前进行中的步骤开始。";
        return "本周 " + week.size() + " 个任务，预计 " + minutes + " 分钟。";
    }

    private SourcePackage requiredPackage(UUID packageId) {
        return store.findPackage(packageId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PACKAGE_NOT_FOUND",
                        "资料包不存在。", false));
    }

    private String snapshotTitle(String title) {
        String value = title == null || title.isBlank() ? "未命名资料" : title.strip();
        return value.substring(0, Math.min(value.length(), 255));
    }

    private List<String> coverKeywords(UUID packageId) {
        return store.readJsonOutput(packageId, "outputs/report.json", StudyGuide.class)
                .map(this::coverKeywords)
                .orElse(List.of());
    }

    private List<String> coverKeywords(StudyGuide guide) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (List<String> candidates : List.of(
                guide.coreKnowledgePoints(), guide.keyPoints(), guide.learningObjectives())) {
            if (candidates == null) continue;
            for (String candidate : candidates) {
                if (candidate != null && !candidate.isBlank()) values.add(candidate.strip());
                if (values.size() == 3) return List.copyOf(values);
            }
        }
        return List.copyOf(values);
    }

    private String systemPrompt() {
        return """
                你是学习教练。只输出合法 JSON，不要 Markdown 代码围栏或额外解释。
                根据用户提供的资料包摘要，生成一个可执行学习计划。
                JSON 字段：
                {
                  "title": "计划标题",
                  "overview": "一段计划说明",
                  "estimatedMinutes": 120,
                  "steps": [
                    {
                      "title": "步骤标题",
                      "description": "具体学习动作",
                      "packageIds": ["资料包 UUID"],
                      "estimatedMinutes": 30,
                      "scheduledDate": "2026-06-18",
                      "stageLabel": "理解 / 练习 / 复习"
                    }
                  ]
                }
                steps 建议 3-8 个，必须引用输入中存在的 packageIds，并给出从今天开始的建议排期。
                """;
    }

    private String userPrompt(List<SourcePackage> packages) {
        List<Map<String, Object>> payload = packages.stream().map(sourcePackage -> {
            StudyGuide guide = store.readJsonOutput(sourcePackage.id(), "outputs/report.json", StudyGuide.class)
                    .orElse(null);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("packageId", sourcePackage.id());
            item.put("title", sourcePackage.title());
            item.put("type", sourcePackage.packageType());
            item.put("keywords", guide == null ? List.of() : coverKeywords(guide));
            item.put("overview", guide == null ? "" : guide.overview());
            item.put("learningObjectives", guide == null ? List.of() : guide.learningObjectives());
            item.put("recommendedSequence", guide == null ? List.of() : guide.recommendedSequence());
            item.put("reviewSchedule", guide == null ? List.of() : guide.reviewSchedule());
            item.put("estimatedMinutes", guide == null ? 0 : guide.estimatedMinutes());
            return item;
        }).toList();
        return json(Map.of("today", LocalDate.now(ZoneOffset.UTC), "packages", payload));
    }

    private GeneratedPlan parseGeneratedPlan(String content, List<SourcePackage> sourcePackages) {
        try {
            JsonNode root = mapper.readTree(content);
            Set<UUID> allowed = new LinkedHashSet<>(sourcePackages.stream().map(SourcePackage::id).toList());
            List<LearningPlanStep> steps = new ArrayList<>();
            JsonNode rawSteps = root.path("steps");
            if (rawSteps.isArray()) {
                int position = 0;
                for (JsonNode rawStep : rawSteps) {
                    List<UUID> packageIds = packageIds(rawStep.path("packageIds"), allowed);
                    if (packageIds.isEmpty()) packageIds = List.of(sourcePackages.getFirst().id());
                    steps.add(new LearningPlanStep(UUID.randomUUID(),
                            text(rawStep.path("title"), "学习步骤 " + (position + 1)),
                            text(rawStep.path("description"), "完成本步骤关联资料的阅读和复盘。"),
                            packageIds,
                            Math.max(0, rawStep.path("estimatedMinutes").asInt(0)),
                            scheduledDate(rawStep.path("scheduledDate"), position),
                            0,
                            text(rawStep.path("stageLabel"), ""),
                            text(rawStep.path("reflection"), ""),
                            PlanStepStatus.TODO,
                            position++,
                            null));
                }
            }
            if (steps.isEmpty()) {
                int position = 0;
                for (SourcePackage sourcePackage : sourcePackages) {
                    steps.add(new LearningPlanStep(UUID.randomUUID(), sourcePackage.title(),
                            "阅读学习指南，整理关键概念并完成一次复述。",
                            List.of(sourcePackage.id()), 30, LocalDate.now(ZoneOffset.UTC).plusDays(position),
                            0, "理解", "", PlanStepStatus.TODO, position++, null));
                }
            }
            int estimatedMinutes = root.path("estimatedMinutes").asInt(
                    steps.stream().mapToInt(LearningPlanStep::estimatedMinutes).sum());
            return new GeneratedPlan(
                    text(root.path("title"), "当前学习计划"),
                    text(root.path("overview"), "按资料依赖顺序推进，并在每个阶段完成复盘。"),
                    Math.max(0, estimatedMinutes),
                    List.copyOf(steps));
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "LEARNING_PLAN_RESPONSE_INVALID",
                    "AI 返回的学习计划无法解析，请重试。", true, exception);
        }
    }

    private List<UUID> packageIds(JsonNode node, Set<UUID> allowed) {
        if (!node.isArray()) return List.of();
        LinkedHashSet<UUID> output = new LinkedHashSet<>();
        node.forEach(value -> {
            try {
                UUID packageId = UUID.fromString(value.asText());
                if (allowed.contains(packageId)) output.add(packageId);
            } catch (IllegalArgumentException ignored) {
                // Ignore invalid model references and keep the plan tied to known packages.
            }
        });
        return List.copyOf(output);
    }

    private String text(JsonNode node, String fallback) {
        String value = node.asText("");
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private LocalDate scheduledDate(JsonNode node, int position) {
        String value = node.asText("");
        if (value != null && !value.isBlank()) {
            try {
                return LocalDate.parse(value.strip());
            } catch (Exception ignored) {
                // Fall back to a near-term schedule if the model emits a non-date value.
            }
        }
        return LocalDate.now(ZoneOffset.UTC).plusDays(position);
    }

    private List<LearningPlanStep> mergeCompletedSteps(List<LearningPlanStep> currentSteps,
                                                       List<LearningPlanStep> proposedSteps) {
        List<LearningPlanStep> done = currentSteps.stream()
                .filter(step -> step.status() == PlanStepStatus.DONE)
                .toList();
        List<LearningPlanStep> open = proposedSteps.stream()
                .filter(step -> step.status() != PlanStepStatus.DONE)
                .toList();
        List<LearningPlanStep> merged = new ArrayList<>();
        int position = 0;
        for (LearningPlanStep step : done) merged.add(reposition(step, position++));
        for (LearningPlanStep step : open) merged.add(reposition(step, position++));
        return List.copyOf(merged);
    }

    private LearningPlanStep reposition(LearningPlanStep step, int position) {
        return new LearningPlanStep(step.stepId(), step.title(), step.description(), step.packageIds(),
                step.estimatedMinutes(), step.scheduledDate(), step.actualMinutes(), step.stageLabel(),
                step.reflection(), step.status() == PlanStepStatus.DONE ? PlanStepStatus.DONE : PlanStepStatus.TODO,
                position, step.completedAt());
    }

    private String replanSystemPrompt() {
        return """
                你是学习计划调度教练。只输出合法 JSON，不要 Markdown。
                根据当前计划、完成情况、实际学习分钟和用户反馈，重排未完成步骤。
                已完成步骤不要重新安排。输出字段与生成计划相同，并用 overview 说明调整原因。
                """;
    }

    private String replanUserPrompt(LearningPlanRepository.PlanRecord plan, List<LearningPlanPackage> packages,
                                    List<LearningPlanStep> steps, String feedback) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("today", LocalDate.now(ZoneOffset.UTC));
        payload.put("plan", Map.of(
                "title", plan.title(),
                "overview", plan.overview(),
                "version", plan.version()
        ));
        payload.put("packages", packages);
        payload.put("steps", steps);
        payload.put("feedback", feedback == null ? "" : feedback.strip());
        return json(payload);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize learning plan payload", exception);
        }
    }

    private String textNode(String content, String fallback) {
        try {
            JsonNode root = mapper.readTree(content);
            return text(root.path("overview"), fallback);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private record GeneratedPlan(String title, String overview, int estimatedMinutes,
                                 List<LearningPlanStep> steps) {}
}
