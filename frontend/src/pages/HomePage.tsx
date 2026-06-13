import {
  ArrowRightOutlined,
  CheckCircleOutlined,
  DeleteOutlined,
  FileAddOutlined,
  LinkOutlined,
} from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Button, Empty, Input, Modal, Select, Skeleton, message } from 'antd'
import type { CSSProperties, MouseEvent } from 'react'
import { useState } from 'react'
import { Link } from 'react-router'
import { api } from '../api'
import StatusBadge from '../components/StatusBadge'
import type { PackageSummary } from '../types'

const deletableStatuses = new Set(['READY', 'PARTIALLY_READY', 'FAILED', 'INTERRUPTED'])
const masteryStatuses = new Set(['READY', 'PARTIALLY_READY'])
type PackageFilters = { q: string; status: string; type: string; mastery: string }
const coverPalettes = [
  { background: '#d9c6a5', accent: '#8f3d27', ink: '#2e2922' },
  { background: '#bfcdb7', accent: '#486247', ink: '#20291f' },
  { background: '#c8c4d6', accent: '#5b5278', ink: '#292635' },
  { background: '#d8b9aa', accent: '#824936', ink: '#30221d' },
  { background: '#b9cbd0', accent: '#315b66', ink: '#1d2b2e' },
]

function coverPalette(id: string) {
  const hash = Array.from(id).reduce((value, char) => (value * 31 + char.charCodeAt(0)) >>> 0, 0)
  return coverPalettes[hash % coverPalettes.length]
}

function PackageCover({ item }: { item: PackageSummary }) {
  const [imageFailed, setImageFailed] = useState(false)
  const imageUrl = item.cover?.imageUrl
  const palette = coverPalette(item.id)
  const keywords = item.cover?.keywords?.slice(0, 3) ?? []
  const style = {
    '--cover-bg': palette.background,
    '--cover-accent': palette.accent,
    '--cover-ink': palette.ink,
  } as CSSProperties

  return (
    <div className="package-cover" style={style} aria-hidden="true">
      <div className="package-cover-fallback">
        <span className="package-cover-kicker">
          {item.status === 'PROCESSING' || item.status === 'QUEUED' ? 'ANALYSING / TOPIC' : 'STUDY / THEME'}
        </span>
        <strong>{item.title}</strong>
        {keywords.length ? (
          <div className="package-cover-keywords">
            {keywords.map((keyword) => <span key={keyword}>{keyword}</span>)}
          </div>
        ) : (
          <small>{item.progress}% CONTENT PROCESSED</small>
        )}
      </div>
      {imageUrl && !imageFailed ? (
        <img
          src={imageUrl}
          alt=""
          loading="lazy"
          onError={() => setImageFailed(true)}
        />
      ) : null}
    </div>
  )
}

