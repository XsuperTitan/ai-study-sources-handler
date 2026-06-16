package com.aisourceshandler.learning;

import com.aisourceshandler.domain.Models.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.aisourceshandler.learning.LearningModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("mysql")
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = "jdbc:mysql:.*")
@Import(LearningMySqlIntegrationTest.FailureConfig.class)
@SpringBootTest(properties = {
        "app.require-ai-configuration=false",
        "app.storage-root=${java.io.tmpdir}/ai-sources-handler-mysql-it"
})
class LearningMySqlIntegrationTest {
    private final String ownerId = "mysql-it-" + UUID.randomUUID();

    @Autowired
    LearningService learning;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FailingLearningRepository repository;

    @BeforeEach
    void resetFailure() {
        repository.failAppend = false;
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM user_activity_event WHERE owner_id = ?", ownerId);
        jdbc.update("DELETE FROM learning_subject_state WHERE owner_id = ?", ownerId);
    }

    @Test
    void flywaySchemaSupportsIdempotentMasteryJsonSnapshotsAndRestore() {
        SourcePackage sourcePackage = sourcePackage();

        learning.setMastery(sourcePackage, List.of("线程池", "任务队列"), true);
        learning.setMastery(sourcePackage, List.of("线程池", "任务队列"), true);

        assertThat(learning.masteryFor(ownerId, sourcePackage.id()).mastered()).isTrue();
        assertThat(eventCount(sourcePackage.id(), "PACKAGE_MASTERED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT JSON_VALID(keywords_snapshot)
                FROM learning_subject_state
                WHERE owner_id = ? AND subject_id = ?
                """, Integer.class, ownerId, sourcePackage.id().toString())).isEqualTo(1);

        learning.setMastery(sourcePackage, List.of("线程池"), false);

        assertThat(learning.masteryFor(ownerId, sourcePackage.id()).mastered()).isFalse();
        assertThat(eventCount(sourcePackage.id(), "PACKAGE_UNMASTERED")).isEqualTo(1);
        assertThat(learning.masteryFor(ownerId, List.of(sourcePackage.id())))
                .containsKey(sourcePackage.id());
    }

    @Test
    void rollsBackMasteryStateWhenEventAppendFails() {
        SourcePackage sourcePackage = sourcePackage();
        repository.failAppend = true;

        assertThatThrownBy(() -> learning.setMastery(sourcePackage, List.of("事务"), true))
                .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM learning_subject_state
                WHERE owner_id = ? AND subject_id = ?
                """, Integer.class, ownerId, sourcePackage.id().toString())).isZero();
    }

    @Test
    void retainsMasterySnapshotAndEventsAfterSourceDeletion() {
        SourcePackage sourcePackage = sourcePackage();
        learning.setMastery(sourcePackage, List.of("线程池"), true);

        learning.recordDeleteRequested(sourcePackage, List.of("线程池"));
        learning.recordDeleted(sourcePackage, List.of("线程池"));

        assertThat(jdbc.queryForObject("""
                SELECT source_deleted_at IS NOT NULL
                FROM learning_subject_state
                WHERE owner_id = ? AND subject_id = ?
                """, Boolean.class, ownerId, sourcePackage.id().toString())).isTrue();
        assertThat(eventCount(sourcePackage.id(), "PACKAGE_DELETE_REQUESTED")).isEqualTo(1);
        assertThat(eventCount(sourcePackage.id(), "PACKAGE_DELETED")).isEqualTo(1);

        LearningOverview overview = learning.overview(ownerId, 7, 30);
        assertThat(overview.masteredTotal()).isZero();
        assertThat(overview.deletedMasteredTotal()).isEqualTo(1);
        assertThat(overview.deletedMastered()).singleElement().satisfies(item -> {
            assertThat(item.packageId()).isEqualTo(sourcePackage.id());
            assertThat(item.title()).isEqualTo(sourcePackage.title());
            assertThat(item.keywords()).containsExactly("线程池");
            assertThat(item.masteredAt()).isNotNull();
            assertThat(item.deletedAt()).isNotNull();
        });
        assertThat(overview.masteredThisWeek()).isEqualTo(1);
    }

    @Test
    void deletingActivePackageDoesNotCreateLearningArchive() {
        SourcePackage sourcePackage = sourcePackage();

        learning.recordDeleteRequested(sourcePackage, List.of("active-only"));
        learning.recordDeleted(sourcePackage, List.of("active-only"));

        LearningOverview overview = learning.overview(ownerId, 7, 30);
        assertThat(overview.masteredTotal()).isZero();
        assertThat(overview.deletedMasteredTotal()).isZero();
        assertThat(overview.deletedMastered()).isEmpty();
    }

    private int eventCount(UUID packageId, String eventType) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM user_activity_event
                WHERE owner_id = ? AND package_id = ? AND event_type = ?
                """, Integer.class, ownerId, packageId.toString(), eventType);
    }

    private SourcePackage sourcePackage() {
        OffsetDateTime now = OffsetDateTime.now();
        return new SourcePackage(1, UUID.randomUUID(), ownerId, "Java 并发任务调度",
                PackageType.MIXED, PackageStatus.READY, JobStage.ILLUSTRATION, 100,
                PackageOptions.defaults(), new ArrayList<>(), new ArrayList<>(), now, now);
    }

    @TestConfiguration
    static class FailureConfig {
        @Bean
        @Primary
        FailingLearningRepository failingLearningRepository(
                com.aisourceshandler.learning.mybatis.MyBatisLearningRepository delegate) {
            return new FailingLearningRepository(delegate);
        }
    }

    static class FailingLearningRepository implements LearningRepository {
        private final LearningRepository delegate;
        boolean failAppend;

        FailingLearningRepository(LearningRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<MasteryState> findState(String ownerId, String subjectType, UUID subjectId) {
            return delegate.findState(ownerId, subjectType, subjectId);
        }

        @Override
        public List<MasteryState> findStates(String ownerId, String subjectType, Collection<UUID> subjectIds) {
            return delegate.findStates(ownerId, subjectType, subjectIds);
        }

        @Override
        public List<MasteryState> findMasteredStates(String ownerId, String subjectType) {
            return delegate.findMasteredStates(ownerId, subjectType);
        }

        @Override
        public List<MasteredEventSnapshot> findMasteredEventsSince(String ownerId, OffsetDateTime since) {
            return delegate.findMasteredEventsSince(ownerId, since);
        }

        @Override
        public int insertMasteredIfAbsent(MasteryState state) {
            return delegate.insertMasteredIfAbsent(state);
        }

        @Override
        public int updateMasteryIfChanged(MasteryState state) {
            return delegate.updateMasteryIfChanged(state);
        }

        @Override
        public int markSourceDeleted(String ownerId, String subjectType, UUID subjectId, String title,
                                     List<String> keywords, OffsetDateTime deletedAt) {
            return delegate.markSourceDeleted(ownerId, subjectType, subjectId, title, keywords, deletedAt);
        }

        @Override
        public void appendEvent(ActivityEvent event) {
            if (failAppend) throw new IllegalStateException("forced event failure");
            delegate.appendEvent(event);
        }
    }
}
