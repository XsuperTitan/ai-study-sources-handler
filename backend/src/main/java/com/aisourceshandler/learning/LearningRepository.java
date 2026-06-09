package com.aisourceshandler.learning;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.aisourceshandler.learning.LearningModels.*;

public interface LearningRepository {
    Optional<MasteryState> findState(String ownerId, String subjectType, UUID subjectId);

    List<MasteryState> findStates(String ownerId, String subjectType, Collection<UUID> subjectIds);

    int insertMasteredIfAbsent(MasteryState state);

    int updateMasteryIfChanged(MasteryState state);

    int markSourceDeleted(String ownerId, String subjectType, UUID subjectId, String title,
                          List<String> keywords, OffsetDateTime deletedAt);

    void appendEvent(ActivityEvent event);
}
