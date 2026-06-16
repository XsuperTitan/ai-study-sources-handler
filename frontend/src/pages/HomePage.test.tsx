import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { message, Modal } from 'antd'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api'
import type { LearningOverview, PackageSummary } from '../types'
import HomePage from './HomePage'

vi.mock('../api', () => ({
  api: {
    capabilities: vi.fn(),
    learningOverview: vi.fn(),
    deletePackage: vi.fn(),
    packages: vi.fn(),
    setMastery: vi.fn(),
  },
}))

const packages: PackageSummary[] = [
  {
    id: 'ready-package',
    title: 'Java 线程池执行机制',
    packageType: 'MIXED',
    status: 'READY',
    currentStage: 'ILLUSTRATION',
    progress: 100,
    warnings: [],
    createdAt: '2026-06-07T00:00:00Z',
    cover: {
      imageUrl: '/api/v1/packages/ready-package/assets/cover',
      keywords: ['线程复用', '任务队列'],
    },
    mastery: {
      packageId: 'ready-package',
      mastered: false,
    },
  },
  {
    id: 'processing-package',
    title: '处理中资料',
    packageType: 'MIXED',
    status: 'PROCESSING',
    currentStage: 'DIGEST',
    progress: 50,
    warnings: [],
    createdAt: '2026-06-07T00:01:00Z',
    cover: {
      keywords: ['内容解析'],
    },
  },
]

const masteredPackage: PackageSummary = {
  ...packages[0],
  id: 'mastered-package',
  title: 'RAG 与向量检索',
  mastery: {
    packageId: 'mastered-package',
    mastered: true,
    masteredAt: '2026-06-10T00:00:00Z',
  },
}

const learningArchiveHiddenStorageKey = 'learningArchiveHiddenPackageIds:v1'

