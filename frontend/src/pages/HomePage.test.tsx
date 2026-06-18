import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { message, Modal } from 'antd'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api'
import type { LearningOverview, LearningPlan, PackageSummary } from '../types'
import HomePage from './HomePage'

vi.mock('../api', () => ({
  api: {
    capabilities: vi.fn(),
    deletePackage: vi.fn(),
    generatePackageIllustration: vi.fn(),
    generateLearningPlan: vi.fn(),
    learningOverview: vi.fn(),
    learningPlan: vi.fn(),
    packages: vi.fn(),
    resetLearningPlan: vi.fn(),
    saveLearningPlanPackages: vi.fn(),
    setMastery: vi.fn(),
    updateLearningPlanStep: vi.fn(),
  },
}))

const basePackages: PackageSummary[] = [
  {
    id: 'ready-package',
    title: 'Ready Notes',
    packageType: 'MIXED',
    status: 'READY',
    currentStage: 'ILLUSTRATION',
    progress: 100,
    warnings: [],
    createdAt: '2026-06-07T00:00:00Z',
    cover: {
      imageUrl: '/api/v1/packages/ready-package/assets/cover',
      keywords: ['Thread Pool', 'Queue'],
      visualVariants: {
        classic: {
          imageUrl: '/api/v1/packages/ready-package/assets/classic',
          ready: true,
          generating: false,
        },
        whiteboard: {
          imageUrl: '/api/v1/packages/ready-package/assets/whiteboard',
          ready: true,
          generating: false,
        },
      },
    },
    mastery: {
      packageId: 'ready-package',
      mastered: false,
    },
  },
  {
    id: 'processing-package',
    title: 'Processing Notes',
    packageType: 'MIXED',
    status: 'PROCESSING',
    currentStage: 'DIGEST',
    progress: 50,
    warnings: [],
    createdAt: '2026-06-07T00:01:00Z',
    cover: {
      keywords: ['Parsing'],
      visualVariants: {
        classic: {
          ready: false,
          generating: false,
        },
        whiteboard: {
          ready: false,
          generating: false,
        },
      },
    },
  },
]

const emptyOverview: LearningOverview = {
  masteredTotal: 0,
  deletedMasteredTotal: 0,
  masteredThisWeek: 0,
  currentStreakDays: 0,
  trend: [],
  recentKeywords: [],
  recentMastered: [],
  deletedMastered: [],
}

const emptyPlan: LearningPlan = {
  title: '',
  overview: '',
  estimatedMinutes: 0,
  progress: 0,
  weeklySummary: '',
  todaySteps: [],
  packages: [],
  steps: [],
  version: 0,
}

const selectedPlan: LearningPlan = {
  ...emptyPlan,
  packages: [{
    packageId: 'ready-package',
    title: 'Ready Notes',
    keywords: ['Thread Pool'],
    status: 'READY',
    position: 0,
  }],
}

function generatedPlan(stepDone = false): LearningPlan {
  return {
    title: 'Java plan',
    overview: 'Read, practice, then review.',
    estimatedMinutes: 45,
    progress: stepDone ? 100 : 0,
    weeklySummary: 'This week has 1 task.',
    todaySteps: [],
    version: 1,
    generatedAt: '2026-06-09T00:00:00Z',
    packages: selectedPlan.packages,
    steps: [{
      stepId: 'step-1',
      title: 'Read foundations',
      description: 'Read the source package.',
      packageIds: ['ready-package'],
      estimatedMinutes: 45,
      scheduledDate: '2026-06-09',
      actualMinutes: 0,
      stageLabel: 'Read',
      reflection: '',
      status: stepDone ? 'DONE' : 'IN_PROGRESS',
      position: 0,
      completedAt: stepDone ? '2026-06-09T01:00:00Z' : undefined,
    }],
  }
}

