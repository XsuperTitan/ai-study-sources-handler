package com.aisourceshandler.application;

import com.aisourceshandler.api.ApiException;
import com.aisourceshandler.domain.Models.JobStage;
import com.aisourceshandler.domain.Models.PackageOptions;
import com.aisourceshandler.domain.Models.PackageStatus;
import com.aisourceshandler.domain.Models.PackageType;
import com.aisourceshandler.domain.Models.SourcePackage;
import com.aisourceshandler.domain.Models.StudyGuide;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

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
    void appliesNormalizedGeneratedTitleOnlyWhenAutoTitleIsEnabled() {
        SourcePackage automatic = sourcePackage("draft.pdf", true);
        SourcePackage manual = sourcePackage("我的线程池资料", false);

        SourcePackage renamed = PackagePipeline.applyGeneratedTitle(
                automatic, "## 《Java 线程池执行机制.pdf》 [[cite:blk_1234567890abcdef1234567890abcdef]]");
        SourcePackage preserved = PackagePipeline.applyGeneratedTitle(manual, "AI 生成标题");

        assertThat(renamed.title()).isEqualTo("Java 线程池执行机制");
        assertThat(preserved).isSameAs(manual);
    }

    @Test
    void keepsFallbackTitleWhenGeneratedTitleIsInvalid() {
        SourcePackage sourcePackage = sourcePackage("fallback.png", true);

        SourcePackage result = PackagePipeline.applyGeneratedTitle(
                sourcePackage, "### [[cite:blk_1234567890abcdef1234567890abcdef]]");

        assertThat(result).isSameAs(sourcePackage);
        assertThat(PackagePipeline.normalizeGeneratedTitle("A".repeat(90))).hasSize(80);
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

        assertThat(prompt).contains("画面类型：1:1 手绘白板速记图");
        assertThat(prompt).contains("四个分区");
        assertThat(prompt).contains("黑手写 marker");
        assertThat(prompt).contains("红蓝 marker");
        assertThat(prompt).contains("图片标题：线程池");
        assertThat(prompt).contains("主题摘要：复用工作线程。");
        assertThat(prompt).contains("核心概念：1. 任务提交");
        assertThat(prompt).contains("任务提交（队列缓冲任务、工作线程消费任务");
        assertThat(prompt).contains("每个标签 4-10 个汉字");
        assertThat(prompt).contains("主体隐喻");
        assertThat(prompt).contains("小程序员");
        assertThat(prompt).contains("任务提交");
        assertThat(prompt).doesNotContain("[[cite:");
        assertThat(prompt).doesNotContain("```");
        assertThat(prompt).doesNotContain("run();");
    }

    @Test
    void illustrationPromptKeepsTitleAndConceptsBeforeStyle() throws Exception {
        String prompt = PackagePipeline.buildIllustrationPrompt("system prompt",
                "机器学习与深度学习理论基础：分类与神经网络发展",
                mapper.readTree("""
                        {
                          "groups": [{
                            "overview": "讲解监督学习、分类任务和神经网络层级结构。",
                            "sections": [{
                              "title": "机器学习分类",
                              "knowledgePoints": [
                                "监督学习中的分类边界",
                                "神经网络多层特征提取",
                                "卷积网络识别局部模式",
                                "循环网络处理序列信息"
                              ]
                            }]
                          }]
                        }
                        """));

        assertThat(prompt).contains("机器学习与深度学习理论基础");
        assertThat(prompt).contains("1. 机器学习分类");
        assertThat(prompt).contains("分叉分类树");
        assertThat(prompt.indexOf("1:1")).isLessThan(prompt.indexOf("marker"));
    }

    @Test
    void separatesClassicAndWhiteboardIllustrationPrompts() throws Exception {
        var digest = mapper.readTree("""
                {
                  "groups": [{
                    "overview": "Embedding model selection for code, long text, and multimodal search.",
                    "sections": [{
                      "title": "Embedding Model 场景选型",
                      "knowledgePoints": ["代码嵌入", "长文本处理", "多模态连接"]
                    }]
                  }]
                }
                """);

        String classic = PackagePipeline.buildClassicIllustrationPrompt(
                "classic system", "Embedding Model 场景选型", digest);
        String whiteboard = PackagePipeline.buildWhiteboardIllustrationPrompt(
                "whiteboard system", "Embedding Model 场景选型", digest);

        assertThat(classic).contains("abstract knowledge poster");
        assertThat(classic).contains("no readable text");
        assertThat(classic).contains("translucent glass");
        assertThat(whiteboard).contains("1:1");
        assertThat(whiteboard).contains("Embedding Model");
        assertThat(whiteboard).contains("marker");
        assertThat(whiteboard).doesNotContain("Canvas: 16:9");
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
    void rendersStructuredKnowledgeDiagramDeterministically() throws Exception {
        String diagram = PackagePipeline.renderKnowledgeDiagram(
                mapper.readTree("""
                        [
                          {"id":"N1","label":"资料输入"},
                          {"id":"N2","label":"概念识别"},
                          {"id":"N3","label":"结构拆解"},
                          {"id":"N4","label":"关键路径"},
                          {"id":"N5","label":"复习应用"}
                        ]
                        """),
                mapper.readTree("""
                        [
                          {"from":"N1","to":"N2"},
                          {"from":"N2","to":"N3"},
                          {"from":"N3","to":"N4","label":"深入"},
                          {"from":"N4","to":"N5"}
                        ]
                        """));

        assertThat(diagram).startsWith("flowchart TB");
        assertThat(diagram).contains("N3 -- \"深入\" --> N4");
        assertThat(diagram).contains("N5[\"复习应用\"]");
    }

    @Test
    void acceptsKnowledgeDiagramWithTwentyFourNodes() {
        assertThat(PackagePipeline.normalizeKnowledgeDiagram(diagramWithNodes(24)))
                .contains("N24[节点24]");
    }

    @Test
    void rejectsKnowledgeDiagramWithMoreThanTwentyFourNodes() {
        assertThatThrownBy(() -> PackagePipeline.normalizeKnowledgeDiagram(diagramWithNodes(25)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("5-24");
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

    private String diagramWithNodes(int count) {
        StringBuilder diagram = new StringBuilder("flowchart TB\n");
        for (int index = 1; index <= count; index++) {
            diagram.append("N").append(index).append("[节点").append(index).append("]");
            if (index < count) diagram.append(" --> ");
        }
        return diagram.toString();
    }

    private SourcePackage sourcePackage(String title, boolean autoTitle) {
        OffsetDateTime now = OffsetDateTime.now();
        return new SourcePackage(1, UUID.randomUUID(), "local-user", title, PackageType.MIXED,
                PackageStatus.PROCESSING, JobStage.NOTE, 65,
                new PackageOptions("ZH_CN", "INTERVIEW", true, autoTitle),
                new ArrayList<>(), new ArrayList<>(), now, now);
    }
}