function deletedMasteredItem(index: number): LearningOverview['deletedMastered'][number] {
  return {
    packageId: `deleted-${index}`,
    title: `Archived Result ${index}`,
    keywords: [`Topic ${index}`, 'Mastery'],
    masteredAt: `2026-06-${String(9 + index).padStart(2, '0')}T00:00:00Z`,
    deletedAt: `2026-06-${String(12 + index).padStart(2, '0')}T00:00:00Z`,
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

describe('HomePage delete package', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.localStorage.clear()
    vi.mocked(api.capabilities).mockResolvedValue({})
    vi.mocked(api.learningOverview).mockResolvedValue({
      masteredTotal: 0,
      deletedMasteredTotal: 0,
      masteredThisWeek: 0,
      currentStreakDays: 0,
      trend: [],
      recentKeywords: [],
      recentMastered: [],
      deletedMastered: [],
    })
    vi.mocked(api.deletePackage).mockResolvedValue(undefined)
    vi.mocked(api.setMastery).mockResolvedValue({
      packageId: 'ready-package',
      mastered: true,
      masteredAt: '2026-06-09T00:00:00Z',
      updatedAt: '2026-06-09T00:00:00Z',
    })
    vi.mocked(api.packages).mockResolvedValue(packages)
    vi.spyOn(message, 'success').mockImplementation(() => undefined as never)
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

  it('deletes finished packages after confirmation and disables active packages', async () => {
    renderHome()

    const readyDelete = await screen.findByLabelText('删除资料包 Java 线程池执行机制')
    const processingDelete = await screen.findByLabelText('删除资料包 处理中资料')
    expect(readyDelete).toBeEnabled()
    expect(processingDelete).toBeDisabled()
    expect(readyDelete.querySelector('.anticon-delete')).toBeInTheDocument()

    fireEvent.click(readyDelete)

    await waitFor(() => expect(api.deletePackage).toHaveBeenCalled())
    expect(vi.mocked(api.deletePackage).mock.calls[0][0]).toBe('ready-package')
    expect(vi.mocked(Modal.confirm).mock.calls[0][0]).toMatchObject({
      title: '删除资料包？',
      content: '将永久删除“Java 线程池执行机制”及其本地相关文件，此操作不可恢复。',
      okText: '删除',
      okButtonProps: { danger: true },
    })
    expect(message.success).toHaveBeenCalledWith('资料包已删除')
    await waitFor(() => expect(api.learningOverview).toHaveBeenCalledTimes(2))
  })

  it('archives mastered learning results while deleting their source package', async () => {
    vi.mocked(api.packages).mockResolvedValue([masteredPackage])
    const { container } = renderHome()

    const archiveButton = await screen.findByLabelText('归档学习成果 RAG 与向量检索')
    expect(archiveButton).toBeEnabled()
    expect(container.querySelector('.card-archive .anticon-inbox')).toBeInTheDocument()
    expect(container.querySelector('.card-archive .anticon-delete')).not.toBeInTheDocument()

    fireEvent.click(archiveButton)

    await waitFor(() => expect(api.deletePackage).toHaveBeenCalledWith('mastered-package'))
    expect(vi.mocked(Modal.confirm).mock.calls[0][0]).toMatchObject({
      title: '归档学习成果？',
      content: '将永久删除“RAG 与向量检索”及其本地相关文件，无法恢复；学习成果会继续保留在学习概览中。',
      okText: '归档',
      okButtonProps: { danger: true },
    })
    expect(message.success).toHaveBeenCalledWith('学习成果已归档，源资料已删除')
  })

  it('renders deleted mastered items as a separate learning archive', async () => {
    vi.mocked(api.learningOverview).mockResolvedValue({
      masteredTotal: 7,
      deletedMasteredTotal: 1,
      masteredThisWeek: 2,
      currentStreakDays: 3,
      trend: [],
      recentKeywords: [],
      recentMastered: [],
      deletedMastered: [{
        packageId: 'deleted-package',
        title: 'Deleted RAG Notes',
        keywords: ['RAG', 'Embedding'],
        masteredAt: '2026-06-10T00:00:00Z',
        deletedAt: '2026-06-13T00:00:00Z',
      }],
    })

    renderHome()

    expect(await screen.findByText('熟练掌握的学习成果')).toBeInTheDocument()
    expect(screen.getByText('Deleted RAG Notes')).toBeInTheDocument()
    expect(screen.getByText('RAG / Embedding')).toBeInTheDocument()
    expect(screen.getByText('当前掌握')).toBeInTheDocument()
    expect(screen.getByText('已删除留档')).toBeInTheDocument()
  })

  it('keeps overflow archived learning results behind more and restores hidden items', async () => {
    const archiveItems = [deletedMasteredItem(1), deletedMasteredItem(2), deletedMasteredItem(3)]
    vi.mocked(api.learningOverview).mockResolvedValue({
      masteredTotal: 7,
      deletedMasteredTotal: 3,
      masteredThisWeek: 2,
      currentStreakDays: 3,
      trend: [],
      recentKeywords: [],
      recentMastered: [],
      deletedMastered: archiveItems,
    })

    const { container } = renderHome()

    await screen.findByText('Archived Result 1')
    expect(screen.getByText('Archived Result 2')).toBeInTheDocument()
    expect(screen.queryByText('Archived Result 3')).not.toBeInTheDocument()
    expect(screen.getByText('RECENT 3')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /more/i })).toBeInTheDocument()

    fireEvent.click(screen.getByLabelText('Hide learning result Archived Result 1'))

    await waitFor(() => {
      const mainTitles = Array.from(container.querySelectorAll('.learning-archive-item strong'))
        .map((item) => item.textContent)
      expect(mainTitles).toEqual(['Archived Result 2', 'Archived Result 3'])
    })
    expect(JSON.parse(window.localStorage.getItem(learningArchiveHiddenStorageKey) ?? '[]'))
      .toEqual(['deleted-1'])

    fireEvent.click(screen.getByRole('button', { name: /more/i }))
    const hiddenCheckbox = await screen.findByRole('checkbox', { name: /Archived Result 1/ })
    expect(hiddenCheckbox).not.toBeChecked()

    fireEvent.click(hiddenCheckbox)

    await waitFor(() => {
      const mainTitles = Array.from(container.querySelectorAll('.learning-archive-item strong'))
        .map((item) => item.textContent)
      expect(mainTitles).toEqual(['Archived Result 1', 'Archived Result 2'])
    })
    expect(JSON.parse(window.localStorage.getItem(learningArchiveHiddenStorageKey) ?? '[]'))
      .toEqual([])
  })

  it('restores hidden archive state from localStorage on render', async () => {
    window.localStorage.setItem(learningArchiveHiddenStorageKey, JSON.stringify(['deleted-1']))
    vi.mocked(api.learningOverview).mockResolvedValue({
      masteredTotal: 7,
      deletedMasteredTotal: 3,
      masteredThisWeek: 2,
      currentStreakDays: 3,
      trend: [],
      recentKeywords: [],
      recentMastered: [],
      deletedMastered: [deletedMasteredItem(1), deletedMasteredItem(2), deletedMasteredItem(3)],
    })

    const { container } = renderHome()

    await screen.findByText('Archived Result 2')
    const mainTitles = Array.from(container.querySelectorAll('.learning-archive-item strong'))
      .map((item) => item.textContent)
    expect(mainTitles).toEqual(['Archived Result 2', 'Archived Result 3'])
    expect(screen.queryByText('Archived Result 1')).not.toBeInTheDocument()
  })

  it('renders an AI image cover and a local topic fallback', async () => {
    const { container } = renderHome()

    expect(await screen.findByText('学习计划 / PLAN')).toBeInTheDocument()
    expect(screen.getByText('计划制定中')).toBeInTheDocument()
    expect(screen.getByText('后续会根据已掌握资料和学习目标追踪计划推进。')).toBeInTheDocument()
    await screen.findByRole('heading', { name: 'Java 线程池执行机制' })
    expect(container.querySelector('.package-cover img')).toHaveAttribute(
      'src',
      '/api/v1/packages/ready-package/assets/cover',
    )
    expect(screen.getByText('线程复用')).toBeInTheDocument()
    expect(screen.getByText('内容解析')).toBeInTheDocument()
    expect(screen.getByText('ANALYSING / TOPIC')).toBeInTheDocument()
  })

  it('optimistically archives a mastered package and disables active processing', async () => {
    let mastered = false
    vi.mocked(api.setMastery).mockImplementation(async ({ id, mastered: next }) => {
      mastered = next
      return {
        packageId: id,
        mastered: next,
        masteredAt: next ? '2026-06-09T00:00:00Z' : undefined,
        updatedAt: '2026-06-09T00:00:00Z',
      }
    })
    vi.mocked(api.packages).mockImplementation(async (filters) =>
      mastered && filters?.mastery === 'ACTIVE' ? packages.slice(1) : packages)
    renderHome()

    const masteryButton = await screen.findByLabelText(`标记已掌握 ${packages[0].title}`)
    const disabledButton = await screen.findByLabelText(`标记已掌握 ${packages[1].title}`)
    expect(masteryButton).toBeEnabled()
    expect(disabledButton).toBeDisabled()

    fireEvent.click(masteryButton)

    await waitFor(() => expect(vi.mocked(api.setMastery).mock.calls[0][0]).toEqual({
      id: 'ready-package',
      mastered: true,
    }))
    await waitFor(() => expect(screen.queryByRole('heading', {
      name: packages[0].title,
    })).not.toBeInTheDocument())
  })
})
