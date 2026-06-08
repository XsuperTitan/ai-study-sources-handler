package com.aisourceshandler.application;

import com.aisourceshandler.api.ApiException;
import com.aisourceshandler.domain.Models.StudyGuide;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void rejectsCitationsThatDoNotMapToContentBlocks() {
        assertThatThrownBy(() -> PackagePipeline.validateCitations(
                "结论 [[cite:blk_1234567890abcdef1234567890abcdef]]",
                Set.of("blk_abcdef1234567890abcdef1234567890")))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo("NOTE_CITATION_INVALID");
    }

    @Test
    void buildsIllustrationPromptFromDigestWithoutMarkdownNoise() throws Exception {
        String prompt = PackagePipeline.buildIllustrationPrompt("system prompt", "线程池 [[cite:blk_1234567890abcdef1234567890abcdef]]",
                mapper.readTree("""
                        {
                          "groups": [{
                            "overview": "复用工作线程。```java\\nrun();\\n```",
                            "sections": [{
                              "title": "任务提交",
                              "knowledgePoints": [
                                "队列缓冲任务 [[cite:blk_1234567890abcdef1234567890abcdef]]",
                                "工作线程消费任务"
                              ]
                            }]
                          }]
                        }
                        """));

        assertThat(prompt).contains("标题：线程池");
        assertThat(prompt).contains("摘要：复用工作线程。");
        assertThat(prompt).contains("概念：");
        assertThat(prompt).contains("专属隐喻优先");
        assertThat(prompt).contains("任务提交");
        assertThat(prompt).doesNotContain("[[cite:");
        assertThat(prompt).doesNotContain("```");
        assertThat(prompt).doesNotContain("run();");
    }

    @Test
    void acceptsValidKnowledgeDiagram() {
        String diagram = PackagePipeline.normalizeKnowledgeDiagram("""
                ```mermaid
                flowchart TB
                  A[资料输入] --> B[概念识别]
                  B --> C[结构拆解]
                  C --> D[关键路径]
                  D --> E[复习应用]
                ```
                """);

        assertThat(diagram).startsWith("flowchart TB");
        assertThat(diagram).contains("关键路径");
    }

    @Test
    void rejectsKnowledgeDiagramWithInternalReferences() {
        assertThatThrownBy(() -> PackagePipeline.normalizeKnowledgeDiagram("""
                flowchart TB
                  A[资料输入] --> B[blk_1234567890abcdef1234567890abcdef]
                  B --> C[结构拆解]
                  C --> D[关键路径]
                  D --> E[复习应用]
                """))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo("DIAGRAM_GENERATION_FAILED");
    }
}
