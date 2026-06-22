import { expect, test } from '@playwright/test'

test('creates a pasted-text package and opens its progress page', async ({ page }) => {
  await page.route('**/api/v1/capabilities', (route) =>
    route.fulfill({ json: { deepseek: { available: true }, qwenVl: { available: true } } }),
  )
  await page.route('**/api/v1/packages?**', (route) => route.fulfill({ json: [] }))
  await page.route('**/api/v1/packages', async (route) => {
    if (route.request().method() === 'POST') {
      await route.fulfill({
        status: 202,
        json: { packageId: '11111111-1111-1111-1111-111111111111', rootJobId: 'job-id' },
      })
    } else {
      await route.fallback()
    }
  })
  await page.route('**/api/v1/packages/11111111-1111-1111-1111-111111111111', (route) =>
    route.fulfill({
      json: {
        id: '11111111-1111-1111-1111-111111111111',
        title: 'Java Thread Pool',
        packageType: 'MIXED',
        status: 'READY',
        currentStage: 'ILLUSTRATION',
        progress: 100,
        warnings: [],
        createdAt: new Date().toISOString(),
        outputs: {
          noteReady: true,
          reportReady: true,
          diagramReady: true,
          diagramTitle: '线程池知识流程',
          diagramUrl: '/api/v1/packages/11111111-1111-1111-1111-111111111111/diagram',
          abstractIllustrationReady: true,
          abstractIllustrationAssetId: '22222222-2222-2222-2222-222222222222',
          abstractIllustrationAssetUrl:
            '/api/v1/packages/11111111-1111-1111-1111-111111111111/assets/22222222-2222-2222-2222-222222222222',
          illustrationReady: true,
          illustrationAssetId: '22222222-2222-2222-2222-222222222222',
          illustrationAssetUrl:
            '/api/v1/packages/11111111-1111-1111-1111-111111111111/assets/22222222-2222-2222-2222-222222222222',
        },
      },
    }),
  )
  await page.route('**/api/v1/packages/11111111-1111-1111-1111-111111111111/jobs', (route) =>
    route.fulfill({ json: [] }),
  )
  await page.route('**/api/v1/packages/11111111-1111-1111-1111-111111111111/sources', (route) =>
    route.fulfill({
      json: {
        items: [
          {
            id: 'source-1',
            kind: 'PASTED_TEXT',
            originalName: '粘贴文本',
            sequence: 0,
            assetId: '33333333-3333-3333-3333-333333333333',
            assetUrl:
              '/api/v1/packages/11111111-1111-1111-1111-111111111111/assets/33333333-3333-3333-3333-333333333333',
            contentType: 'text/plain',
            size: 35,
            metadata: {},
          },
        ],
        assets: [
          {
            id: '33333333-3333-3333-3333-333333333333',
            originalName: 'pasted-text.txt',
            contentType: 'text/plain',
            size: 35,
            assetUrl:
              '/api/v1/packages/11111111-1111-1111-1111-111111111111/assets/33333333-3333-3333-3333-333333333333',
          },
          {
            id: '22222222-2222-2222-2222-222222222222',
            originalName: 'illustration.png',
            contentType: 'image/png',
            size: 2048,
            assetUrl:
              '/api/v1/packages/11111111-1111-1111-1111-111111111111/assets/22222222-2222-2222-2222-222222222222',
          },
        ],
      },
    }),
  )
  await page.route('**/api/v1/packages/11111111-1111-1111-1111-111111111111/note', (route) =>
    route.fulfill({
      contentType: 'text/markdown;charset=UTF-8',
      body: '# Java Thread Pool\n\nThread pools reuse worker threads [[cite:blk_1234567890abcdef1234567890abcdef]]',
    }),
  )
  await page.route('**/api/v1/packages/11111111-1111-1111-1111-111111111111/diagram', (route) =>
    route.fulfill({
      contentType: 'text/plain;charset=UTF-8',
      body: 'flowchart TB\nA[资料输入] --> B[任务拆解]\nB --> C[队列缓冲]\nC --> D[线程复用]\nD --> E[复习应用]',
    }),
  )
  await page.route('**/api/v1/packages/11111111-1111-1111-1111-111111111111/report', (route) =>
    route.fulfill({
      json: {
        overview: 'A compact guide for understanding thread pool reuse.',
        targetAudience: [],
        difficulty: 'INTERMEDIATE',
        estimatedMinutes: 30,
        prerequisites: [],
        learningObjectives: ['Understand thread reuse'],
        recommendedSequence: ['Task submission', 'Queue buffering', 'Worker reuse'],
        coreKnowledgePoints: ['Worker reuse', 'Task queue', 'Rejection policy'],
        keyPoints: ['Thread pools reuse worker threads', 'Queues buffer tasks'],
        difficultPoints: ['Parameter tradeoffs'],
        commonMistakes: ['Ignoring queue capacity'],
        interviewFocus: [],
        exercises: ['Explain why reuse matters'],
        reviewSchedule: [{ afterDays: 1, focus: 'Core parameters' }],
        completenessWarnings: [],
        aiRiskWarnings: [],
      },
    }),
  )
  await page.route(
    '**/api/v1/packages/11111111-1111-1111-1111-111111111111/citations/blk_1234567890abcdef1234567890abcdef',
    (route) =>
      route.fulfill({
        json: {
          blockId: 'blk_1234567890abcdef1234567890abcdef',
          sourceKind: 'TEXT_PARAGRAPH',
          displayName: '文本第 1 段',
          paragraphNumber: 1,
          excerpt: 'Thread pools reuse worker threads.',
        },
      }),
  )
  await page.route(
    '**/api/v1/packages/11111111-1111-1111-1111-111111111111/assets/22222222-2222-2222-2222-222222222222',
    (route) =>
      route.fulfill({
        contentType: 'image/png',
        body: Buffer.from(
          'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAFgwJ/lxV2NwAAAABJRU5ErkJggg==',
          'base64',
        ),
      }),
  )

  await page.goto('/packages/new')
  await page.getByLabel('资料标题').fill('Java Thread Pool')
  await page.getByLabel('粘贴文本').fill('Thread pools reuse worker threads.')
  await page.getByRole('button', { name: '开始处理' }).click()

  await expect(page).toHaveURL(/packages\/11111111/)
  await expect(page.getByText('Java Thread Pool')).toBeVisible()
  await expect(page.getByText('100%')).toBeVisible()
  await expect(page.getByRole('tab', { name: /抽象记忆图/ })).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByRole('region', { name: 'AI 一图总结' })).toBeVisible()
  await expect(page.getByText('A compact guide for understanding thread pool reuse.')).toBeVisible()
  await expect(page.getByText('Task submission')).toBeVisible()
  await expect(page.getByText('Rejection policy')).toBeVisible()
  await expect(page.getByRole('link', { name: '下载 Markdown' })).toHaveAttribute(
    'href',
    '/api/v1/packages/11111111-1111-1111-1111-111111111111/note.md',
  )
  await page.getByRole('tab', { name: /知识流程图/ }).click()
  await expect(page.getByRole('tab', { name: /知识流程图/ })).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByText('资料输入')).toBeVisible()
  await page.getByRole('button', { name: '来源' }).click()
  await expect(page.getByText('Thread pools reuse worker threads.')).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByText('Thread pools reuse worker threads.')).toBeHidden()
  await page.getByRole('tab', { name: '学习指南' }).click()
  await expect(
    page.getByLabel('学习指南').getByText('A compact guide for understanding thread pool reuse.'),
  ).toBeVisible()
  await expect(page.getByRole('link', { name: '下载 Markdown' })).toHaveAttribute(
    'href',
    '/api/v1/packages/11111111-1111-1111-1111-111111111111/report.md',
  )
  await page.getByRole('tab', { name: '原始资料' }).click()
  await expect(page.getByText('粘贴文本')).toBeVisible()
})