function renderHome() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <HomePage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('HomePage learning plan', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.localStorage.clear()
    vi.mocked(api.capabilities).mockResolvedValue({})
    vi.mocked(api.deletePackage).mockResolvedValue(undefined)
    vi.mocked(api.generatePackageIllustration).mockResolvedValue({ packageId: 'ready-package', rootJobId: 'job-1' })
    vi.mocked(api.generateLearningPlan).mockResolvedValue(generatedPlan())
    vi.mocked(api.learningOverview).mockResolvedValue(emptyOverview)
    vi.mocked(api.learningPlan).mockResolvedValue(emptyPlan)
    vi.mocked(api.packages).mockResolvedValue(basePackages)
    vi.mocked(api.resetLearningPlan).mockResolvedValue(emptyPlan)
    vi.mocked(api.saveLearningPlanPackages).mockResolvedValue(selectedPlan)
    vi.mocked(api.setMastery).mockResolvedValue({
      packageId: 'ready-package',
      mastered: true,
      masteredAt: '2026-06-09T00:00:00Z',
      updatedAt: '2026-06-09T00:00:00Z',
    })
    vi.mocked(api.updateLearningPlanStep).mockResolvedValue(generatedPlan(true))
    vi.spyOn(message, 'success').mockImplementation(() => undefined as never)
    vi.spyOn(message, 'error').mockImplementation(() => undefined as never)
    vi.spyOn(message, 'info').mockImplementation(() => undefined as never)
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

  it('switches package covers between classic and whiteboard variants', async () => {
    const { container } = renderHome()

    await screen.findByRole('heading', { name: 'Ready Notes' })
    expect(container.querySelector('.package-cover img')?.getAttribute('src'))
      .toBe('/api/v1/packages/ready-package/assets/classic')

    fireEvent.click(screen.getByText('图二 白板信息图'))

    expect(window.localStorage.getItem('packageCoverVariant:v1')).toBe('whiteboard')
    expect(container.querySelector('.package-cover img')?.getAttribute('src'))
      .toBe('/api/v1/packages/ready-package/assets/whiteboard')
  })

  it('queues missing whiteboard illustration generation from the cover placeholder', async () => {
    window.localStorage.setItem('packageCoverVariant:v1', 'whiteboard')
    vi.mocked(api.packages).mockResolvedValue([{
      ...basePackages[0],
      cover: {
        ...basePackages[0].cover,
        keywords: basePackages[0].cover?.keywords ?? [],
        visualVariants: {
          classic: {
            imageUrl: '/api/v1/packages/ready-package/assets/classic',
            ready: true,
            generating: false,
          },
          whiteboard: {
            ready: false,
            generating: false,
          },
        },
      },
    }])
    renderHome()

    fireEvent.click(await screen.findByRole('button', { name: /生成图二/ }))

    await waitFor(() => expect(api.generatePackageIllustration).toHaveBeenCalledWith({
      id: 'ready-package',
      variant: 'whiteboard',
    }))
  })

  it('adds a package to the current plan from the card button', async () => {
    renderHome()

    fireEvent.click(await screen.findByLabelText('加入学习计划 Ready Notes'))

    await waitFor(() => expect(vi.mocked(api.saveLearningPlanPackages).mock.calls[0][0])
      .toEqual(['ready-package']))
    expect((await screen.findAllByText('Ready Notes')).length).toBeGreaterThan(0)
  })

  it('adds a package to the current plan by drag and drop', async () => {
    const { container } = renderHome()
    await screen.findByRole('heading', { name: 'Ready Notes' })
    const card = container.querySelector('.package-card')
    const dropZone = screen.getByText('拖入资料卡片做学习规划').closest('.floating-plan-drop')
    const transfer = new Map<string, string>()
    const dataTransfer = {
      effectAllowed: '',
      setData: vi.fn((key: string, value: string) => transfer.set(key, value)),
      getData: vi.fn((key: string) => transfer.get(key) ?? ''),
    }

    fireEvent.dragStart(card as Element, { dataTransfer })
    fireEvent.drop(dropZone as Element, { dataTransfer })

    await waitFor(() => expect(vi.mocked(api.saveLearningPlanPackages).mock.calls[0][0])
      .toEqual(['ready-package']))
  })

  it('generates a plan and keeps selected packages visible on failure', async () => {
    vi.mocked(api.learningPlan).mockResolvedValue(selectedPlan)
    vi.mocked(api.generateLearningPlan).mockRejectedValue(new Error('AI failed'))
    renderHome()

    await screen.findByText('1 份资料已加入')
    const generateButton = screen.getByRole('button', { name: /生成学习计划/ })
    expect(generateButton).toBeEnabled()
    fireEvent.click(generateButton)

    await waitFor(() => expect(api.generateLearningPlan).toHaveBeenCalled())
    expect(message.error).toHaveBeenCalledWith('AI failed')
    expect(screen.getAllByText('Ready Notes').length).toBeGreaterThan(0)
  })

  it('removes selected packages from the floating plan collector', async () => {
    vi.mocked(api.learningPlan).mockResolvedValue(selectedPlan)
    renderHome()

    fireEvent.click(await screen.findByLabelText('从学习计划移除 Ready Notes'))

    await waitFor(() => expect(vi.mocked(api.saveLearningPlanPackages).mock.calls[0][0])
      .toEqual([]))
  })

  it('marks a generated plan step complete and updates progress', async () => {
    vi.mocked(api.learningPlan).mockResolvedValue(generatedPlan())
    renderHome()

    fireEvent.click(await screen.findByRole('button', { name: '进行中 Read foundations' }))

    await waitFor(() => expect(vi.mocked(api.updateLearningPlanStep).mock.calls[0][0]).toEqual({
      stepId: 'step-1',
      completed: true,
    }))
    expect(await screen.findByText('100%')).toBeInTheDocument()
  })

  it('resets the current plan after confirmation', async () => {
    vi.mocked(api.learningPlan).mockResolvedValue(generatedPlan())
    renderHome()

    fireEvent.click(await screen.findByRole('button', { name: /重置学习计划/ }))

    await waitFor(() => expect(api.resetLearningPlan).toHaveBeenCalled())
    expect(vi.mocked(Modal.confirm).mock.calls.at(-1)?.[0]).toMatchObject({
      title: '重置学习计划？',
      okText: '重置',
      okButtonProps: { danger: true },
    })
  })

  it('deletes finished packages after confirmation', async () => {
    renderHome()

    fireEvent.click(await screen.findByLabelText('删除资料包 Ready Notes'))

    await waitFor(() => expect(api.deletePackage).toHaveBeenCalledWith('ready-package'))
    expect(vi.mocked(Modal.confirm).mock.calls[0][0]).toMatchObject({
      title: '删除资料包？',
      okText: '删除',
      okButtonProps: { danger: true },
    })
    expect(message.success).toHaveBeenCalledWith('资料包已删除')
  })
})
