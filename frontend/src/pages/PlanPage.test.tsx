import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { message, Modal } from 'antd'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api'
import type { LearningPlan } from '../types'
import PlanPage from './PlanPage'

vi.mock('../api', () => ({
  api: {
    applyLearningPlanReplan: vi.fn(),
    learningPlan: vi.fn(),
    recordLearningPlanSession: vi.fn(),
    replanLearningPlan: vi.fn(),
    resetLearningPlan: vi.fn(),
    updateLearningPlanSchedule: vi.fn(),
    updateLearningPlanStep: vi.fn(),
  },
}))

const basePlan: LearningPlan = {
  title: 'Java plan',
  overview: 'Read, practice, review.',
  estimatedMinutes: 60,
  progress: 0,
  weeklySummary: '本周 2 个任务，预计 60 分钟。',
  todaySteps: [],
  version: 1,
  generatedAt: '2026-06-18T00:00:00Z',
  updatedAt: '2026-06-18T00:00:00Z',
  packages: [{
    packageId: 'ready-package',
    title: 'Ready Notes',
    keywords: ['Thread Pool'],
    status: 'READY',
    position: 0,
  }],
  steps: [{
    stepId: 'step-1',
    title: 'Read foundations',
    description: 'Read the source package.',
    packageIds: ['ready-package'],
    estimatedMinutes: 30,
    scheduledDate: new Date().toISOString().slice(0, 10),
    actualMinutes: 0,
    stageLabel: '理解',
    reflection: '',
    status: 'IN_PROGRESS',
    position: 0,
  }],
}

function renderPlan() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <PlanPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('PlanPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.learningPlan).mockResolvedValue(basePlan)
    vi.mocked(api.recordLearningPlanSession).mockResolvedValue({
      ...basePlan,
      steps: [{ ...basePlan.steps[0], actualMinutes: 25 }],
    })
    vi.mocked(api.updateLearningPlanSchedule).mockResolvedValue(basePlan)
    vi.mocked(api.updateLearningPlanStep).mockResolvedValue({
      ...basePlan,
      progress: 100,
      steps: [{ ...basePlan.steps[0], status: 'DONE', completedAt: '2026-06-18T01:00:00Z' }],
    })
    vi.mocked(api.replanLearningPlan).mockResolvedValue({
      proposalId: 'proposal-1',
      summary: 'Move practice later',
      steps: [{ ...basePlan.steps[0], title: 'Practice later' }],
      createdAt: '2026-06-18T01:00:00Z',
    })
    vi.mocked(api.applyLearningPlanReplan).mockResolvedValue({
      ...basePlan,
      version: 2,
      steps: [{ ...basePlan.steps[0], title: 'Practice later' }],
    })
    vi.mocked(api.resetLearningPlan).mockResolvedValue({
      title: '',
      overview: '',
      estimatedMinutes: 0,
      progress: 0,
      weeklySummary: '',
      todaySteps: [],
      packages: [],
      steps: [],
      version: 0,
    })
    vi.spyOn(message, 'success').mockImplementation(() => undefined as never)
    vi.spyOn(message, 'error').mockImplementation(() => undefined as never)
    vi.spyOn(Modal, 'confirm').mockImplementation((config) => {
      void config.onOk?.(() => undefined)
      return {
        destroy: vi.fn(),
        update: vi.fn(),
      }
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('records a study session without completing the step', async () => {
    renderPlan()

    await screen.findByText('Java plan')
    fireEvent.click(screen.getByRole('button', { name: /保存学习记录/ }))

    await waitFor(() => expect(vi.mocked(api.recordLearningPlanSession).mock.calls[0][0]).toEqual({
      stepId: 'step-1',
      minutes: 25,
      note: '',
    }))
    expect(await screen.findByText('25/30 min')).toBeInTheDocument()
  })

  it('previews and applies a replan proposal', async () => {
    renderPlan()

    await screen.findByText('Java plan')
    fireEvent.change(screen.getByPlaceholderText('例如：本周只有 2 小时，优先复习已学过的概念'), {
      target: { value: 'busy' },
    })
    fireEvent.click(screen.getByRole('button', { name: /生成重排建议/ }))

    await screen.findByText('Move practice later')
    fireEvent.click(screen.getByRole('button', { name: '应用建议' }))

    await waitFor(() => expect(vi.mocked(api.applyLearningPlanReplan).mock.calls[0][0]).toBe('proposal-1'))
    expect((await screen.findAllByText('Practice later')).length).toBeGreaterThan(0)
  })

  it('resets the active plan after confirmation', async () => {
    renderPlan()

    await screen.findByText('Java plan')
    fireEvent.click(screen.getByRole('button', { name: /重置学习计划/ }))

    await waitFor(() => expect(api.resetLearningPlan).toHaveBeenCalled())
    expect(vi.mocked(Modal.confirm).mock.calls[0][0]).toMatchObject({
      title: '重置学习计划？',
      okText: '重置',
      okButtonProps: { danger: true },
    })
  })
})
