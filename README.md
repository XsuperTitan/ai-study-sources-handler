# AI Sources Handler

Windows 本地运行的单用户学习资料处理工作台。它可以接收 PDF、TXT、Markdown、粘贴文本、截图和带字幕的 Bilibili 视频，异步生成带来源引用的 Markdown 笔记与学习指南。

## MVP 能力

- PDF、文本、截图混合资料包，单包最多 20 个文件和 100 MB。
- PDF 逐页文本提取、扫描页检测和最多 12 页视觉分析。
- 千问 VL 图片理解、DeepSeek 摘要/笔记/学习指南、可选万相插图。
- Bilibili 元数据和已有字幕解析，不下载音视频，不执行 ASR。
- 本地目录与原子 JSON 保存资料包和任务，MySQL 保存掌握状态与学习行为历史。
- Markdown、Mermaid、内部图片和可点击来源引用。
- 资料卡片支持标记“已掌握”、归档筛选与恢复，删除资料后仍保留学习痕迹快照。

不包含账号体系、资料包元数据全量 MySQL 化、在线笔记编辑、正式学习计划、复习任务和 RAG。

## 环境

- Windows 11
- Java 21
- Node.js 24
- MySQL 8
- `yt-dlp`

本机已可通过以下方式安装依赖：

```powershell
winget install --id EclipseAdoptium.Temurin.21.JDK --exact
winget install --id yt-dlp.yt-dlp --exact
```

新安装后需要重新打开终端，让 PATH 生效。Maven 由 `backend/mvnw.cmd` 自动下载。

## 配置

复制 `.env.example` 中的变量到本机环境。不要将真实密钥写入仓库。

```powershell
$env:DEEPSEEK_API_KEY='...'
$env:DASHSCOPE_API_KEY='...'
$env:QWEN_VL_MODEL='qwen-vl-max'
$env:DB_URL='jdbc:mysql://localhost:3306/ai_sources_handler?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true'
$env:DB_USERNAME='ai_sources_handler'
$env:DB_PASSWORD='...'
```

可选变量包括 `WANX_MODEL`、`YT_DLP_PATH`、`YT_DLP_COOKIES_FROM_BROWSER` 和 `APP_STORAGE_ROOT`。

首次运行前创建数据库和专用账号。Flyway 会在后端启动时自动创建并升级学习域表：

```sql
CREATE DATABASE ai_sources_handler
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'ai_sources_handler'@'localhost' IDENTIFIED BY 'replace-with-a-strong-password';
GRANT ALL PRIVILEGES ON ai_sources_handler.* TO 'ai_sources_handler'@'localhost';
```

正式启动默认要求 DeepSeek 与千问 VL 配置完整。万相或 `yt-dlp` 缺失时，应用仍可启动，但对应能力会通过 `/api/v1/capabilities` 标记为不可用。

## 启动

后端：

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

前端：

```powershell
cd frontend
npm install
npm run dev
```

- Web: <http://localhost:5173>
- API: <http://localhost:8080/api/v1>
- Health: <http://localhost:8080/actuator/health>

资料包、任务、内容输出和文件仍保存在 `runtime-data/packages/{packageId}`。掌握状态写入
`learning_subject_state`，上传、掌握、恢复和删除行为追加写入 `user_activity_event`。

## 验证

```powershell
cd backend
.\mvnw.cmd clean verify

# 配置独立测试库的 DB_* 后，验证 Flyway 与 MyBatis/MySQL
.\mvnw.cmd -Pmysql-it -Dtest=LearningMySqlIntegrationTest test

cd ..\frontend
npm run lint
npm run test
npm run build
npm run e2e
```

Playwright 默认使用本机安装的 Chrome，避免额外下载浏览器运行时。

后端 AI 集成测试使用 WireMock，不调用真实付费模型。MySQL 集成测试只在配置 `DB_URL` 后运行，
且应指向可清理的独立测试库。运行时没有 Mock Profile，所有 AI 处理均使用已配置的真实服务。

## 安全边界

- 文件使用服务端 UUID 存储，原始文件名仅作展示。
- 下载接口只接受 `packageId + assetId`，所有路径均执行规范化和根目录校验。
- Bilibili 入口只允许 HTTPS 白名单域名，并拒绝本机与内网解析结果。
- Markdown 禁止执行原始 HTML 和 `javascript:` URL。
- 日志不记录完整原文、图片 Base64、Authorization Header 或 API 密钥。

## 当前外部限制

- 无字幕、私密或受平台限制的 Bilibili 视频无法处理。
- 扫描 PDF 超过视觉页上限时只选择优先页面分析。
- 万相接口返回临时 URL 后会立即下载到本地；插图失败只会令资料包进入 `PARTIALLY_READY`。
