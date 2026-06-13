import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { Modal } from 'antd'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api'
import type { PackageSummary } from '../types'
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
    vi.mocked(api.capabilities).mockResolvedValue({})
    vi.mocked(api.learningOverview).mockResolvedValue({
      masteredTotal: 0,
      masteredThisWeek: 0,
      currentStreakDays: 0,
      trend: [],
      recentKeywords: [],
      recentMastered: [],
    })
    vi.mocked(api.deletePackage).mockResolvedValue(undefined)
    vi.mocked(api.setMastery).mockResolvedValue({
      packageId: 'ready-package',
      mastered: true,
      masteredAt: '2026-06-09T00:00:00Z',
      updatedAt: '2026-06-09T00:00:00Z',
    })
    vi.mocked(api.packages).mockResolvedValue(packages)
    vi.spyOn(Modal, 'confirm').mockImplementation((config) => {
      void config.onOk?.(() => undefined)
      return {
        destroy: vi.fn(),
        update: vi.fn(),
      }
    })
  })

  it('deletes finished packages after confirmation and disables active packages', async () => {
    renderHome()

    const readyDelete = await screen.findByLabelText('删除 Java 线程池执行机制')
    const processingDelete = await screen.findByLabelText('删除 处理中资料')
    expect(readyDelete).toBeEnabled()
    expect(processingDelete).toBeDisabled()

    fireEvent.click(readyDelete)

    await waitFor(() => expect(api.deletePackage).toHaveBeenCalled())
    expect(vi.mocked(api.deletePackage).mock.calls[0][0]).toBe('ready-package')
  })

  it('renders an AI image cover and a local topic fallback', async () => {
    const { container } = renderHome()

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
