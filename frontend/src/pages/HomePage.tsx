import {
  ArrowRightOutlined,
  CheckCircleOutlined,
  CloseOutlined,
  DeleteOutlined,
  DownOutlined,
  EyeOutlined,
  EyeInvisibleOutlined,
  FileAddOutlined,
  FolderAddOutlined,
  InboxOutlined,
  LinkOutlined,
  PlusOutlined,
  ReloadOutlined,
} from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Button, Checkbox, Dropdown, Empty, Input, Modal, Segmented, Select, Skeleton, message } from 'antd'
import type { CSSProperties, DragEvent, MouseEvent, PointerEvent as ReactPointerEvent } from 'react'
import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router'
import { api } from '../api'
import StatusBadge from '../components/StatusBadge'
import type { LearningPlanStep, PackageStatus, PackageSummary } from '../types'

const deletableStatuses = new Set<PackageStatus>(['READY', 'PARTIALLY_READY', 'FAILED', 'INTERRUPTED'])
const masteryStatuses = new Set<PackageStatus>(['READY', 'PARTIALLY_READY'])
const learningArchiveHiddenStorageKey = 'learningArchiveHiddenPackageIds:v1'
const coverVariantStorageKey = 'packageCoverVariant:v1'
type CoverVariant = 'classic' | 'whiteboard'
type PackageFilters = { q: string; status: string; type: string; mastery: string }
const coverPalettes = [
  { background: '#d9c6a5', accent: '#8f3d27', ink: '#2e2922' },
  { background: '#bfcdb7', accent: '#486247', ink: '#20291f' },
  { background: '#c8c4d6', accent: '#5b5278', ink: '#292635' },
  { background: '#d8b9aa', accent: '#824936', ink: '#30221d' },
  { background: '#b9cbd0', accent: '#315b66', ink: '#1d2b2e' },
]
type FloatingDropPosition = { x: number; y: number }
type FloatingDropDrag = { offsetX: number; offsetY: number; width: number; height: number }
type ImagePreview = { title: string; imageUrl: string }
const coverVariantLabels: Record<CoverVariant, { short: string; action: string; title: string }> = {
  classic: { short: '图一', action: '生成图一', title: '抽象海报' },
  whiteboard: { short: '图二', action: '生成图二', title: '白板信息图' },
}

const stepStatusLabels: Record<LearningPlanStep['status'], string> = {
  TODO: '待学',
  IN_PROGRESS: '进行中',
  DONE: '已完成',
}

function coverPalette(id: string) {
  const hash = Array.from(id).reduce((value, char) => (value * 31 + char.charCodeAt(0)) >>> 0, 0)
  return coverPalettes[hash % coverPalettes.length]
}

function readHiddenArchiveIds() {
  try {
    const value = window.localStorage.getItem(learningArchiveHiddenStorageKey)
    const parsed = value ? JSON.parse(value) : []
    return Array.isArray(parsed) ? parsed.filter((item): item is string => typeof item === 'string') : []
  } catch {
    return []
  }
}

function writeHiddenArchiveIds(ids: string[]) {
  try {
    window.localStorage.setItem(learningArchiveHiddenStorageKey, JSON.stringify(ids))
  } catch {
    // Ignore storage failures so the archive controls remain usable in restricted browsers.
  }
}

function readCoverVariant(): CoverVariant {
  try {
    const value = window.localStorage.getItem(coverVariantStorageKey)
    return value === 'whiteboard' ? 'whiteboard' : 'classic'
  } catch {
    return 'classic'
  }
}

function writeCoverVariant(value: CoverVariant) {
  try {
    window.localStorage.setItem(coverVariantStorageKey, value)
  } catch {
    // Keep the switch usable even when localStorage is unavailable.
  }
}

