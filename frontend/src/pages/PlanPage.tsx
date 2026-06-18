import {
  CalendarOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  FieldTimeOutlined,
  FolderOpenOutlined,
  ReloadOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Button, Empty, Input, InputNumber, Skeleton, message } from 'antd'
import type { CSSProperties } from 'react'
import { useMemo, useState } from 'react'
import { Link } from 'react-router'
import { api } from '../api'
import type { LearningPlanReplanProposal, LearningPlanStep } from '../types'

const stepStatusLabels: Record<LearningPlanStep['status'], string> = {
  TODO: '待学',
  IN_PROGRESS: '进行中',
  DONE: '已完成',
}

function dateLabel(value?: string) {
  if (!value) return '未排期'
  return new Date(`${value}T00:00:00`).toLocaleDateString('zh-CN', {
    month: 'short',
    day: 'numeric',
    weekday: 'short',
  })
}

function isoToday() {
  return new Date().toISOString().slice(0, 10)
}

export default function PlanPage() {
  const queryClient = useQueryClient()
  const [selectedStepId, setSelectedStepId] = useState<string | null>(null)
  const [sessionMinutes, setSessionMinutes] = useState<number | null>(25)
  const [sessionNote, setSessionNote] = useState('')
  const [scheduleDate, setScheduleDate] = useState(isoToday())
  const [scheduleMinutes, setScheduleMinutes] = useState<number | null>(30)
  const [reflection, setReflection] = useState('')
  const [replanFeedback, setReplanFeedback] = useState('')
  const [proposal, setProposal] = useState<LearningPlanReplanProposal | null>(null)

  const learningPlan = useQuery({
    queryKey: ['learning-plan'],
    queryFn: api.learningPlan,
  })

  const plan = learningPlan.data
  const steps = plan?.steps ?? []
  const selectedStep = useMemo(
    () => steps.find((step) => step.stepId === selectedStepId) ?? steps[0],
    [selectedStepId, steps],
  )
  const todaySteps = plan?.todaySteps?.length ? plan.todaySteps : steps.filter((step) => step.scheduledDate === isoToday())
  const weekSteps = steps.filter((step) => step.scheduledDate).slice(0, 7)

  const updateStep = useMutation({
    mutationFn: api.updateLearningPlanStep,
    onSuccess: (data) => {
      queryClient.setQueryData(['learning-plan'], data)
    },
    onError: (error: Error) => message.error(error.message),
  })

  const recordSession = useMutation({
    mutationFn: api.recordLearningPlanSession,
    onSuccess: (data) => {
      queryClient.setQueryData(['learning-plan'], data)
      setSessionNote('')
      message.success('学习记录已保存')
    },
    onError: (error: Error) => message.error(error.message),
  })

  const updateSchedule = useMutation({
    mutationFn: api.updateLearningPlanSchedule,
    onSuccess: (data) => {
      queryClient.setQueryData(['learning-plan'], data)
      message.success('排期已更新')
    },
    onError: (error: Error) => message.error(error.message),
  })

  const replan = useMutation({
    mutationFn: api.replanLearningPlan,
    onSuccess: (data) => {
      setProposal(data)
      message.success('重排建议已生成')
    },
    onError: (error: Error) => message.error(error.message),
  })

  const applyReplan = useMutation({
    mutationFn: api.applyLearningPlanReplan,
    onSuccess: (data) => {
      queryClient.setQueryData(['learning-plan'], data)
      setProposal(null)
      message.success('重排建议已应用')
    },
    onError: (error: Error) => message.error(error.message),
  })

  function selectStep(step: LearningPlanStep) {
    setSelectedStepId(step.stepId)
    setScheduleDate(step.scheduledDate ?? isoToday())
    setScheduleMinutes(step.estimatedMinutes || 30)
    setReflection(step.reflection ?? '')
  }

  if (learningPlan.isLoading) {
    return (
      <div className="page plan-workbench-page">
        <Skeleton active paragraph={{ rows: 12 }} />
      </div>
    )
  }

  if (!plan?.steps.length) {
    return (
      <div className="page plan-workbench-page">
        <section className="plan-empty-state">
          <span className="eyebrow">PLAN WORKBENCH / V2</span>
          <h1>当前还没有可执行学习计划</h1>
          <p>先回到首页，把资料卡片拖入 PLAN 投放区，再生成一份带排期的学习路线。</p>
          <Link to="/">
            <Button type="primary" icon={<FolderOpenOutlined />}>回到资料卡片集</Button>
          </Link>
        </section>
      </div>
    )
  }

  return (
    <div className="page plan-workbench-page">
      <section className="plan-workbench-heading">
        <div>
          <span className="eyebrow">PLAN WORKBENCH / V2</span>
          <h1>{plan.title || '当前学习计划'}</h1>
          <p>{plan.overview || '按资料包顺序推进，完成每一步后记录学习过程。'}</p>
        </div>
        <div className="plan-workbench-meter">
          <div className="plan-meter is-large" style={{ '--plan-progress': `${plan.progress * 3.6}deg` } as CSSProperties}>
            <strong>{plan.progress}%</strong>
            <span>总进度</span>
          </div>
          <small>v{plan.version} / {plan.estimatedMinutes} min</small>
        </div>
      </section>

      <section className="plan-workbench-grid">
        <aside className="plan-today-panel">
          <div className="board-title">TODAY / WEEK</div>
          <div className="plan-week-summary">
            <CalendarOutlined />
            <strong>{plan.weeklySummary || '本周暂无排期'}</strong>
          </div>
          <h2>今日任务</h2>
          {todaySteps.length ? todaySteps.map((step) => (
            <button
              className={`plan-mini-task is-${step.status.toLowerCase()}`}
              key={step.stepId}
              onClick={() => selectStep(step)}
              type="button"
            >
              <span>{dateLabel(step.scheduledDate)}</span>
              <strong>{step.title}</strong>
              <small>{step.estimatedMinutes} min / {stepStatusLabels[step.status]}</small>
            </button>
          )) : <Empty description="今天没有排期" />}
          <h2>本周节奏</h2>
          <div className="plan-week-list">
            {weekSteps.map((step) => (
              <button key={step.stepId} onClick={() => selectStep(step)} type="button">
                <time>{dateLabel(step.scheduledDate)}</time>
                <span>{step.stageLabel || '学习'}</span>
                <strong>{step.title}</strong>
              </button>
            ))}
          </div>
        </aside>

        <main className="plan-timeline-panel">
          <div className="board-title">STEPS / SQUARE PROGRESS</div>
          <div className="plan-stage-grid is-workbench" aria-label="学习计划阶段进度">
            {steps.map((step) => (
              <button
                aria-label={`${stepStatusLabels[step.status]} ${step.title}`}
                className={`plan-step-tile is-${step.status.toLowerCase()}`}
                key={step.stepId}
                onClick={() => selectStep(step)}
                type="button"
              >
                <span>{String(step.position + 1).padStart(2, '0')}</span>
                <strong>{step.title}</strong>
                <small>{step.stageLabel || stepStatusLabels[step.status]}</small>
              </button>
            ))}
          </div>
          <div className="plan-step-list">
            {steps.map((step) => (
              <article
                className={`plan-step-row is-${step.status.toLowerCase()}${selectedStep?.stepId === step.stepId ? ' is-selected' : ''}`}
                key={step.stepId}
                onClick={() => selectStep(step)}
              >
                <div>
                  <span>{dateLabel(step.scheduledDate)}</span>
                  <h3>{step.title}</h3>
                  <p>{step.description}</p>
                </div>
                <div className="plan-step-row-meta">
                  <span><ClockCircleOutlined /> {step.actualMinutes}/{step.estimatedMinutes} min</span>
                  <Button
                    icon={<CheckCircleOutlined />}
                    loading={updateStep.isPending && updateStep.variables?.stepId === step.stepId}
                    onClick={(event) => {
                      event.stopPropagation()
                      updateStep.mutate({ stepId: step.stepId, completed: step.status !== 'DONE' })
                    }}
                    size="small"
                  >
                    {step.status === 'DONE' ? '取消完成' : '完成'}
                  </Button>
                </div>
              </article>
            ))}
          </div>
        </main>

        <aside className="plan-action-panel">
          <div className="board-title">RECORD / REPLAN</div>
          {selectedStep ? (
            <>
              <section>
                <h2>{selectedStep.title}</h2>
                <p>{selectedStep.description}</p>
                <div className="plan-step-fields">
                  <label>
                    日期
                    <input value={scheduleDate} onChange={(event) => setScheduleDate(event.target.value)} type="date" />
                  </label>
                  <label>
                    预计分钟
                    <InputNumber min={0} value={scheduleMinutes} onChange={setScheduleMinutes} />
                  </label>
                  <label className="is-wide">
                    反思
                    <Input.TextArea value={reflection} onChange={(event) => setReflection(event.target.value)} rows={3} />
                  </label>
                </div>
                <Button
                  block
                  icon={<FieldTimeOutlined />}
                  loading={updateSchedule.isPending}
                  onClick={() => updateSchedule.mutate({
                    stepId: selectedStep.stepId,
                    scheduledDate: scheduleDate,
                    estimatedMinutes: scheduleMinutes ?? 0,
                    reflection,
                  })}
                >
                  保存排期
                </Button>
              </section>
              <section>
                <h2>记录一次学习</h2>
                <div className="plan-session-form">
                  <InputNumber min={1} value={sessionMinutes} onChange={setSessionMinutes} addonAfter="min" />
                  <Input.TextArea
                    placeholder="这一步的卡点、收获或下一步提醒"
                    value={sessionNote}
                    onChange={(event) => setSessionNote(event.target.value)}
                    rows={3}
                  />
                  <Button
                    type="primary"
                    icon={<ClockCircleOutlined />}
                    loading={recordSession.isPending}
                    onClick={() => recordSession.mutate({
                      stepId: selectedStep.stepId,
                      minutes: sessionMinutes ?? 0,
                      note: sessionNote,
                    })}
                  >
                    保存学习记录
                  </Button>
                </div>
              </section>
            </>
          ) : null}
          <section>
            <h2>关联资料</h2>
            <div className="plan-source-list">
              {plan.packages.map((item) => (
                <Link key={item.packageId} to={`/packages/${item.packageId}`}>
                  <strong>{item.title}</strong>
                  <span>{item.keywords.slice(0, 3).join(' / ') || item.status}</span>
                </Link>
              ))}
            </div>
          </section>
          <section>
            <h2>AI 重排</h2>
            <Input.TextArea
              placeholder="例如：本周只有 2 小时，优先复习已学过的概念"
              value={replanFeedback}
              onChange={(event) => setReplanFeedback(event.target.value)}
              rows={3}
            />
            <Button
              block
              icon={replan.isPending ? <ReloadOutlined spin /> : <ThunderboltOutlined />}
              loading={replan.isPending}
              onClick={() => replan.mutate(replanFeedback)}
              type="primary"
            >
              生成重排建议
            </Button>
            {proposal ? (
              <div className="plan-replan-proposal">
                <strong>{proposal.summary}</strong>
                <span>{proposal.steps.length} 个步骤将被保存为新版本</span>
                <Button
                  block
                  loading={applyReplan.isPending}
                  onClick={() => applyReplan.mutate(proposal.proposalId)}
                >
                  应用建议
                </Button>
              </div>
            ) : null}
          </section>
        </aside>
      </section>
    </div>
  )
}
