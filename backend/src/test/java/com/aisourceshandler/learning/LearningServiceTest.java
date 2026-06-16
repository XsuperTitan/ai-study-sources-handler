package com.aisourceshandler.learning;

import com.aisourceshandler.domain.Models.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.aisourceshandler.learning.LearningModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LearningServiceTest {
    @Test
    void marksPackageMasteredAndAppendsOneEvent() {
        LearningRepository repository = mock(LearningRepository.class);
        SourcePackage sourcePackage = sourcePackage();
        when(repository.insertMasteredIfAbsent(any())).thenReturn(1);
        when(repository.findState(sourcePackage.ownerId(), PACKAGE_SUBJECT, sourcePackage.id()))
                .thenAnswer(invocation -> Optional.of(state(sourcePackage, true)));
        LearningService service = new LearningService(repository);

        MasteryView result = service.setMastery(sourcePackage, List.of("线程池", "任务队列"), true);

        assertThat(result.mastered()).isTrue();
        ArgumentCaptor<ActivityEvent> event = ArgumentCaptor.forClass(ActivityEvent.class);
        verify(repository).appendEvent(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("PACKAGE_MASTERED");
        assertThat(event.getValue().keywordsSnapshot()).containsExactly("线程池", "任务队列");
    }

    @Test
    void repeatedMasteryRequestDoesNotAppendDuplicateEvent() {
        LearningRepository repository = mock(LearningRepository.class);
        SourcePackage sourcePackage = sourcePackage();
        when(repository.insertMasteredIfAbsent(any())).thenReturn(0);
        when(repository.updateMasteryIfChanged(any())).thenReturn(0);
        when(repository.findState(sourcePackage.ownerId(), PACKAGE_SUBJECT, sourcePackage.id()))
                .thenReturn(Optional.of(state(sourcePackage, true)));
        LearningService service = new LearningService(repository);

        MasteryView result = service.setMastery(sourcePackage, List.of("线程池"), true);

        assertThat(result.mastered()).isTrue();
        verify(repository, never()).appendEvent(any());
    }

    @Test
    void restoresPackageAndAppendsUnmasteredEvent() {
        LearningRepository repository = mock(LearningRepository.class);
        SourcePackage sourcePackage = sourcePackage();
        when(repository.updateMasteryIfChanged(any())).thenReturn(1);
        when(repository.findState(sourcePackage.ownerId(), PACKAGE_SUBJECT, sourcePackage.id()))
                .thenReturn(Optional.of(state(sourcePackage, false)));
        LearningService service = new LearningService(repository);

        MasteryView result = service.setMastery(sourcePackage, List.of(), false);

        assertThat(result.mastered()).isFalse();
        ArgumentCaptor<ActivityEvent> event = ArgumentCaptor.forClass(ActivityEvent.class);
        verify(repository).appendEvent(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("PACKAGE_UNMASTERED");
    }

    @Test
    void buildsLearningOverviewFromCurrentStateAndMasteryEvents() {
        LearningRepository repository = mock(LearningRepository.class);
        SourcePackage sourcePackage = sourcePackage();
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        when(repository.findMasteredStates(sourcePackage.ownerId(), PACKAGE_SUBJECT))
                .thenReturn(List.of(state(sourcePackage, true)));
        when(repository.findMasteredEventsSince(eq(sourcePackage.ownerId()), any()))
                .thenReturn(List.of(
                        new MasteredEventSnapshot(sourcePackage.id(), sourcePackage.title(),
                                List.of("线程池", "任务队列"), now),
                        new MasteredEventSnapshot(UUID.randomUUID(), "并发基础",
                                List.of("线程池"), now.minusDays(1))
                ));
        LearningService service = new LearningService(repository);

        LearningOverview overview = service.overview(sourcePackage.ownerId(), 7, 30);

        assertThat(overview.masteredTotal()).isEqualTo(1);
        assertThat(overview.deletedMasteredTotal()).isZero();
        assertThat(overview.trend()).hasSize(7);
        assertThat(overview.recentKeywords().getFirst())
                .extracting(LearningKeyword::keyword, LearningKeyword::count)
                .containsExactly("线程池", 1);
        assertThat(overview.recentMastered().getFirst().packageId()).isEqualTo(sourcePackage.id());
        assertThat(overview.deletedMastered()).isEmpty();
    }

    @Test
    void separatesDeletedMasteryArchiveFromCurrentOverview() {
        LearningRepository repository = mock(LearningRepository.class);
        SourcePackage currentPackage = sourcePackage();
        SourcePackage deletedPackage = sourcePackage();
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        List<String> currentKeywords = IntStream.rangeClosed(1, 12)
                .mapToObj(index -> "keyword-" + index)
                .toList();
        MasteryState currentState = state(currentPackage, true, currentKeywords, now, null);
        MasteryState deletedState = state(
                deletedPackage, true, List.of("deleted-only"), now.minusDays(1), now);
        when(repository.findMasteredStates(currentPackage.ownerId(), PACKAGE_SUBJECT))
                .thenReturn(List.of(currentState, deletedState));
        when(repository.findMasteredEventsSince(eq(currentPackage.ownerId()), any()))
                .thenReturn(List.of(
                        new MasteredEventSnapshot(
                                currentPackage.id(), currentPackage.title(), currentKeywords, now),
                        new MasteredEventSnapshot(
                                deletedPackage.id(), deletedPackage.title(),
                                List.of("deleted-only"), now.minusDays(1))
                ));
        LearningService service = new LearningService(repository);

        LearningOverview overview = service.overview(currentPackage.ownerId(), 7, 30);

        assertThat(overview.masteredTotal()).isEqualTo(1);
        assertThat(overview.deletedMasteredTotal()).isEqualTo(1);
        assertThat(overview.deletedMastered()).singleElement().satisfies(item -> {
            assertThat(item.packageId()).isEqualTo(deletedPackage.id());
            assertThat(item.keywords()).containsExactly("deleted-only");
            assertThat(item.masteredAt()).isEqualTo(now.minusDays(1));
            assertThat(item.deletedAt()).isEqualTo(now);
        });
        assertThat(overview.recentMastered()).extracting(RecentMastered::packageId)
                .containsExactly(currentPackage.id());
        assertThat(overview.recentKeywords()).hasSize(10);
        assertThat(overview.recentKeywords()).extracting(LearningKeyword::keyword)
                .doesNotContain("deleted-only");
        assertThat(overview.trend().stream().mapToInt(LearningTrendPoint::masteredCount).sum())
                .isEqualTo(2);
    }

    @Test
    void returnsAtMostTwentyDeletedMasteredItemsInNewestFirstOrder() {
        LearningRepository repository = mock(LearningRepository.class);
        SourcePackage packageMarker = sourcePackage();
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
        List<MasteryState> deletedStates = IntStream.range(0, 24)
                .mapToObj(index -> state(sourcePackage(), true, List.of("deleted-" + index),
                        now.minusDays(index + 1L), now.minusDays(index)))
                .toList();
        when(repository.findMasteredStates(packageMarker.ownerId(), PACKAGE_SUBJECT))
                .thenReturn(deletedStates);
        when(repository.findMasteredEventsSince(eq(packageMarker.ownerId()), any()))
                .thenReturn(List.of());
        LearningService service = new LearningService(repository);

        LearningOverview overview = service.overview(packageMarker.ownerId(), 7, 30);

        assertThat(overview.deletedMasteredTotal()).isEqualTo(24);
        assertThat(overview.deletedMastered()).hasSize(20);
        assertThat(overview.deletedMastered()).extracting(DeletedMastered::deletedAt)
                .containsExactlyElementsOf(IntStream.range(0, 20)
                        .mapToObj(index -> now.minusDays(index))
                        .toList());
    }

    private SourcePackage sourcePackage() {
        OffsetDateTime now = OffsetDateTime.now();
        return new SourcePackage(1, UUID.randomUUID(), "local-user", "Java 并发任务调度",
                PackageType.MIXED, PackageStatus.READY, JobStage.ILLUSTRATION, 100,
                PackageOptions.defaults(), new ArrayList<>(), new ArrayList<>(), now, now);
    }

    private MasteryState state(SourcePackage sourcePackage, boolean mastered) {
        OffsetDateTime now = OffsetDateTime.now();
        return state(sourcePackage, mastered, List.of("线程池"), now, null);
    }

    private MasteryState state(SourcePackage sourcePackage, boolean mastered, List<String> keywords,
                               OffsetDateTime masteredAt, OffsetDateTime deletedAt) {
        OffsetDateTime now = OffsetDateTime.now();
        return new MasteryState(sourcePackage.ownerId(), PACKAGE_SUBJECT, sourcePackage.id(), mastered,
                mastered ? "MASTERED" : "UNSPECIFIED", sourcePackage.title(), keywords,
                mastered ? masteredAt : null, mastered ? masteredAt : null, deletedAt, 1, now, now);
    }
}
