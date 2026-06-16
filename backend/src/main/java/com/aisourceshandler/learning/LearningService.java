package com.aisourceshandler.learning;

import com.aisourceshandler.domain.Models.SourcePackage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

import static com.aisourceshandler.learning.LearningModels.*;

@Service
public class LearningService {
    private static final String EVENT_CATEGORY_LEARNING = "LEARNING";
    private static final String EVENT_CATEGORY_CONTENT = "CONTENT";
    private final LearningRepository repository;
    private static final ZoneId LEARNING_ZONE = ZoneId.of("Asia/Shanghai");

    public LearningService(LearningRepository repository) {
        this.repository = repository;
    }

    public Map<UUID, MasteryView> masteryFor(String ownerId, Collection<UUID> packageIds) {
        if (packageIds.isEmpty()) return Map.of();
        return repository.findStates(ownerId, PACKAGE_SUBJECT, packageIds).stream()
                .collect(Collectors.toUnmodifiableMap(MasteryState::subjectId, this::view));
    }

    public MasteryView masteryFor(String ownerId, UUID packageId) {
        return repository.findState(ownerId, PACKAGE_SUBJECT, packageId)
                .map(this::view)
                .orElseGet(() -> MasteryView.active(packageId));
    }

    public LearningOverview overview(String ownerId, int trendDays, int keywordDays) {
        int boundedTrendDays = Math.max(1, Math.min(trendDays, 30));
        int boundedKeywordDays = Math.max(boundedTrendDays, Math.min(keywordDays, 90));
        OffsetDateTime now = OffsetDateTime.now(LEARNING_ZONE);
        LocalDate today = now.toLocalDate();
        OffsetDateTime since = today.minusDays(Math.max(365, boundedKeywordDays) - 1L)
                .atStartOfDay(LEARNING_ZONE).toOffsetDateTime();
        OffsetDateTime keywordCutoff = today.minusDays(boundedKeywordDays - 1L)
                .atStartOfDay(LEARNING_ZONE).toOffsetDateTime();
        List<MasteryState> allMastered = repository.findMasteredStates(ownerId, PACKAGE_SUBJECT);
        List<MasteryState> currentMastered = allMastered.stream()
                .filter(state -> state.sourceDeletedAt() == null)
                .toList();
        List<MasteryState> deletedMasteredStates = allMastered.stream()
                .filter(state -> state.sourceDeletedAt() != null)
                .sorted(Comparator.comparing(MasteryState::sourceDeletedAt).reversed())
                .toList();
        Set<UUID> masteredHistoryIds = allMastered.stream()
                .map(MasteryState::subjectId)
                .collect(Collectors.toSet());
        List<MasteredEventSnapshot> events = repository.findMasteredEventsSince(ownerId, since).stream()
                .filter(event -> masteredHistoryIds.contains(event.packageId()))
                .toList();

        Map<LocalDate, Integer> counts = events.stream().collect(Collectors.groupingBy(
                event -> event.occurredAt().atZoneSameInstant(LEARNING_ZONE).toLocalDate(),
                Collectors.collectingAndThen(
                        Collectors.mapping(MasteredEventSnapshot::packageId, Collectors.toSet()),
                        Set::size)));
        List<LearningTrendPoint> trend = new ArrayList<>();
        for (int offset = boundedTrendDays - 1; offset >= 0; offset--) {
            LocalDate date = today.minusDays(offset);
            trend.add(new LearningTrendPoint(date, counts.getOrDefault(date, 0)));
        }
        LocalDate weekStart = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        int thisWeek = events.stream()
                .filter(event -> !event.occurredAt().atZoneSameInstant(LEARNING_ZONE)
                        .toLocalDate().isBefore(weekStart))
                .map(MasteredEventSnapshot::packageId).collect(Collectors.toSet()).size();
        int streak = 0;
        for (LocalDate date = today; counts.getOrDefault(date, 0) > 0; date = date.minusDays(1)) streak++;

        Map<String, KeywordAccumulator> keywordStats = new HashMap<>();
        currentMastered.stream()
                .filter(state -> state.masteredAt() != null && !state.masteredAt().isBefore(keywordCutoff))
                .forEach(state -> state.keywordsSnapshot().forEach(keyword -> keywordStats.compute(keyword, (key, value) ->
                        value == null ? new KeywordAccumulator(1, state.masteredAt())
                                : new KeywordAccumulator(value.count() + 1,
                                value.last().isAfter(state.masteredAt()) ? value.last() : state.masteredAt()))));
        List<LearningKeyword> keywords = keywordStats.entrySet().stream()
                .map(entry -> new LearningKeyword(entry.getKey(), entry.getValue().count(), entry.getValue().last()))
                .sorted(Comparator.comparingInt(LearningKeyword::count).reversed()
                        .thenComparing(LearningKeyword::lastMasteredAt, Comparator.reverseOrder()))
                .limit(10).toList();
        List<RecentMastered> recent = currentMastered.stream().limit(5)
                .map(state -> new RecentMastered(state.subjectId(), state.titleSnapshot(),
                        state.keywordsSnapshot(), state.masteredAt()))
                .toList();
        List<DeletedMastered> deletedMastered = deletedMasteredStates.stream().limit(20)
                .map(state -> new DeletedMastered(state.subjectId(), state.titleSnapshot(),
                        state.keywordsSnapshot(), state.masteredAt(), state.sourceDeletedAt()))
                .toList();
        return new LearningOverview(currentMastered.size(), deletedMasteredStates.size(), thisWeek,
                streak, trend, keywords, recent, deletedMastered);
    }

