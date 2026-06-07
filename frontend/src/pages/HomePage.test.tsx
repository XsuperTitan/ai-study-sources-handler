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
    deletePackage: vi.fn(),
    packages: vi.fn(),
  },
}))

const packages: PackageSummary[] = [
  {
    id: 'ready-package',
    title: '可删除资料',
    packageType: 'MIXED',
    status: 'READY',
    currentStage: 'ILLUSTRATION',
    progress: 100,
    warnings: [],
    createdAt: '2026-06-07T00:00:00Z',
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
    vi.mocked(api.deletePackage).mockResolvedValue(undefined)
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

    const readyDelete = await screen.findByLabelText('删除 可删除资料')
    const processingDelete = await screen.findByLabelText('删除 处理中资料')
    expect(readyDelete).toBeEnabled()
    expect(processingDelete).toBeDisabled()

    fireEvent.click(readyDelete)

    await waitFor(() => expect(api.deletePackage).toHaveBeenCalled())
    expect(vi.mocked(api.deletePackage).mock.calls[0][0]).toBe('ready-package')
  })
})
