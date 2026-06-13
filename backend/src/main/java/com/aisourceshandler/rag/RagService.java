package com.aisourceshandler.rag;

import com.aisourceshandler.api.ApiException;
import com.aisourceshandler.config.RagProperties;
import com.aisourceshandler.domain.Models.*;
import com.aisourceshandler.infrastructure.AiProviders;
import com.aisourceshandler.infrastructure.LocalStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chroma.vectorstore.ChromaApi;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.aisourceshandler.rag.RagModels.*;

@Service
public class RagService {
    private final LocalStore store;
    private final AiProviders ai;
    private final RagProperties properties;
    private final ObjectMapper mapper;
    private final ChromaApi chroma;
    private volatile String collectionId;

    public RagService(LocalStore store, AiProviders ai, RagProperties properties,
                      ObjectMapper mapper, RestClient.Builder restClientBuilder) {
        this.store = store;
        this.ai = ai;
        this.properties = properties;
        this.mapper = mapper;
        this.chroma = new ChromaApi(properties.chroma().baseUrl(), restClientBuilder, mapper);
    }

    public boolean enabled() {
        return properties.enabled() && ai.embeddingConfigured();
    }

    public RagStatus status() {
        if (!properties.enabled()) {
            return new RagStatus(false, ai.embeddingConfigured(), false,
                    properties.chroma().collection(), 0, "RAG 已禁用");
        }
        try {
            String id = ensureCollection();
            long count = chroma.countEmbeddings(properties.chroma().tenant(), properties.chroma().database(), id);
            return new RagStatus(true, ai.embeddingConfigured(), true,
                    properties.chroma().collection(), count, "可用");
        } catch (Exception exception) {
            return new RagStatus(true, ai.embeddingConfigured(), false,
                    properties.chroma().collection(), 0,
                    "Chroma 不可用：" + Optional.ofNullable(exception.getMessage()).orElse("连接失败"));
        }
    }

    public int indexPackage(UUID packageId) {
        if (!enabled()) return 0;
        SourcePackage sourcePackage = requiredPackage(packageId);
        List<RagChunk> chunks = chunks(sourcePackage, store.contentBlocks(packageId));
        String collection = ensureCollection();
        chroma.deleteEmbeddings(properties.chroma().tenant(), properties.chroma().database(), collection,
                new ChromaApi.DeleteEmbeddingsRequest(null, Map.of("packageId", packageId.toString())));
        for (int offset = 0; offset < chunks.size(); offset += 10) {
            List<RagChunk> batch = chunks.subList(offset, Math.min(offset + 10, chunks.size()));
            List<float[]> vectors = ai.embedTexts(batch.stream().map(RagChunk::text).toList(), "document");
            chroma.upsertEmbeddings(properties.chroma().tenant(), properties.chroma().database(), collection,
                    new ChromaApi.AddEmbeddingsRequest(
                            batch.stream().map(RagChunk::id).toList(),
                            vectors,
                            batch.stream().map(RagChunk::metadata).toList(),
                            batch.stream().map(RagChunk::text).toList()));
        }
        return chunks.size();
    }

    public ReindexResult reindexAll() {
        int packages = 0;
        int chunks = 0;
        List<UUID> failed = new ArrayList<>();
        for (SourcePackage sourcePackage : store.findAllPackages()) {
            if (sourcePackage.status() != PackageStatus.READY
                    && sourcePackage.status() != PackageStatus.PARTIALLY_READY) continue;
            try {
                chunks += indexPackage(sourcePackage.id());
                packages++;
            } catch (RuntimeException exception) {
                failed.add(sourcePackage.id());
            }
        }
        return new ReindexResult(packages, chunks, failed);
    }

