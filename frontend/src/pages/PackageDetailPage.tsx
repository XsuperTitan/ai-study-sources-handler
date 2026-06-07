import { CheckCircleFilled, ClockCircleOutlined, ReloadOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Descriptions, Empty, Progress, Skeleton, Tabs, Timeline, message } from 'antd'
import { useParams } from 'react-router'
import { api } from '../api'
import MarkdownViewer from '../components/MarkdownViewer'
import StatusBadge from '../components/StatusBadge'
import type { JobStage, ProcessingJob, StudyGuide } from '../types'

const stages: Array<{ key: JobStage; label: string }> = [
  { key: 'PARSE', label: '内容解析' },
  { key: 'VISION', label: '视觉理解' },
  { key: 'DIGEST', label: '资料摘要' },
  { key: 'NOTE', label: 'AI 笔记' },
  { key: 'REPORT', label: '学习指南' },
  { key: 'ILLUSTRATION', label: '主题插图' },
]

function Guide({ guide }: { guide: StudyGuide }) {
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

export default function PackageDetailPage() {
  const { packageId = '' } = useParams()
  const queryClient = useQueryClient()
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
  const note = useQuery({
    queryKey: ['note', packageId],
    queryFn: () => api.note(packageId),
    enabled: Boolean(detail.data?.outputs?.noteReady),
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

  if (detail.isLoading) return <div className="page"><Skeleton active /></div>
  if (!detail.data) return <div className="page"><Empty description="资料包不存在" /></div>
  const item = detail.data
  const currentIndex = Math.max(0, stages.findIndex((stage) => stage.key === item.currentStage))

  return (
    <div className="page detail-page">
      <section className="detail-heading">
        <div>
          <span className="eyebrow">PACKAGE / {item.id.slice(0, 8).toUpperCase()}</span>
          <h1>{item.title}</h1>
        </div>
        <StatusBadge status={item.status} />
      </section>

      {item.warnings?.map((warning) => (
        <Alert key={warning} type="warning" showIcon title={warning} className="detail-alert" />
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
              <MarkdownViewer markdown={note.data} packageId={packageId} />
            ) : (
              <Empty description="笔记生成中" />
            ),
          },
          {
            key: 'guide',
            label: '学习指南',
            children: report.data ? <Guide guide={report.data} /> : <Empty description="学习指南生成中" />,
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
