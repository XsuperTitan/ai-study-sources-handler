package com.aisourceshandler.application;

import com.aisourceshandler.domain.Models.StudyGuide;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PackagePipelineTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void normalizesCommonModelTypeDrift() throws Exception {
        StudyGuide guide = PackagePipeline.normalizeStudyGuide(mapper.readTree("""
                {
                  "overview": "线程池学习指南",
                  "targetAudience": "Java 开发者",
                  "difficulty": "入门",
                  "estimatedMinutes": 20,
                  "learningObjectives": ["理解线程复用"],
                  "exercises": [{"question": "解释线程池的价值"}],
                  "reviewSchedule": [
                    {"afterDays": "1", "focus": "核心概念"},
                    "一周后回顾"
                  ]
                }
                """));

        assertThat(guide.schemaVersion()).isEqualTo(1);
        assertThat(guide.targetAudience()).containsExactly("Java 开发者");
        assertThat(guide.exercises()).containsExactly("解释线程池的价值");
        assertThat(guide.reviewSchedule()).hasSize(2);
        assertThat(guide.reviewSchedule().getFirst()).containsEntry("afterDays", 1);
    }
}