export default function HomePage() {
  const queryClient = useQueryClient()
  const [filters, setFilters] = useState<PackageFilters>({
    q: '',
    status: '',
    type: '',
    mastery: 'ACTIVE',
  })
  const packages = useQuery({
    queryKey: ['packages', filters],
    queryFn: () => api.packages(filters),
  })
  const capabilities = useQuery({ queryKey: ['capabilities'], queryFn: api.capabilities })
  const learningOverview = useQuery({
    queryKey: ['learning-overview'],
    queryFn: api.learningOverview,
  })
  const deletePackage = useMutation({
    mutationFn: api.deletePackage,
    onSuccess: () => {
      message.success('资料包已删除')
      queryClient.invalidateQueries({ queryKey: ['packages'] })
    },
    onError: (error: Error) => message.error(error.message),
  })
  const updateMastery = useMutation({
    mutationFn: api.setMastery,
    onMutate: async ({ id, mastered }) => {
      await queryClient.cancelQueries({ queryKey: ['packages'] })
      const snapshots = queryClient.getQueriesData<PackageSummary[]>({ queryKey: ['packages'] })
      const now = new Date().toISOString()
      for (const [queryKey, data] of snapshots) {
        if (!data) continue
        const queryFilters = (queryKey[1] ?? {}) as Partial<PackageFilters>
        const next = data
          .map((item) => item.id === id ? {
            ...item,
            mastery: {
              packageId: id,
              mastered,
              masteredAt: mastered ? now : undefined,
              updatedAt: now,
            },
          } : item)
          .filter((item) => {
            const filter = queryFilters.mastery ?? 'ACTIVE'
            if (filter === 'ALL') return true
            return filter === 'MASTERED' ? item.mastery?.mastered : !item.mastery?.mastered
          })
        queryClient.setQueryData(queryKey, next)
      }
      return { snapshots }
    },
    onError: (error: Error, _variables, context) => {
      context?.snapshots.forEach(([queryKey, data]) => queryClient.setQueryData(queryKey, data))
      message.error(error.message)
    },
    onSuccess: (_data, variables) => {
      message.success(variables.mastered ? '已移入“已掌握”归档' : '已恢复到学习中')
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ['packages'] })
      queryClient.invalidateQueries({ queryKey: ['learning-overview'] })
    },
  })

  function confirmDelete(event: MouseEvent, item: PackageSummary) {
    event.preventDefault()
    event.stopPropagation()
    if (!deletableStatuses.has(item.status)) return
    Modal.confirm({
      title: '删除资料包？',
      content: `将永久删除“${item.title}”及其本地相关文件，此操作不可恢复。`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () => deletePackage.mutateAsync(item.id),
    })
  }

  function toggleMastery(event: MouseEvent, item: PackageSummary) {
    event.preventDefault()
    event.stopPropagation()
    if (!masteryStatuses.has(item.status)) return
    updateMastery.mutate({ id: item.id, mastered: !item.mastery?.mastered })
  }

  return (
    <div className="page home-page">
      <section className="hero-grid">
        <div className="hero-copy">
          <span className="eyebrow">PERSONAL RESEARCH DESK / 01</span>
          <h1>
            把散落的资料，
            <br />
            整理成<span>轻松看</span>的知识。
          </h1>
          <p>
            混合提交 PDF、文本与截图。系统异步解析内容，生成可回到原页、原图和视频时间点的学习笔记。
          </p>
          <div className="hero-actions">
            <Link to="/packages/new">
              <Button type="primary" size="large" icon={<FileAddOutlined />}>
                新建资料包
              </Button>
            </Link>
            <Link to="/videos/new">
              <Button size="large" icon={<LinkOutlined />}>
                解析 B 站视频
              </Button>
            </Link>
          </div>
        </div>
        <div className="capability-board">
          <div className="board-title">AI辅助能力状态</div>
          {capabilities.data ? (
            Object.entries(capabilities.data).map(([name, value]) => (
              <div className="capability-row" key={name}>
                <span>{name}</span>
                <i className={value.available ? 'online' : 'offline'} />
                <small>{value.model ?? value.provider ?? (value.available ? '可用' : '未配置')}</small>
              </div>
            ))
          ) : (
            <Skeleton active paragraph={{ rows: 3 }} />
          )}
        </div>
        <div className="learning-board">
          <div className="board-title">学习概览 / PROGRESS</div>
          {learningOverview.data ? (
            <>
              <div className="learning-metrics">
                <div><strong>{learningOverview.data.masteredTotal}</strong><span>累计掌握</span></div>
                <div><strong>{learningOverview.data.masteredThisWeek}</strong><span>本周新增</span></div>
                <div><strong>{learningOverview.data.currentStreakDays}</strong><span>连续天数</span></div>
              </div>
              <div className="learning-trend" aria-label="最近七天学习趋势">
                {learningOverview.data.trend.map((point) => {
                  const max = Math.max(1, ...learningOverview.data.trend.map((item) => item.masteredCount))
                  return (
                    <div key={point.date}>
                      <i style={{ height: `${Math.max(8, point.masteredCount / max * 100)}%` }} />
                      <small>{new Date(`${point.date}T00:00:00`).toLocaleDateString('zh-CN', { weekday: 'short' })}</small>
                    </div>
                  )
                })}
              </div>
              <div className="learning-keywords">
                {learningOverview.data.recentKeywords.length ? (
                  learningOverview.data.recentKeywords.map((item) => (
                    <span key={item.keyword}>{item.keyword}<sup>{item.count}</sup></span>
                  ))
                ) : <small>标记资料为已掌握后，这里会形成近期关键词。</small>}
              </div>
              <Link className="learning-ask-link" to="/ask">进入全库问答 <ArrowRightOutlined /></Link>
            </>
          ) : (
            <Skeleton active paragraph={{ rows: 4 }} />
          )}
        </div>
      </section>

      <section className="library-section">
        <div className="section-heading">
          <div>
            <span className="eyebrow">ARCHIVE / RECENT</span>
            <h2>我的资料卡片集</h2>
          </div>
          <span>{packages.data?.length ?? 0} 份记录</span>
        </div>
        <div className="library-filters">
          <Input.Search
            allowClear
            placeholder="按标题搜索"
            onSearch={(q) => setFilters((current) => ({ ...current, q }))}
          />
          <Select
            allowClear
            placeholder="状态"
            onChange={(status) => setFilters((current) => ({ ...current, status: status ?? '' }))}
            options={[
              { value: 'QUEUED', label: '排队中' },
              { value: 'PROCESSING', label: '处理中' },
              { value: 'READY', label: '已完成' },
              { value: 'PARTIALLY_READY', label: '部分完成' },
              { value: 'FAILED', label: '失败' },
              { value: 'INTERRUPTED', label: '已中断' },
            ]}
          />
          <Select
            value={filters.mastery}
            aria-label="学习状态"
            onChange={(mastery) => setFilters((current) => ({ ...current, mastery }))}
            options={[
              { value: 'ACTIVE', label: '学习中' },
              { value: 'MASTERED', label: '已掌握' },
              { value: 'ALL', label: '全部资料' },
            ]}
          />
          <Select
            allowClear
            placeholder="类型"
            onChange={(type) => setFilters((current) => ({ ...current, type: type ?? '' }))}
            options={[
              { value: 'MIXED', label: '混合资料' },
              { value: 'VIDEO', label: '视频' },
            ]}
          />
        </div>
        {packages.isLoading ? (
          <Skeleton active />
        ) : packages.data?.length ? (
          <div className="package-grid">
            {packages.data.map((item, index) => (
              <Link className="package-card" to={`/packages/${item.id}`} key={item.id}>
                <div className="card-index">{String(index + 1).padStart(2, '0')}</div>
                <div className="card-actions">
                  <Button
                    aria-label={`${item.mastery?.mastered ? '恢复学习' : '标记已掌握'} ${item.title}`}
                    className={`card-mastery${item.mastery?.mastered ? ' is-mastered' : ''}`}
                    disabled={!masteryStatuses.has(item.status) || updateMastery.isPending}
                    icon={<CheckCircleOutlined />}
                    loading={updateMastery.isPending && updateMastery.variables?.id === item.id}
                    onClick={(event) => toggleMastery(event, item)}
                    title={item.mastery?.mastered ? '恢复到学习中' : '标记为已掌握'}
                    type="text"
                  />
                  <Button
                    aria-label={`删除 ${item.title}`}
                    className="card-delete"
                    disabled={!deletableStatuses.has(item.status) || deletePackage.isPending}
                    icon={<DeleteOutlined />}
                    onClick={(event) => confirmDelete(event, item)}
                    title={deletableStatuses.has(item.status) ? '删除资料包' : '处理中不可删除'}
                    type="text"
                  />
                </div>
                <PackageCover key={`${item.id}-${item.cover?.imageUrl ?? 'fallback'}`} item={item} />
                <div className="package-card-body">
                  <div className="card-topline">
                    <span>{item.packageType === 'VIDEO' ? 'VIDEO' : 'MIXED SOURCE'}</span>
                    <StatusBadge status={item.status} />
                  </div>
                  <h3 title={item.title}>{item.title}</h3>
                  <div className="card-meta">
                    <span>{new Date(item.createdAt).toLocaleString('zh-CN')}</span>
                    <ArrowRightOutlined />
                  </div>
                </div>
              </Link>
            ))}
          </div>
        ) : (
          <Empty description="还没有资料包，先提交一份学习材料。" />
        )}
      </section>
    </div>
  )
}
