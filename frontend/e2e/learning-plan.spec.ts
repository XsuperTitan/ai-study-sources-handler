import { expect, test } from '@playwright/test'

test('drags a package into the learning plan and completes a generated step', async ({ page }) => {
  const packageId = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
  const stepId = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'
  let plan = {
    title: '',
    overview: '',
    estimatedMinutes: 0,
    progress: 0,
    weeklySummary: '',
    todaySteps: [] as Array<unknown>,
    version: 0,
    packages: [] as Array<{
      packageId: string
      title: string
      keywords: string[]
      status: string
      position: number
    }>,
    steps: [] as Array<{
      stepId: string
      title: string
      description: string
      packageIds: string[]
      estimatedMinutes: number
      scheduledDate?: string
      actualMinutes: number
      stageLabel: string
      reflection: string
      status: string
      position: number
    }>,
  }

  await page.route('**/api/v1/capabilities', (route) => route.fulfill({ json: {} }))
  await page.route('**/api/v1/learning/overview', (route) =>
    route.fulfill({
      json: {
        masteredTotal: 0,
        deletedMasteredTotal: 0,
        masteredThisWeek: 0,
        currentStreakDays: 0,
        trend: [],
        recentKeywords: [],
        recentMastered: [],
        deletedMastered: [],
      },
    }),
  )
  await page.route('**/api/v1/learning/plan', (route) => route.fulfill({ json: plan }))
  await page.route('**/api/v1/learning/plan/packages', async (route) => {
    const body = await route.request().postDataJSON()
    plan = {
      ...plan,
      packages: body.packageIds.map((id: string, position: number) => ({
        packageId: id,
        title: 'Plan Source',
        keywords: ['Thread Pool'],
        status: 'READY',
        position,
      })),
    }
    await route.fulfill({ json: plan })
  })
  await page.route('**/api/v1/learning/plan/generate', (route) => {
    plan = {
      ...plan,
      title: 'Generated Plan',
      overview: 'Read the source and explain the core idea.',
      estimatedMinutes: 25,
      progress: 0,
      steps: [{
        stepId,
        title: 'Read foundations',
        description: 'Read and summarize.',
        packageIds: [packageId],
        estimatedMinutes: 25,
        scheduledDate: '2026-06-18',
        actualMinutes: 0,
        stageLabel: 'Read',
        reflection: '',
        status: 'IN_PROGRESS',
        position: 0,
      }],
    }
    return route.fulfill({ json: plan })
  })
  await page.route(`**/api/v1/learning/plan/steps/${stepId}`, (route) => {
    plan = {
      ...plan,
      progress: 100,
      steps: plan.steps.map((step) => ({ ...step, status: 'DONE', completedAt: '2026-06-09T00:00:00Z' })),
    }
    return route.fulfill({ json: plan })
  })
  await page.route('**/api/v1/packages?**', (route) =>
    route.fulfill({
      json: [{
        id: packageId,
        title: 'Plan Source',
        packageType: 'MIXED',
        status: 'READY',
        currentStage: 'ILLUSTRATION',
        progress: 100,
        warnings: [],
        createdAt: '2026-06-09T00:00:00Z',
        cover: { keywords: ['Thread Pool'] },
        mastery: { packageId, mastered: false },
      }],
    }),
  )

  await page.goto('/')
  await expect(page.locator('.package-card')).toBeVisible()
  await page.locator('.package-card').evaluate((card) => {
    const dataTransfer = new DataTransfer()
    card.dispatchEvent(new DragEvent('dragstart', {
      bubbles: true,
      cancelable: true,
      dataTransfer,
    }))
    const dropZone = document.querySelector('.floating-plan-drop')
    dropZone?.dispatchEvent(new DragEvent('dragenter', {
      bubbles: true,
      cancelable: true,
      dataTransfer,
    }))
    dropZone?.dispatchEvent(new DragEvent('drop', {
      bubbles: true,
      cancelable: true,
      dataTransfer,
    }))
  })
  await expect(page.locator('.plan-package-pill', { hasText: 'Plan Source' })).toBeVisible()

  await page.getByRole('button', { name: '生成学习计划' }).click()
  await expect(page.getByText('Generated Plan')).toBeVisible()
  await expect(page.getByRole('button', { name: '进行中 Read foundations' })).toBeVisible()

  await page.getByRole('button', { name: '进行中 Read foundations' }).click()
  await expect(page.getByText('100%')).toBeVisible()
  await expect(page.getByRole('button', { name: '已完成 Read foundations' })).toBeVisible()
})
