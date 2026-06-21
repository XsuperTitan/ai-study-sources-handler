import {
  CheckCircleFilled,
  ClockCircleOutlined,
  DownloadOutlined,
  EyeOutlined,
  FileOutlined,
  LinkOutlined,
  PictureOutlined,
  PartitionOutlined,
  ReloadOutlined,
} from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Descriptions, Empty, Modal, Progress, Skeleton, Tabs, Timeline, message } from 'antd'
import { useEffect, useState } from 'react'
import { useParams } from 'react-router'
import { api } from '../api'
import MarkdownViewer from '../components/MarkdownViewer'
import MermaidDiagram from '../components/MermaidDiagram'
import StatusBadge from '../components/StatusBadge'
import ThemeSummaryGraphic from '../components/ThemeSummaryGraphic'
import type { JobStage, ProcessingJob, SourcesResponse, StudyGuide } from '../types'

const stages: Array<{ key: JobStage; label: string }> = [
  { key: 'PARSE', label: '内容解析' },
  { key: 'VISION', label: '视觉理解' },
  { key: 'DIGEST', label: '资料摘要' },
  { key: 'NOTE', label: 'AI 笔记' },
  { key: 'REPORT', label: '学习指南' },
  { key: 'RAG_INDEX', label: '知识索引' },
  { key: 'ILLUSTRATION', label: '主题插图' },
]

function DownloadBar({ href }: { href: string }) {
  return (
    <div className="download-cta">
      <Button icon={<DownloadOutlined />} href={href} size="large">
        下载 Markdown
      </Button>
    </div>
  )
}

function Guide({ guide, downloadHref }: { guide: StudyGuide; downloadHref: string }) {
  const groups = [
    ['学习目标', guide.learningObjectives],
    ['推荐顺序', guide.recommendedSequence],
    ['核心知识点', guide.coreKnowledgePoints],
    ['难点', guide.difficultPoints],
    ['常见错误', guide.commonMistakes],
    ['面试重点', guide.interviewFocus],
    ['练习', guide.exercises],
  ] as const
  return (
    <div className="result-stack">
      <div className="guide-sheet">
        <div className="guide-summary">
          <span>{guide.difficulty}</span>
          <span>约 {guide.estimatedMinutes} 分钟</span>
        </div>
        <p className="guide-overview">{guide.overview}</p>
        <div className="guide-grid">
          {groups.map(([title, values]) =>
            values?.length ? (
              <section key={title}>
                <h3>{title}</h3>
                <ol>
                  {values.map((value) => (
                    <li key={value}>{value}</li>
                  ))}
                </ol>
              </section>
            ) : null,
          )}
        </div>
      </div>
      <DownloadBar href={downloadHref} />
    </div>
  )
}

function JobTimeline({
  jobs,
  onRetry,
}: {
  jobs: ProcessingJob[]
  onRetry: (id: string) => void
}) {
  return (
    <Timeline
      items={jobs.map((job) => ({
        color: job.status === 'SUCCEEDED' ? 'green' : job.status === 'FAILED' ? 'red' : 'blue',
        dot: job.status === 'SUCCEEDED' ? <CheckCircleFilled /> : <ClockCircleOutlined />,
        children: (
          <div className="job-entry">
            <div>
              <strong>{job.stage}</strong>
              <span>第 {job.attempt} 次</span>
              <span>{job.status}</span>
            </div>
            {job.errorMessage && <p>{job.errorMessage}</p>}
            {job.metrics?.durationMs ? (
              <small>
                {(job.metrics.durationMs / 1000).toFixed(1)}s · {job.metrics.provider ?? 'local'} ·{' '}
                {job.metrics.inputTokens + job.metrics.outputTokens} tokens
              </small>
            ) : null}
            {job.retryable && ['FAILED', 'INTERRUPTED'].includes(job.status) && (
              <Button size="small" icon={<ReloadOutlined />} onClick={() => onRetry(job.id)}>
                重试此阶段
              </Button>
            )}
          </div>
        ),
      }))}
    />
  )
}

