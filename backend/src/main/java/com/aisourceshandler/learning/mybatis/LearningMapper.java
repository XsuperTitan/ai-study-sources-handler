package com.aisourceshandler.learning.mybatis;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.aisourceshandler.learning.mybatis.LearningRows.*;

@Mapper
interface LearningMapper {
    Optional<MasteryStateRow> findState(@Param("ownerId") String ownerId,
                                        @Param("subjectType") String subjectType,
                                        @Param("subjectId") String subjectId);

    List<MasteryStateRow> findStates(@Param("ownerId") String ownerId,
                                     @Param("subjectType") String subjectType,
                                     @Param("subjectIds") Collection<String> subjectIds);

    int insertMasteredIfAbsent(MasteryStateRow state);

    int updateMasteryIfChanged(MasteryStateRow state);

    int markSourceDeleted(@Param("ownerId") String ownerId,
                          @Param("subjectType") String subjectType,
                          @Param("subjectId") String subjectId,
                          @Param("titleSnapshot") String titleSnapshot,
                          @Param("keywordsSnapshot") String keywordsSnapshot,
                          @Param("sourceDeletedAt") LocalDateTime sourceDeletedAt);

    void insertEvent(ActivityEventRow event);
}