    @Transactional
    public MasteryView setMastery(SourcePackage sourcePackage, List<String> keywords, boolean mastered) {
        OffsetDateTime now = now();
        MasteryState desired = new MasteryState(
                sourcePackage.ownerId(), PACKAGE_SUBJECT, sourcePackage.id(), mastered,
                mastered ? "MASTERED" : "UNSPECIFIED", snapshotTitle(sourcePackage.title()),
                snapshotKeywords(keywords), mastered ? now : null, mastered ? now : null,
                null, 0, now, now);

        int changed;
        if (mastered) {
            changed = repository.insertMasteredIfAbsent(desired);
            if (changed == 0) changed = repository.updateMasteryIfChanged(desired);
        } else {
            changed = repository.updateMasteryIfChanged(desired);
        }
        if (changed > 0) {
            append(sourcePackage, keywords, mastered ? "PACKAGE_MASTERED" : "PACKAGE_UNMASTERED",
                    EVENT_CATEGORY_LEARNING, Map.of("mastered", mastered), now);
        }
        return masteryFor(sourcePackage.ownerId(), sourcePackage.id());
    }

    @Transactional
    public void recordPackageCreated(SourcePackage sourcePackage) {
        append(sourcePackage, List.of(), "PACKAGE_CREATED", EVENT_CATEGORY_CONTENT, Map.of(), now());
    }

    @Transactional
    public void recordDeleteRequested(SourcePackage sourcePackage, List<String> keywords) {
        append(sourcePackage, keywords, "PACKAGE_DELETE_REQUESTED", EVENT_CATEGORY_CONTENT, Map.of(), now());
    }

    @Transactional
    public void recordDeleted(SourcePackage sourcePackage, List<String> keywords) {
        OffsetDateTime now = now();
        repository.markSourceDeleted(sourcePackage.ownerId(), PACKAGE_SUBJECT, sourcePackage.id(),
                snapshotTitle(sourcePackage.title()), snapshotKeywords(keywords), now);
        append(sourcePackage, keywords, "PACKAGE_DELETED", EVENT_CATEGORY_CONTENT,
                Map.of("sourceDeleted", true), now);
    }

    @Transactional
    public void recordDeleteFailed(SourcePackage sourcePackage, List<String> keywords, RuntimeException failure) {
        String reason = Optional.ofNullable(failure.getMessage()).orElse(failure.getClass().getSimpleName());
        append(sourcePackage, keywords, "PACKAGE_DELETE_FAILED", EVENT_CATEGORY_CONTENT,
                Map.of("reason", reason.substring(0, Math.min(reason.length(), 500))), now());
    }

    private void append(SourcePackage sourcePackage, List<String> keywords, String eventType,
                        String category, Map<String, Object> context, OffsetDateTime occurredAt) {
        repository.appendEvent(new ActivityEvent(
                UUID.randomUUID(), sourcePackage.ownerId(), category, eventType, PACKAGE_SUBJECT,
                sourcePackage.id(), sourcePackage.id(), snapshotTitle(sourcePackage.title()),
                snapshotKeywords(keywords), context, occurredAt));
    }

    private MasteryView view(MasteryState state) {
        return new MasteryView(state.subjectId(), state.mastered(), state.masteredAt(), state.updatedAt());
    }

    private String snapshotTitle(String title) {
        String value = title == null || title.isBlank() ? "未命名资料" : title.strip();
        return value.substring(0, Math.min(value.length(), 255));
    }

    private List<String> snapshotKeywords(List<String> keywords) {
        if (keywords == null) return List.of();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank()) values.add(keyword.strip());
            if (values.size() == 10) break;
        }
        return List.copyOf(values);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private record KeywordAccumulator(int count, OffsetDateTime last) {}
}
