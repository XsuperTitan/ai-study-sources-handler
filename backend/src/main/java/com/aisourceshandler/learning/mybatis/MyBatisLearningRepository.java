package com.aisourceshandler.learning.mybatis;

import com.aisourceshandler.learning.LearningRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.aisourceshandler.learning.LearningModels.*;
import static com.aisourceshandler.learning.mybatis.LearningRows.*;

@Repository
public class MyBatisLearningRepository implements LearningRepository {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private final LearningMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisLearningRepository(LearningMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<MasteryState> findState(String ownerId, String subjectType, UUID subjectId) {
        return mapper.findState(ownerId, subjectType, subjectId.toString()).map(this::domain);
    }

    @Override
    public List<MasteryState> findStates(String ownerId, String subjectType, Collection<UUID> subjectIds) {
        if (subjectIds.isEmpty()) return List.of();
        return mapper.findStates(ownerId, subjectType, subjectIds.stream().map(UUID::toString).toList())
                .stream().map(this::domain).toList();
    }

    @Override
    public int insertMasteredIfAbsent(MasteryState state) {
        return mapper.insertMasteredIfAbsent(row(state));
    }

    @Override
    public int updateMasteryIfChanged(MasteryState state) {
        return mapper.updateMasteryIfChanged(row(state));
    }

    @Override
    public int markSourceDeleted(String ownerId, String subjectType, UUID subjectId, String title,
                                 List<String> keywords, OffsetDateTime deletedAt) {
        return mapper.markSourceDeleted(ownerId, subjectType, subjectId.toString(), title,
                json(keywords), local(deletedAt));
    }

    @Override
    public void appendEvent(ActivityEvent event) {
        mapper.insertEvent(new ActivityEventRow(
                event.eventId().toString(), event.ownerId(), event.category(), event.eventType(),
                event.subjectType(), event.subjectId().toString(),
                event.packageId() == null ? null : event.packageId().toString(),
                event.titleSnapshot(), json(event.keywordsSnapshot()), json(event.context()),
                local(event.occurredAt())));
    }

    private MasteryStateRow row(MasteryState state) {
        return new MasteryStateRow(
                state.ownerId(), state.subjectType(), state.subjectId().toString(), state.mastered(),
                state.masteryLevel(), state.titleSnapshot(), json(state.keywordsSnapshot()),
                local(state.firstMasteredAt()), local(state.masteredAt()), local(state.sourceDeletedAt()),
                state.version(), local(state.createdAt()), local(state.updatedAt()));
    }

    private MasteryState domain(MasteryStateRow row) {
        return new MasteryState(
                row.ownerId(), row.subjectType(), UUID.fromString(row.subjectId()), row.mastered(),
                row.masteryLevel(), row.titleSnapshot(), stringList(row.keywordsSnapshot()),
                offset(row.firstMasteredAt()), offset(row.lastMasteredAt()), offset(row.sourceDeletedAt()),
                row.version(), offset(row.createdAt()), offset(row.updatedAt()));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化学习行为快照", exception);
        }
    }

    private List<String> stringList(String value) {
        try {
            return objectMapper.readValue(value, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法读取学习行为快照", exception);
        }
    }

    private LocalDateTime local(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