function PackageCover({
  item,
  variant,
  generating,
  onGenerate,
  onPreview,
}: {
  item: PackageSummary
  variant: CoverVariant
  generating: boolean
  onGenerate: (item: PackageSummary, variant: CoverVariant, replace?: boolean) => void
  onPreview: (preview: ImagePreview) => void
}) {
  const [imageFailed, setImageFailed] = useState(false)
  const variantState = item.cover?.visualVariants?.[variant]
  const imageUrl = variantState?.imageUrl ?? (variant === 'classic' ? item.cover?.imageUrl : undefined)
  const palette = coverPalette(item.id)
  const style = {
    '--cover-bg': palette.background,
    '--cover-accent': palette.accent,
    '--cover-ink': palette.ink,
  } as CSSProperties

  const hasImage = Boolean(imageUrl && !imageFailed)
  const canGenerate = masteryStatuses.has(item.status)
  const isGenerating = generating || Boolean(variantState?.generating)
  const label = coverVariantLabels[variant]
  const generate = (event: MouseEvent<HTMLElement>, replace = false) => {
    event.preventDefault()
    event.stopPropagation()
    if (canGenerate && !isGenerating) onGenerate(item, variant, replace)
  }
  const preview = (event: MouseEvent<HTMLElement>) => {
    event.preventDefault()
    event.stopPropagation()
    if (imageUrl && hasImage) onPreview({ title: item.title, imageUrl })
  }

  return (
    <div className={`package-cover${hasImage ? ' has-image' : ' is-fallback'} cover-${variant}`} style={style}>
      {imageUrl && !imageFailed ? (
        <img
          src={imageUrl}
          alt=""
          loading="lazy"
          onError={() => setImageFailed(true)}
        />
      ) : null}
      {hasImage ? (
        <div className="package-cover-tools">
          <Button
            aria-label={`查看大图 ${item.title}`}
            icon={<EyeOutlined />}
            onClick={preview}
            onMouseDown={(event) => event.stopPropagation()}
            size="small"
            title="查看大图"
            type="text"
          />
          <Button
            aria-label={`重新生成${label.short} ${item.title}`}
            disabled={!canGenerate}
            icon={<ReloadOutlined />}
            loading={isGenerating}
            onClick={(event) => generate(event, true)}
            onMouseDown={(event) => event.stopPropagation()}
            size="small"
            title={`重新生成${label.short}`}
            type="text"
          />
        </div>
      ) : null}
      <div className="package-cover-fallback-art">
        <i />
        <i />
        <i />
        <b />
      </div>
      {!hasImage ? (
        <div className="package-cover-missing">
          <span>{label.short} / {label.title}</span>
          <Button
            disabled={!canGenerate}
            icon={<ReloadOutlined />}
            loading={isGenerating}
            onClick={generate}
            onMouseDown={(event) => event.stopPropagation()}
            size="small"
          >
            {canGenerate ? label.action : '处理中'}
          </Button>
          {variantState?.errorMessage ? <small>{variantState.errorMessage}</small> : null}
        </div>
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
  const [hiddenArchiveIds, setHiddenArchiveIds] = useState(readHiddenArchiveIds)
  const [draggingPackageId, setDraggingPackageId] = useState<string | null>(null)
  const [planDropActive, setPlanDropActive] = useState(false)
  const [planDropPosition, setPlanDropPosition] = useState<FloatingDropPosition | null>(null)
  const [planDropDrag, setPlanDropDrag] = useState<FloatingDropDrag | null>(null)
  const [coverVariant, setCoverVariantState] = useState<CoverVariant>(readCoverVariant)
  const [previewImage, setPreviewImage] = useState<ImagePreview | null>(null)

  const packages = useQuery({
    queryKey: ['packages', filters],
    queryFn: () => api.packages(filters),
    refetchInterval: (query) => {
      const items = query.state.data
      return items?.some((item) => item.status === 'QUEUED' || item.status === 'PROCESSING'
        || Object.values(item.cover?.visualVariants ?? {}).some((variant) => variant.generating))
        ? 1500
        : false
    },
  })
  const capabilities = useQuery({ queryKey: ['capabilities'], queryFn: api.capabilities })
  const learningOverview = useQuery({
    queryKey: ['learning-overview'],
    queryFn: api.learningOverview,
  })
  const learningPlan = useQuery({
    queryKey: ['learning-plan'],
    queryFn: api.learningPlan,
  })

  const deletePackage = useMutation({
    mutationFn: (item: PackageSummary) => api.deletePackage(item.id),
    onSuccess: (_data, item) => {
      message.success(item.mastery?.mastered
        ? '学习成果已归档，源资料已删除'
        : '资料包已删除')
      queryClient.invalidateQueries({ queryKey: ['packages'] })
      queryClient.invalidateQueries({ queryKey: ['learning-overview'] })
      queryClient.invalidateQueries({ queryKey: ['learning-plan'] })
    },
    onError: (error: Error) => message.error(error.message),
  })

  const generateIllustration = useMutation({
    mutationFn: ({ item, variant, replace = false }: {
      item: PackageSummary
      variant: CoverVariant
      replace?: boolean
    }) =>
      api.generatePackageIllustration({ id: item.id, variant, ...(replace ? { replace: true } : {}) }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['packages'] })
      message.success('万象图生成任务已提交')
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

  const savePlanPackages = useMutation({
    mutationFn: api.saveLearningPlanPackages,
    onSuccess: (data) => {
      queryClient.setQueryData(['learning-plan'], data)
    },
    onError: (error: Error) => message.error(error.message),
  })
  const generatePlan = useMutation({
    mutationFn: api.generateLearningPlan,
    onSuccess: (data) => {
      queryClient.setQueryData(['learning-plan'], data)
      message.success('学习计划已生成')
    },
    onError: (error: Error) => message.error(error.message),
  })
  const updatePlanStep = useMutation({
    mutationFn: api.updateLearningPlanStep,
    onSuccess: (data) => queryClient.setQueryData(['learning-plan'], data),
    onError: (error: Error) => message.error(error.message),
  })
  const resetPlan = useMutation({
    mutationFn: api.resetLearningPlan,
    onSuccess: (data) => {
      queryClient.setQueryData(['learning-plan'], data)
      message.success('学习计划已重置')
    },
    onError: (error: Error) => message.error(error.message),
  })

  const planPackages = learningPlan.data?.packages ?? []
  const selectedPlanPackageIds = useMemo(
    () => planPackages.map((item) => item.packageId),
    [planPackages],
  )
  const planProgress = learningPlan.data?.progress ?? 0
  const planSteps = learningPlan.data?.steps ?? []
  const archiveItems = learningOverview.data?.deletedMastered ?? []
  const hiddenArchiveIdSet = new Set(hiddenArchiveIds)
  const visibleArchiveItems = archiveItems.filter((item) => !hiddenArchiveIdSet.has(item.packageId))
  const pinnedArchiveItems = visibleArchiveItems.slice(0, 2)
  const hasHiddenArchiveItems = archiveItems.some((item) => hiddenArchiveIdSet.has(item.packageId))
  const shouldShowArchiveMore = archiveItems.length > 2 || hasHiddenArchiveItems

  useEffect(() => {
    if (!learningOverview.data) return
    const archiveIds = new Set(learningOverview.data.deletedMastered.map((item) => item.packageId))
    setHiddenArchiveIds((current) => {
      const next = current.filter((id) => archiveIds.has(id))
      if (next.length === current.length) return current
      writeHiddenArchiveIds(next)
      return next
    })
  }, [learningOverview.data])

  useEffect(() => {
    if (!planDropDrag) return
    const drag = planDropDrag
    function move(event: PointerEvent) {
      const margin = 14
      setPlanDropPosition({
        x: Math.min(
          Math.max(margin, event.clientX - drag.offsetX),
          window.innerWidth - drag.width - margin,
        ),
        y: Math.min(
          Math.max(margin, event.clientY - drag.offsetY),
          window.innerHeight - drag.height - margin,
        ),
      })
    }
    function stop() {
      setPlanDropDrag(null)
    }
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', stop, { once: true })
    return () => {
      window.removeEventListener('pointermove', move)
      window.removeEventListener('pointerup', stop)
    }
  }, [planDropDrag])

  function confirmDelete(event: MouseEvent, item: PackageSummary) {
    event.preventDefault()
    event.stopPropagation()
    if (!deletableStatuses.has(item.status)) return
    const isMastered = item.mastery?.mastered === true
    Modal.confirm({
      title: isMastered ? '归档学习成果？' : '删除资料包？',
      content: isMastered
        ? `将永久删除“${item.title}”及其本地相关文件，无法恢复；学习成果会继续保留在学习概览中。`
        : `将永久删除“${item.title}”及其本地相关文件，此操作不可恢复。`,
      okText: isMastered ? '归档' : '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () => deletePackage.mutateAsync(item),
    })
  }

  function toggleMastery(event: MouseEvent, item: PackageSummary) {
    event.preventDefault()
    event.stopPropagation()
    if (!masteryStatuses.has(item.status)) return
    updateMastery.mutate({ id: item.id, mastered: !item.mastery?.mastered })
  }

  function addPackageToPlan(packageId: string) {
    if (selectedPlanPackageIds.includes(packageId)) {
      message.info('这份资料已经在学习计划里')
      return
    }
    savePlanPackages.mutate([...selectedPlanPackageIds, packageId])
  }

  function addPackageButton(event: MouseEvent, item: PackageSummary) {
    event.preventDefault()
    event.stopPropagation()
    addPackageToPlan(item.id)
  }

  function setCoverVariant(value: CoverVariant) {
    setCoverVariantState(value)
    writeCoverVariant(value)
  }

  function generatePackageCover(item: PackageSummary, variant: CoverVariant, replace = false) {
    generateIllustration.mutate({ item, variant, replace })
  }

  function removePlanPackage(packageId: string) {
    savePlanPackages.mutate(selectedPlanPackageIds.filter((id) => id !== packageId))
  }

  function confirmResetPlan(event?: MouseEvent) {
    event?.preventDefault()
    event?.stopPropagation()
    Modal.confirm({
      title: '重置学习计划？',
      content: '将清空当前 PLAN 的待规划资料、步骤、学习记录和重排建议；资料卡片本身不会删除。',
      okText: '重置',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: () => resetPlan.mutateAsync(),
    })
  }

  function handlePlanDrop(event: DragEvent<HTMLDivElement>) {
    event.preventDefault()
    setPlanDropActive(false)
    const packageId = event.dataTransfer.getData('application/x-ai-package-id')
    if (packageId) addPackageToPlan(packageId)
    setDraggingPackageId(null)
  }

  function startPlanDropDrag(event: ReactPointerEvent<HTMLElement>) {
    if (event.button !== 0) return
    const target = event.target as HTMLElement
    if (!target.closest('.floating-plan-drop-handle')
      && target.closest('button, a, input, textarea, .ant-input-number')) return
    const panel = event.currentTarget.classList.contains('floating-plan-drop')
      ? event.currentTarget
      : event.currentTarget.closest('.floating-plan-drop')
    if (!(panel instanceof HTMLElement)) return
    event.preventDefault()
    const rect = panel.getBoundingClientRect()
    setPlanDropDrag({
      offsetX: event.clientX - rect.left,
      offsetY: event.clientY - rect.top,
      width: rect.width,
      height: rect.height,
    })
    setPlanDropPosition({ x: rect.left, y: rect.top })
  }

  function setArchiveItemVisible(packageId: string, visible: boolean) {
    setHiddenArchiveIds((current) => {
      const next = new Set(current)
      if (visible) next.delete(packageId)
      else next.add(packageId)
      const values = Array.from(next)
      writeHiddenArchiveIds(values)
      return values
    })
  }

  return (
    <div className="page home-page">
      <div
        className={`floating-plan-drop${planDropActive ? ' is-active' : ''}${planDropDrag ? ' is-moving' : ''}`}
        onDragEnter={(event) => {
          event.preventDefault()
          setPlanDropActive(true)
        }}
        onDragOver={(event) => event.preventDefault()}
        onDragLeave={() => setPlanDropActive(false)}
        onDrop={handlePlanDrop}
        style={planDropPosition ? {
          bottom: 'auto',
          left: planDropPosition.x,
          right: 'auto',
          top: planDropPosition.y,
        } : undefined}
      >
        <button
          aria-label="拖动学习计划投放区"
          className="floating-plan-drop-handle"
          onPointerDown={startPlanDropDrag}
          type="button"
        >
          PLAN
        </button>
        <FolderAddOutlined />
        <div>
          <strong>拖入资料卡片做学习规划</strong>
          <span>{planPackages.length ? `${planPackages.length} 份资料已加入` : '初始在右下角，可拖动位置'}</span>
          {planPackages.length ? (
            <div className="plan-package-list is-floating">
              {planPackages.map((item) => (
                <div className="plan-package-pill" key={item.packageId}>
                  <div>
                    <strong>{item.title}</strong>
                    <span>{item.keywords.slice(0, 2).join(' / ') || item.status}</span>
                  </div>
                  <Button
                    aria-label={`从学习计划移除 ${item.title}`}
                    disabled={savePlanPackages.isPending || generatePlan.isPending || resetPlan.isPending}
                    icon={<CloseOutlined />}
                    onClick={(event) => {
                      event.stopPropagation()
                      removePlanPackage(item.packageId)
                    }}
                    size="small"
                    type="text"
                  />
                </div>
              ))}
            </div>
          ) : null}
          <button
            className="floating-plan-generate"
            disabled={!planPackages.length || savePlanPackages.isPending || resetPlan.isPending}
            onClick={(event) => {
              event.stopPropagation()
              generatePlan.mutate()
            }}
            type="button"
          >
            {generatePlan.isPending ? <ReloadOutlined spin /> : <FolderAddOutlined />}
            {learningPlan.data?.generatedAt ? '重新生成计划' : '生成学习计划'}
          </button>
        </div>
      </div>
      <section className="hero-grid">
        <div className="hero-copy">
          <span className="eyebrow">PERSONAL RESEARCH DESK / 01</span>
          <h1>
            把你的资料丢来，
            <br />
            我用<span>AI</span>整理成<span>专属的</span>
            <br />的个人知识库。
          </h1>
          <p>
            混合提交 PDF、文本与截图。系统异步解析内容，生成可回到原页、原图和视频时间点的学习笔记。
          </p>
          <div className="hero-actions">
            <div className="hero-action-card">
              <div className="hero-action-card-label" aria-hidden="true">
                NEW
              </div>
              <Link className="hero-action-tile is-primary" to="/packages/new">
                <FileAddOutlined />
                <span>新建资料包</span>
              </Link>
              <Link className="hero-action-tile" to="/videos/new">
                <LinkOutlined />
                <span>解析 B 站视频</span>
              </Link>
            </div>
          </div>
        </div>
        <div className="hero-side-stack">
          <div className="plan-board">
            <div className="board-title">学习计划 / PLAN</div>
            {learningPlan.isLoading ? (
              <Skeleton active paragraph={{ rows: 5 }} />
            ) : (
              <>
                <div className="plan-status">
                  <div className="plan-meter" style={{ '--plan-progress': `${planProgress * 3.6}deg` } as CSSProperties}>
                    <strong>{planProgress}%</strong>
                    <span>总进度</span>
                  </div>
                  <div className="plan-summary">
                    <strong>{learningPlan.data?.title || '当前学习计划'}</strong>
                    <span>{learningPlan.data?.generatedAt ? `${learningPlan.data.estimatedMinutes} 分钟` : '等待资料加入'}</span>
                  </div>
                </div>
                <div className="plan-quick-summary">
                  <span>{learningPlan.data?.weeklySummary || 'PLAN v2 工作台会在生成后显示本周节奏'}</span>
                  <strong>{learningPlan.data?.todaySteps?.length ?? 0} today</strong>
                </div>
                <div className="plan-stage-grid" aria-label="学习计划阶段进度">
                  {planSteps.length ? (
                    planSteps.map((step) => (
                      <button
                        aria-label={`${stepStatusLabels[step.status]} ${step.title}`}
                        className={`plan-step-tile is-${step.status.toLowerCase()}`}
                        disabled={updatePlanStep.isPending}
                        key={step.stepId}
                        onClick={() => updatePlanStep.mutate({
                          stepId: step.stepId,
                          completed: step.status !== 'DONE',
                        })}
                        type="button"
                      >
                        <span>{String(step.position + 1).padStart(2, '0')}</span>
                        <strong>{step.title}</strong>
                        <small>{stepStatusLabels[step.status]}</small>
                      </button>
                    ))
                  ) : (
                    ['目标拆解', '资料匹配', '复习节奏'].map((label, index) => (
                      <span className="plan-step-tile is-empty" key={label}>
                        <span>{String(index + 1).padStart(2, '0')}</span>
                        <strong>{label}</strong>
                        <small>待生成</small>
                      </span>
                    ))
                  )}
                </div>
                {learningPlan.data?.overview ? <p>{learningPlan.data.overview}</p> : (
                  <p>把资料卡片拖入计划后，AI 会根据学习目标、资料内容和复习节奏生成路线。</p>
                )}
                <Link className="plan-workbench-link" to="/plan">
                  进入 PLAN 工作台 <ArrowRightOutlined />
                </Link>
                <Button
                  block
                  className="plan-reset-button"
                  danger
                  disabled={!planPackages.length && !planSteps.length}
                  icon={<DeleteOutlined />}
                  loading={resetPlan.isPending}
                  onClick={confirmResetPlan}
                >
                  重置学习计划
                </Button>
              </>
            )}
          </div>
          <div className="capability-board">
            <div className="board-title">AI 辅助能力状态</div>
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
        </div>
        <div className="learning-board">
          <div className="board-title">学习概览 / PROGRESS</div>
          {learningOverview.data ? (
            <>
              <div className="learning-metrics">
                <div><strong>{learningOverview.data.masteredTotal}</strong><span>当前掌握</span></div>
                <div><strong>{learningOverview.data.deletedMasteredTotal}</strong><span>已删除留档</span></div>
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
              {archiveItems.length ? (
                <div className="learning-archive">
                  <div className="learning-archive-title">
                    <span>熟练掌握的学习成果</span>
                    <div className="learning-archive-actions">
                      {shouldShowArchiveMore ? (
                        <Dropdown
                          trigger={['click']}
                          placement="bottomRight"
                          menu={{ items: [] }}
                          popupRender={() => (
                            <div className="learning-archive-menu">
                              {archiveItems.map((item) => (
                                <Checkbox
                                  checked={!hiddenArchiveIdSet.has(item.packageId)}
                                  className="learning-archive-option"
                                  key={item.packageId}
                                  onChange={(event) => setArchiveItemVisible(item.packageId, event.target.checked)}
                                >
                                  <span className="learning-archive-option-title">{item.title}</span>
                                  <time dateTime={item.deletedAt}>
                                    {new Date(item.deletedAt).toLocaleDateString('zh-CN')}
                                  </time>
                                </Checkbox>
                              ))}
                            </div>
                          )}
                        >
                          <Button className="learning-archive-more" size="small" type="text">
                            more <DownOutlined />
                          </Button>
                        </Dropdown>
                      ) : null}
                      <small>RECENT {archiveItems.length}</small>
                    </div>
                  </div>
                  {pinnedArchiveItems.map((item) => (
                    <div className="learning-archive-item" key={item.packageId}>
                      <div>
                        <strong>{item.title}</strong>
                        <small>
                          {item.keywords.slice(0, 3).join(' / ') || '暂无关键词'}
                        </small>
                      </div>
                      <time dateTime={item.deletedAt}>
                        {new Date(item.deletedAt).toLocaleDateString('zh-CN')}
                      </time>
                      <Button
                        aria-label={`Hide learning result ${item.title}`}
                        className="learning-archive-hide"
                        icon={<EyeInvisibleOutlined />}
                        onClick={() => setArchiveItemVisible(item.packageId, false)}
                        size="small"
                        title="Hide"
                        type="text"
                      />
                    </div>
                  ))}
                </div>
              ) : null}
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
            <div className="cover-variant-tools">
              <Segmented<CoverVariant>
                aria-label="万象图模式"
                onChange={setCoverVariant}
                options={[
                  { label: '图一 抽象海报', value: 'classic' },
                  { label: '图二 白板信息图', value: 'whiteboard' },
                ]}
                size="small"
                value={coverVariant}
              />
            </div>
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
              <Link
                className={`package-card${draggingPackageId === item.id ? ' is-dragging' : ''}`}
                draggable
                key={item.id}
                onDragEnd={() => setDraggingPackageId(null)}
                onDragStart={(event) => {
                  setDraggingPackageId(item.id)
                  event.dataTransfer.effectAllowed = 'copy'
                  event.dataTransfer.setData('application/x-ai-package-id', item.id)
                }}
                to={`/packages/${item.id}`}
              >
                <div className="card-index">{String(index + 1).padStart(2, '0')}</div>
                <div className="card-actions">
                  <Button
                    aria-label={`加入学习计划 ${item.title}`}
                    className="card-plan-add"
                    disabled={savePlanPackages.isPending || selectedPlanPackageIds.includes(item.id)}
                    icon={<PlusOutlined />}
                    onClick={(event) => addPackageButton(event, item)}
                    title={selectedPlanPackageIds.includes(item.id) ? '已在学习计划中' : '加入学习计划'}
                    type="text"
                  />
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
                    aria-label={`${item.mastery?.mastered ? '归档学习成果' : '删除资料包'} ${item.title}`}
                    className={item.mastery?.mastered ? 'card-archive' : 'card-delete'}
                    disabled={!deletableStatuses.has(item.status) || deletePackage.isPending}
                    icon={item.mastery?.mastered ? <InboxOutlined /> : <DeleteOutlined />}
                    onClick={(event) => confirmDelete(event, item)}
                    title={deletableStatuses.has(item.status)
                      ? item.mastery?.mastered ? '归档学习成果' : '删除资料包'
                      : '处理中不可删除'}
                    type="text"
                  />
                </div>
                <PackageCover
                  generating={generateIllustration.isPending
                    && generateIllustration.variables?.item.id === item.id
                    && generateIllustration.variables?.variant === coverVariant}
                  item={item}
                  key={`${item.id}-${coverVariant}-${item.cover?.visualVariants?.[coverVariant]?.imageUrl ?? item.cover?.imageUrl ?? 'fallback'}`}
                  onGenerate={generatePackageCover}
                  onPreview={setPreviewImage}
                  variant={coverVariant}
                />
                <div className="package-card-body">
                  <div className="card-topline">
                    <div className="card-labels">
                      <span>{item.packageType === 'VIDEO' ? 'VIDEO' : 'MIXED SOURCE'}</span>
                      <StatusBadge status={item.status} />
                    </div>
                  </div>
                  <h3 title={item.title}>{item.title}</h3>
                  {item.cover?.keywords?.length ? (
                    <div className="card-keywords">
                      {item.cover.keywords.slice(0, 3).map((keyword) => <span key={keyword}>{keyword}</span>)}
                    </div>
                  ) : null}
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
      <Modal
        centered
        className="image-preview-modal"
        footer={null}
        onCancel={() => setPreviewImage(null)}
        open={Boolean(previewImage)}
        title={previewImage?.title}
        width={1040}
      >
        {previewImage ? <img src={previewImage.imageUrl} alt={previewImage.title} /> : null}
      </Modal>
    </div>
  )
}