    public RagAnswer ask(RagAskRequest request) {
        if (request.question() == null || request.question().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RAG_QUESTION_EMPTY", "请输入问题。", false);
        }
        if (!enabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RAG_NOT_CONFIGURED",
                    "RAG 或千问 Embedding 尚未配置。", false);
        }
        int topK = request.topK() == null ? properties.defaultTopK()
                : Math.max(1, Math.min(request.topK(), 30));
        Map<String, Object> where = request.packageIds() == null || request.packageIds().isEmpty() ? null
                : Map.of("packageId", Map.of("$in",
                request.packageIds().stream().map(UUID::toString).toList()));
        float[] query = ai.embedTexts(List.of(request.question().strip()), "query").getFirst();
        ChromaApi.QueryResponse response = chroma.queryCollection(
                properties.chroma().tenant(), properties.chroma().database(), ensureCollection(),
                new ChromaApi.QueryRequest(List.of(query), topK, where,
                        List.of(ChromaApi.QueryRequest.Include.DOCUMENTS,
                                ChromaApi.QueryRequest.Include.METADATAS,
                                ChromaApi.QueryRequest.Include.DISTANCES)));
        List<RagCitation> citations = citations(response);
        if (citations.isEmpty()) {
            return new RagAnswer("当前资料库中没有找到足够证据回答这个问题。", List.of(), List.of());
        }
        String context = citations.stream().map(citation -> "[" + citation.citationId() + "] "
                        + citation.title() + "\n" + citation.excerpt())
                .collect(Collectors.joining("\n\n"));
        AiProviders.AiResult result = ai.deepSeekJson("""
                你是资料库问答助手。只能依据给定证据回答。只输出合法 JSON，字段为 answerMarkdown、citationIds。
                answerMarkdown 使用简洁中文；每个事实结论后使用 [C1] 形式标注证据。
                citationIds 仅列出实际使用且存在于证据中的编号。证据不足时明确说明，不得补充外部知识。
                """, "问题：\n" + request.question().strip() + "\n\n证据：\n" + context);
        try {
            JsonNode json = mapper.readTree(result.content());
            String answer = json.path("answerMarkdown").asText("").strip();
            Set<String> allowed = citations.stream().map(RagCitation::citationId).collect(Collectors.toSet());
            List<String> used = new ArrayList<>();
            json.path("citationIds").forEach(value -> {
                if (allowed.contains(value.asText()) && !used.contains(value.asText())) used.add(value.asText());
            });
            if (answer.isBlank()) answer = "当前证据不足，无法形成可靠回答。";
            return new RagAnswer(answer, List.copyOf(used), citations);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "RAG_ANSWER_INVALID",
                    "问答模型返回结构无效。", true, exception);
        }
    }

    private List<RagCitation> citations(ChromaApi.QueryResponse response) {
        if (response.documents() == null || response.documents().isEmpty()
                || response.documents().getFirst() == null) return List.of();
        List<String> documents = response.documents().getFirst();
        List<Map<String, Object>> metadatas = response.metadata().getFirst();
        List<Double> distances = response.distances().getFirst();
        Map<String, Integer> perPackage = new HashMap<>();
        List<RagCitation> output = new ArrayList<>();
        for (int index = 0; index < documents.size() && output.size() < 8; index++) {
            Map<String, Object> metadata = metadatas.get(index);
            UUID packageId = UUID.fromString(String.valueOf(metadata.get("packageId")));
            if (store.findPackage(packageId).isEmpty()) continue;
            int count = perPackage.getOrDefault(packageId.toString(), 0);
            if (count >= 2) continue;
            perPackage.put(packageId.toString(), count + 1);
            String citationId = "C" + (output.size() + 1);
            output.add(new RagCitation(citationId, packageId, String.valueOf(metadata.get("blockId")),
                    String.valueOf(metadata.get("title")), excerpt(documents.get(index), 520),
                    1.0 - distances.get(index), string(metadata, "sourceKind"),
                    integer(metadata, "pageNumber"), integer(metadata, "paragraphNumber"),
                    longValue(metadata, "startTimeMs"), longValue(metadata, "endTimeMs"),
                    string(metadata, "assetUrl")));
        }
        return List.copyOf(output);
    }

    private String ensureCollection() {
        if (collectionId != null) return collectionId;
        synchronized (this) {
            if (collectionId != null) return collectionId;
            ChromaApi.Collection collection;
            try {
                collection = chroma.getCollection(properties.chroma().tenant(),
                        properties.chroma().database(), properties.chroma().collection());
            } catch (RuntimeException missing) {
                collection = chroma.createCollection(properties.chroma().tenant(),
                        properties.chroma().database(),
                        new ChromaApi.CreateCollectionRequest(properties.chroma().collection(),
                                Map.of("hnsw:space", "cosine")));
            }
            collectionId = collection.id();
            return collectionId;
        }
    }

    private List<RagChunk> chunks(SourcePackage sourcePackage, List<ContentBlock> blocks) {
        List<RagChunk> output = new ArrayList<>();
        for (ContentBlock block : blocks) {
            String text = block.content() == null ? "" : block.content().strip();
            int index = 0;
            for (int start = 0; start < text.length();) {
                int end = Math.min(text.length(), start + properties.chunkChars());
                String part = text.substring(start, end).strip();
                if (!part.isBlank()) {
                    output.add(new RagChunk(chunkId(sourcePackage.id(), block.id(), index),
                            part, metadata(sourcePackage, block)));
                }
                if (end == text.length()) break;
                start = Math.max(start + 1, end - properties.chunkOverlap());
                index++;
            }
        }
        return List.copyOf(output);
    }

    private Map<String, Object> metadata(SourcePackage sourcePackage, ContentBlock block) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("packageId", sourcePackage.id().toString());
        values.put("blockId", block.id());
        values.put("title", sourcePackage.title());
        values.put("sourceKind", block.sourceRef().kind().name());
        put(values, "pageNumber", block.sourceRef().pageNumber());
        put(values, "paragraphNumber", block.sourceRef().paragraphNumber());
        put(values, "startTimeMs", block.sourceRef().startTimeMs());
        put(values, "endTimeMs", block.sourceRef().endTimeMs());
        if (block.sourceRef().assetId() != null) {
            values.put("assetUrl", "/api/v1/packages/" + sourcePackage.id()
                    + "/assets/" + block.sourceRef().assetId());
        }
        values.put("indexedAt", OffsetDateTime.now().toString());
        return values;
    }

    private void put(Map<String, Object> values, String key, Object value) {
        if (value != null) values.put(key, value);
    }

    private String chunkId(UUID packageId, String blockId, int index) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((packageId + ":" + blockId + ":" + index).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private SourcePackage requiredPackage(UUID packageId) {
        return store.findPackage(packageId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PACKAGE_NOT_FOUND",
                        "资料包不存在。", false));
    }

    private String excerpt(String value, int length) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        return text.length() <= length ? text : text.substring(0, length) + "…";
    }

    private String string(Map<String, Object> map, String key) {
        return map.get(key) == null ? null : String.valueOf(map.get(key));
    }

    private Integer integer(Map<String, Object> map, String key) {
        return map.get(key) instanceof Number number ? number.intValue() : null;
    }

    private Long longValue(Map<String, Object> map, String key) {
        return map.get(key) instanceof Number number ? number.longValue() : null;
    }

    private record RagChunk(String id, String text, Map<String, Object> metadata) {}
}
