# 全库 RAG 学习指南

本文面向刚接触 AI 应用开发的学习者，说明本项目当前的 RAG 流程、Chroma 数据结构以及常用查看方式。

## 1. 当前流程

```text
原始资料
→ 解析为 ContentBlock
→ 按 1800 字切片并保留 200 字重叠
→ 千问 text-embedding-v4 生成 1024 维文档向量
→ 文本、向量和来源信息写入 Chroma

用户问题
→ 千问生成查询向量
→ Chroma 进行余弦相似度检索
→ 每个资料包最多保留 2 个片段，总计最多 8 个证据片段
→ DeepSeek 仅依据证据生成答案
→ 前端展示答案、引用和原资料跳转
```

索引对象是原始 `ContentBlock`，不是最终生成的 Markdown 笔记。这样可以保留页码、段落、图片和视频时间点等原始证据。

## 2. Chroma 中保存的数据

默认集合：

```text
ai_sources_content_v1
```

每条记录包含四部分：

```json
{
  "id": "3467351e529a039f...",
  "document": "5. 什么是过拟合？过拟合是模型把训练数据背得太死……",
  "embedding": [-0.077165, 0.006125, -0.015311],
  "metadata": {
    "packageId": "a039a0e4-b207-4fb1-bf0f-d15b6bcf8592",
    "blockId": "blk_c8589252a5ee43f68df24740b05d7059",
    "title": "深度学习核心概念",
    "sourceKind": "IMAGE_ASSET",
    "assetUrl": "/api/v1/packages/.../assets/...",
    "indexedAt": "2026-06-14T00:33:50+08:00"
  }
}
```

| 字段 | 作用 |
|---|---|
| `id` | 由资料包 ID、内容块 ID 和切片序号计算的稳定 SHA-256 ID |
| `document` | 用于语义检索的原始文本切片 |
| `embedding` | 千问生成的 1024 维语义向量 |
| `packageId` | 所属资料包，用于限定检索范围 |
| `blockId` | 对应本地 `ContentBlock`，用于追溯原文 |
| `title` | 资料标题 |
| `sourceKind` | `PDF_PAGE`、`TEXT_PARAGRAPH`、`IMAGE_ASSET` 或 `VIDEO_TIME_RANGE` |
| `pageNumber` | PDF 页码，存在时保存 |
| `paragraphNumber` | 文本段落序号，存在时保存 |
| `startTimeMs` / `endTimeMs` | 视频字幕时间范围，存在时保存 |
| `assetUrl` | 原文件或图片访问地址，存在时保存 |

向量本身通常不适合人工阅读。调试时只需检查向量维度是否为 1024，并打印前几位确认数据存在。

## 3. 查看集合和记录

先确保 Chroma 正在监听 `8000` 端口：

```powershell
.\scripts\start-chroma.ps1
```

在项目根目录运行：

```powershell
@'
import chromadb

client = chromadb.HttpClient(host="127.0.0.1", port=8000)

for item in client.list_collections():
    collection = client.get_collection(item.name)
    print(item.name, collection.count(), collection.metadata)

collection = client.get_collection("ai_sources_content_v1")
data = collection.get(
    limit=3,
    include=["documents", "metadatas", "embeddings"]
)

for index, record_id in enumerate(data["ids"]):
    vector = data["embeddings"][index]
    print("\nID:", record_id)
    print("文本:", data["documents"][index][:300])
    print("元数据:", data["metadatas"][index])
    print("向量维度:", len(vector))
    print("向量前 8 位:", vector[:8])
'@ | .\.venv-chroma\Scripts\python.exe -
```

查看指定资料包：

```python
data = collection.get(
    where={"packageId": "资料包 UUID"},
    limit=5,
    include=["documents", "metadatas"]
)
```

不要直接修改 `runtime-data/chroma/chroma.sqlite3` 或 UUID 命名的索引目录。它们属于 Chroma 的内部存储格式，应通过 Chroma Client 或项目 API 读写。

## 4. 项目 API

| API | 用途 |
|---|---|
| `GET /api/v1/rag/status` | 查看 Chroma 状态、集合名和切片数量 |
| `POST /api/v1/rag/reindex-all` | 重建全部资料索引 |
| `POST /api/v1/packages/{packageId}/rag/reindex` | 重建单个资料包索引 |
| `POST /api/v1/rag/ask` | 全库或指定资料包问答 |

问答示例：

```json
{
  "question": "自注意力机制的核心计算流程是什么？",
  "packageIds": [],
  "topK": 12
}
```

## 5. 关键设计细节

- 文档向量使用千问的 `document` 类型，问题向量使用 `query` 类型。
- Chroma 集合使用余弦距离。
- 重建单个资料包时，先按 `packageId` 删除旧记录，再写入新记录。
- 同一个资料包最多进入回答上下文 2 个片段，避免单一资料占满上下文。
- DeepSeek 收到的是检索后的证据文本，不会直接读取整个 Chroma 数据库。
- Chroma 或 Embedding 失败属于可降级错误，不阻塞笔记与学习指南生成。

核心实现位于：

- `backend/src/main/java/com/aisourceshandler/rag/RagService.java`
- `backend/src/main/java/com/aisourceshandler/api/RagController.java`
- `backend/src/main/java/com/aisourceshandler/infrastructure/AiProviders.java`
- `frontend/src/pages/AskPage.tsx`
