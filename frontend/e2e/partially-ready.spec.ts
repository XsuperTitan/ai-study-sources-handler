import { expect, test } from '@playwright/test'

test('keeps note and guide available when illustration fails', async ({ page }) => {
  const packageId = '44444444-4444-4444-4444-444444444444'
  await page.route('**/api/v1/packages/44444444-4444-4444-4444-444444444444', (route) =>
    route.fulfill({
      json: {
        id: packageId,
        title: 'Partially Ready Package',
        packageType: 'MIXED',
        status: 'PARTIALLY_READY',
        currentStage: 'ILLUSTRATION',
        progress: 100,
        warnings: ['万相未配置或未返回图片。'],
        createdAt: new Date().toISOString(),
        outputs: {
          noteReady: true,
          reportReady: true,
          diagramReady: true,
          diagramTitle: '可用知识流程',
          diagramUrl: `/api/v1/packages/${packageId}/diagram`,
          illustrationReady: false,
        },
      },
    }),
  )
  await page.route('**/api/v1/packages/44444444-4444-4444-4444-444444444444/jobs', (route) =>
    route.fulfill({ json: [] }),
  )
  await page.route('**/api/v1/packages/44444444-4444-4444-4444-444444444444/sources', (route) =>
    route.fulfill({ json: { items: [], assets: [] } }),
  )
  await page.route('**/api/v1/packages/44444444-4444-4444-4444-444444444444/note', (route) =>
    route.fulfill({ contentType: 'text/markdown;charset=UTF-8', body: '# Still readable' }),
  )
  await page.route('**/api/v1/packages/44444444-4444-4444-4444-444444444444/diagram', (route) =>
    route.fulfill({
      contentType: 'text/plain;charset=UTF-8',
      body: 'flowchart TB\nA[资料输入] --> B[概念整理]\nB --> C[重点识别]\nC --> D[练习巩固]\nD --> E[复盘应用]',
    }),
  )
  await page.route('**/api/v1/packages/44444444-4444-4444-4444-444444444444/report', (route) =>
    route.fulfill({
      json: {
        overview: 'Guide still readable.',
        targetAudience: [],
        difficulty: 'INTERMEDIATE',
        estimatedMinutes: 30,
        prerequisites: [],
        learningObjectives: [],
        recommendedSequence: [],
        coreKnowledgePoints: [],
        keyPoints: [],
        difficultPoints: [],
        commonMistakes: [],
        interviewFocus: [],
        exercises: [],
        reviewSchedule: [],
        completenessWarnings: [],
        aiRiskWarnings: [],
      },
    }),
  )

  await page.goto(`/packages/${packageId}`)

  await expect(page.getByText('万相未配置或未返回图片。')).toBeVisible()
  await expect(page.getByRole('region', { name: 'AI 一图总结' })).toBeVisible()
  await expect(page.getByText('Guide still readable.')).toBeVisible()
  await page.getByRole('tab', { name: /知识流程图/ }).click()
  await expect(page.getByText('资料输入')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Still readable' })).toBeVisible()
  await page.getByRole('tab', { name: '学习指南' }).click()
  await expect(page.getByLabel('学习指南').getByText('Guide still readable.')).toBeVisible()
})
