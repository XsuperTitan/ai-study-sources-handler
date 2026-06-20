import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { createElement } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api'
import type { PackageSummary } from '../types'
import PackageDetailPage from './PackageDetailPage'

vi.mock('../api', () => ({
  api: {
    package: vi.fn(),
    jobs: vi.fn(),
    sources: vi.fn(),
    note: vi.fn(),
    diagram: vi.fn(),
    report: vi.fn(),
    retry: vi.fn(),
  },
}))

vi.mock('../components/MermaidDiagram', () => ({
  default: ({ chart }: { chart: string }) =>
    createElement('pre', { 'data-testid': 'mermaid-diagram' }, chart),
}))

const basePackage: PackageSummary = {
  id: '11111111-1111-1111-1111-111111111111',
  title: 'Java Thread Pool',
  packageType: 'MIXED',
  status: 'READY',
  currentStage: 'ILLUSTRATION',
  progress: 100,
  warnings: [],
  createdAt: '2026-06-07T00:00:00Z',
  outputs: {
    noteReady: true,
    reportReady: true,
    diagramReady: true,
    diagramTitle: '线程池知识流程',
    diagramUrl: '/api/v1/packages/11111111-1111-1111-1111-111111111111/diagram',
    illustrationReady: true,
    illustrationAssetId: '22222222-2222-2222-2222-222222222222',
    illustrationAssetUrl:
      '/api/v1/packages/11111111-1111-1111-1111-111111111111/assets/22222222-2222-2222-2222-222222222222',
  },
}

const baseGuide = {
  overview: '线程池通过复用工作线程降低创建成本，并用队列协调任务提交。',
  targetAudience: [],
  difficulty: '中级',
  estimatedMinutes: 30,
  prerequisites: [],
  learningObjectives: ['理解线程复用'],
  recommendedSequence: ['任务提交', '队列缓冲', '线程复用'],
  coreKnowledgePoints: ['工作线程复用', '任务队列', '拒绝策略'],
  keyPoints: ['线程池复用工作线程', '队列负责削峰'],
  difficultPoints: ['参数组合', '拒绝策略触发时机'],
  commonMistakes: ['忽略队列容量', '把核心线程数等同于最大线程数'],
  interviewFocus: [],
  exercises: ['解释线程池的价值', '画出任务提交到执行的路径'],
  reviewSchedule: [{ afterDays: 1, focus: '复习核心参数' }],
  completenessWarnings: [],
  aiRiskWarnings: [],
}

function renderDetail() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/packages/11111111-1111-1111-1111-111111111111']}>
        <Routes>
          <Route path="/packages/:packageId" element={<PackageDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('PackageDetailPage note visuals', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.package).mockResolvedValue(basePackage)
    vi.mocked(api.jobs).mockResolvedValue([])
    vi.mocked(api.sources).mockResolvedValue({ items: [], assets: [] })
    vi.mocked(api.note).mockResolvedValue('# Note')
    vi.mocked(api.diagram).mockResolvedValue('flowchart TB\nA[输入] --> B[复用]\nB --> C[队列]\nC --> D[执行]\nD --> E[复习]')
    vi.mocked(api.report).mockResolvedValue(baseGuide)
  })

  it('shows the theme summary graphic, keeps the knowledge flowchart, and downloads after the note', async () => {
    renderDetail()

    expect(await screen.findByRole('heading', { name: 'Note' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /AI 主题图/ })).toHaveAttribute('aria-selected', 'true')
    const summary = screen.getByRole('region', { name: 'AI 一图总结' })
    expect(summary).toHaveTextContent('Java Thread Pool')
    expect(summary).toHaveTextContent('线程池通过复用工作线程降低创建成本，并用队列协调任务提交。')
    expect(summary).toHaveTextContent('任务提交')
    expect(summary).toHaveTextContent('工作线程复用')
    expect(summary).toHaveTextContent('忽略队列容量')
    expect(summary).toHaveTextContent('参数组合')
    expect(summary).toHaveTextContent('画出任务提交到执行的路径')
    expect(screen.queryByRole('img', { name: 'AI 主题图' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '查看大图' }))
    expect(await screen.findByRole('dialog')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Java Thread Pool' }))
      .toHaveAttribute('src', basePackage.outputs?.illustrationAssetUrl)

    const noteHeading = screen.getByRole('heading', { name: 'Note' })
    const noteDownload = screen.getByRole('link', { name: /下载 Markdown/ })
    expect(noteDownload).toHaveAttribute(
      'href',
      '/api/v1/packages/11111111-1111-1111-1111-111111111111/note.md',
    )
    expect(noteHeading.compareDocumentPosition(noteDownload) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()

    fireEvent.click(screen.getByRole('tab', { name: /知识流程图/ }))
    expect(await screen.findByTestId('mermaid-diagram')).toHaveTextContent('flowchart TB')
    expect(screen.getByRole('tab', { name: /知识流程图/ })).toHaveAttribute('aria-selected', 'true')
  })

  it('shows the theme summary even when the generated image is missing', async () => {
    vi.mocked(api.package).mockResolvedValue({
      ...basePackage,
      outputs: {
        noteReady: true,
        reportReady: true,
        diagramReady: false,
        illustrationReady: false,
      },
    })

    renderDetail()

    const summary = await screen.findByRole('region', { name: 'AI 一图总结' })
    expect(summary).toHaveTextContent('线程池通过复用工作线程降低创建成本')
    expect(screen.queryByRole('img', { name: 'AI 主题图' })).not.toBeInTheDocument()
  })

  it('does not request the flowchart when no flowchart exists', async () => {
    vi.mocked(api.package).mockResolvedValue({
      ...basePackage,
      outputs: {
        noteReady: true,
        reportReady: false,
        illustrationReady: true,
        illustrationAssetId: basePackage.outputs?.illustrationAssetId,
        illustrationAssetUrl: basePackage.outputs?.illustrationAssetUrl,
      },
    })

    renderDetail()

    expect(await screen.findByText('Note')).toBeInTheDocument()
    await waitFor(() => expect(api.diagram).not.toHaveBeenCalled())
    expect(screen.queryByRole('tab', { name: /知识流程图/ })).not.toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /AI 主题图/ })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('img', { name: 'AI 主题图' })).toBeInTheDocument()
  })

  it('shows a lightweight pending state when neither guide nor image is ready', async () => {
    vi.mocked(api.package).mockResolvedValue({
      ...basePackage,
      outputs: {
        noteReady: true,
        reportReady: false,
        diagramReady: false,
        illustrationReady: false,
      },
    })

    renderDetail()

    expect(await screen.findByText('主题总结生成中')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /AI 主题图/ })).toHaveAttribute('aria-selected', 'true')
  })

  it('shows the guide markdown download on the guide tab', async () => {
    renderDetail()

    await screen.findByRole('heading', { name: 'Note' })
    fireEvent.click(await screen.findByRole('tab', { name: '学习指南' }))
    await waitFor(() =>
      expect(screen.getByRole('tab', { name: '学习指南' })).toHaveAttribute('aria-selected', 'true'),
    )

    const guideDownload = screen
      .getAllByRole('link', { name: /下载 Markdown/ })
      .find((link) => link.getAttribute('href')?.includes('/report.md'))
    expect(guideDownload).toHaveAttribute(
      'href',
      '/api/v1/packages/11111111-1111-1111-1111-111111111111/report.md',
    )
  })
})