function formatBytes(value?: number) {
  if (value == null) return ''
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

function dismissedWarningsKey(packageId: string) {
  return `package-detail-dismissed-warnings:${packageId}`
}

function readDismissedWarnings(key: string) {
  try {
    const raw = window.localStorage.getItem(key)
    const values = raw ? JSON.parse(raw) : []
    return new Set(Array.isArray(values) ? values.filter((value): value is string => typeof value === 'string') : [])
  } catch {
    return new Set<string>()
  }
}

function writeDismissedWarnings(key: string, values: Set<string>) {
  try {
    window.localStorage.setItem(key, JSON.stringify([...values]))
  } catch {
    // Dismissal is a convenience; failing to persist should not block the page.
  }
}

function SourceLibrary({
  sources,
  illustrationAssetIds = [],
}: {
  sources?: SourcesResponse
  illustrationAssetIds?: Array<string | undefined>
}) {
  if (!sources) return <Skeleton active />
  const sourceAssetIds = new Set(sources.items.flatMap((item) => (item.assetId ? [item.assetId] : [])))
  const hiddenIllustrationAssetIds = new Set(illustrationAssetIds.filter(Boolean))
  const derivedAssets = sources.assets.filter(
    (asset) => !sourceAssetIds.has(asset.id) && !hiddenIllustrationAssetIds.has(asset.id),
  )

  return (
    <div className="source-library">
      <section>
        <div className="source-section-heading">
          <span>提交资料</span>
          <small>{sources.items.length} 项</small>
        </div>
        <div className="source-list">
          {sources.items.map((item) => {
            const href = item.assetUrl ?? item.sourceUrl
            return (
              <div className="source-row" key={item.id}>
                {item.contentType?.startsWith('image/') && item.assetUrl ? (
                  <img src={item.assetUrl} alt={item.originalName} loading="lazy" />
                ) : (
                  <div className="source-file-icon">
                    {item.kind === 'VIDEO' ? <LinkOutlined /> : <FileOutlined />}
                  </div>
                )}
                <div>
                  <strong>{item.originalName}</strong>
                  <span>
                    {item.kind}
                    {item.size != null ? ` · ${formatBytes(item.size)}` : ''}
                  </span>
                </div>
                {href && (
                  <Button href={href} target="_blank" rel="noopener noreferrer">
                    打开
                  </Button>
                )}
              </div>
            )
          })}
        </div>
      </section>

      {derivedAssets.length > 0 && (
        <section>
          <div className="source-section-heading">
            <span>解析资源</span>
            <small>{derivedAssets.length} 项</small>
          </div>
          <div className="derived-asset-grid">
            {derivedAssets.map((asset) => (
              <a href={asset.assetUrl} target="_blank" rel="noopener noreferrer" key={asset.id}>
                {asset.contentType.startsWith('image/') ? (
                  <img src={asset.assetUrl} alt={asset.originalName} loading="lazy" />
                ) : (
                  <FileOutlined />
                )}
                <span>{asset.originalName}</span>
                <small>{formatBytes(asset.size)}</small>
              </a>
            ))}
          </div>
        </section>
      )}
    </div>
  )
}

function NoteVisuals({
  title,
  guide,
  diagram,
  diagramTitle,
  diagramLoading,
  diagramError,
  illustrationUrl,
}: {
  title: string
  guide?: StudyGuide
  diagram?: string
  diagramTitle?: string
  diagramLoading: boolean
  diagramError?: Error | null
  illustrationUrl?: string
}) {
  const [previewImage, setPreviewImage] = useState<string | null>(null)
  const items = [
    {
      key: 'illustration',
      label: (
        <span className="visual-tab-label">
          <PictureOutlined /> AI 主题图
        </span>
      ),
      children: guide ? (
        <ThemeSummaryGraphic
          title={title}
          guide={guide}
          illustrationUrl={illustrationUrl}
          onPreview={setPreviewImage}
        />
      ) : illustrationUrl ? (
        <figure className="note-illustration">
          <img src={illustrationUrl} alt="AI 主题图" loading="lazy" />
          <Button
            className="note-illustration-preview"
            icon={<EyeOutlined />}
            onClick={() => setPreviewImage(illustrationUrl)}
            size="small"
          >
            查看大图
          </Button>
          <figcaption>基于资料摘要生成的技术主题插图</figcaption>
        </figure>
      ) : (
        <div className="theme-summary-empty">
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="主题总结生成中" />
        </div>
      ),
    },
  ]
  if (diagram || diagramLoading || diagramError) {
    items.push({
      key: 'diagram',
      label: (
        <span className="visual-tab-label">
          <PartitionOutlined /> 知识流程图
        </span>
      ),
      children: (
        <div className="note-diagram-panel">
          <div className="visual-caption">
            <strong>{diagramTitle || '知识流程图'}</strong>
            <span>浏览器渲染，文字保持清晰</span>
          </div>
          {diagramLoading ? (
            <Skeleton active paragraph={{ rows: 4 }} />
          ) : diagramError ? (
            <Alert type="warning" showIcon message={diagramError.message || '知识流程图暂不可用'} />
          ) : (
            diagram && <MermaidDiagram chart={diagram} />
          )}
        </div>
      ),
    })
  }
  return (
    <section className="note-visuals">
      <Tabs size="small" defaultActiveKey="illustration" items={items} />
      <Modal
        centered
        className="image-preview-modal"
        footer={null}
        onCancel={() => setPreviewImage(null)}
        open={Boolean(previewImage)}
        title={title}
        width={1040}
      >
        {previewImage ? <img src={previewImage} alt={title} /> : null}
      </Modal>
    </section>
  )
}

export default function PackageDetailPage() {
  const { packageId = '' } = useParams()
  const queryClient = useQueryClient()
  const warningStorageKey = dismissedWarningsKey(packageId)
  const [dismissedWarnings, setDismissedWarnings] = useState<Set<string>>(() =>
    readDismissedWarnings(warningStorageKey),
  )
  const detail = useQuery({
    queryKey: ['package', packageId],
    queryFn: () => api.package(packageId),
    refetchInterval: (query) => {
      const status = query.state.data?.status
      return status && ['READY', 'PARTIALLY_READY', 'FAILED', 'INTERRUPTED'].includes(status)
        ? false
        : 1500
    },
  })
  const jobs = useQuery({
    queryKey: ['jobs', packageId],
    queryFn: () => api.jobs(packageId),
    refetchInterval: detail.data && ['READY', 'PARTIALLY_READY', 'FAILED'].includes(detail.data.status) ? false : 1500,
  })
  const sources = useQuery({
    queryKey: ['sources', packageId],
    queryFn: () => api.sources(packageId),
  })
  const note = useQuery({
    queryKey: ['note', packageId],
    queryFn: () => api.note(packageId),
    enabled: Boolean(detail.data?.outputs?.noteReady),
    retry: false,
  })
  const diagram = useQuery({
    queryKey: ['diagram', packageId],
    queryFn: () => api.diagram(packageId),
    enabled: Boolean(detail.data?.outputs?.diagramReady),
    retry: false,
  })
  const report = useQuery({
    queryKey: ['report', packageId],
    queryFn: () => api.report(packageId),
    enabled: Boolean(detail.data?.outputs?.reportReady),
    retry: false,
  })
  const retry = useMutation({
    mutationFn: api.retry,
    onSuccess: () => {
      message.success('已创建新的重试任务。')
      queryClient.invalidateQueries({ queryKey: ['package', packageId] })
      queryClient.invalidateQueries({ queryKey: ['jobs', packageId] })
    },
    onError: (error: Error) => message.error(error.message),
  })

  useEffect(() => {
    setDismissedWarnings(readDismissedWarnings(warningStorageKey))
  }, [warningStorageKey])

  if (detail.isLoading) return <div className="page"><Skeleton active /></div>
  if (!detail.data) return <div className="page"><Empty description="资料包不存在" /></div>
  const item = detail.data
  const currentIndex = Math.max(0, stages.findIndex((stage) => stage.key === item.currentStage))
  const noteMarkdownHref = `/api/v1/packages/${packageId}/note.md`
  const reportMarkdownHref = `/api/v1/packages/${packageId}/report.md`
  const themeIllustrationUrl = item.outputs?.whiteboardIllustrationAssetUrl ?? item.outputs?.illustrationAssetUrl
  const visibleWarnings = item.warnings?.filter((warning) => !dismissedWarnings.has(warning)) ?? []

  return (
    <div className="page detail-page">
      <section className="detail-heading">
        <div>
          <span className="eyebrow">PACKAGE / {item.id.slice(0, 8).toUpperCase()}</span>
          <h1>{item.title}</h1>
        </div>
        <StatusBadge status={item.status} />
      </section>

      {visibleWarnings.map((warning) => (
        <Alert
          key={warning}
          type="warning"
          showIcon
          closable
          message={warning}
          className="detail-alert"
          onClose={() =>
            setDismissedWarnings((current) => {
              const next = new Set(current).add(warning)
              writeDismissedWarnings(warningStorageKey, next)
              return next
            })
          }
        />
      ))}

      <section className="progress-board">
        <div className="progress-number">{item.progress}%</div>
        <Progress percent={item.progress} showInfo={false} strokeColor="#d15d36" railColor="#ded7c8" />
        <div className="stage-strip">
          {stages.map((stage, index) => (
            <div
              className={index < currentIndex ? 'done' : index === currentIndex ? 'active' : ''}
              key={stage.key}
            >
              <b>{String(index + 1).padStart(2, '0')}</b>
              <span>{stage.label}</span>
            </div>
          ))}
        </div>
      </section>

      <Tabs
        className="result-tabs"
        items={[
          {
            key: 'note',
            label: 'AI 笔记',
            children: note.data ? (
              <div className="note-result">
                <NoteVisuals
                  title={item.title}
                  guide={report.data}
                  diagram={diagram.data}
                  diagramTitle={item.outputs?.diagramTitle}
                  diagramLoading={diagram.isLoading}
                  diagramError={diagram.error}
                  illustrationUrl={themeIllustrationUrl}
                />
                <MarkdownViewer markdown={note.data} packageId={packageId} />
                <DownloadBar href={noteMarkdownHref} />
              </div>
            ) : (
              <Empty description="笔记生成中" />
            ),
          },
          {
            key: 'guide',
            label: '学习指南',
            children: report.data ? (
              <Guide guide={report.data} downloadHref={reportMarkdownHref} />
            ) : (
              <Empty description="学习指南生成中" />
            ),
          },
          {
            key: 'sources',
            label: '原始资料',
            children: (
              <SourceLibrary
                sources={sources.data}
                illustrationAssetIds={[
                  item.outputs?.illustrationAssetId,
                  item.outputs?.whiteboardIllustrationAssetId,
                ]}
              />
            ),
          },
          {
            key: 'jobs',
            label: '处理记录',
            children: (
              <JobTimeline jobs={jobs.data ?? []} onRetry={(id) => retry.mutate(id)} />
            ),
          },
          {
            key: 'meta',
            label: '资料信息',
            children: (
              <Descriptions
                bordered
                column={1}
                items={[
                  { key: 'type', label: '资料包类型', children: item.packageType },
                  { key: 'status', label: '当前状态', children: item.status },
                  { key: 'stage', label: '当前阶段', children: item.currentStage },
                  { key: 'created', label: '创建时间', children: new Date(item.createdAt).toLocaleString('zh-CN') },
                ]}
              />
            ),
          },
        ]}
      />
    </div>
  )
}
