package com.aisourceshandler.learning;

import com.aisourceshandler.domain.Models.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    private SourcePackage sourcePackage() {
        OffsetDateTime now = OffsetDateTime.now();
        return new SourcePackage(1, UUID.randomUUID(), "local-user", "Java 并发任务调度",
                PackageType.MIXED, PackageStatus.READY, JobStage.ILLUSTRATION, 100,
                PackageOptions.defaults(), new ArrayList<>(), new ArrayList<>(), now, now);
    }

    private MasteryState state(SourcePackage sourcePackage, boolean mastered) {
        OffsetDateTime now = OffsetDateTime.now();
        return new MasteryState(sourcePackage.ownerId(), PACKAGE_SUBJECT, sourcePackage.id(), mastered,
                mastered ? "MASTERED" : "UNSPECIFIED", sourcePackage.title(), List.of("线程池"),
                mastered ? now : null, mastered ? now : null, null, 1, now, now);
    }
}
