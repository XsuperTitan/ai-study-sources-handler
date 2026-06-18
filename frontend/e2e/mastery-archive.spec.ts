import { expect, test } from '@playwright/test'

test('archives a mastered package and restores it from the mastered filter', async ({ page }) => {
  const packageId = '99999999-9999-9999-9999-999999999999'
  let mastered = false

  await page.route('**/api/v1/capabilities', (route) => route.fulfill({ json: {} }))
  await page.route('**/api/v1/learning/overview', (route) =>
    route.fulfill({
      json: {
        masteredTotal: mastered ? 1 : 0,
        deletedMasteredTotal: 0,
        masteredThisWeek: mastered ? 1 : 0,
        currentStreakDays: mastered ? 1 : 0,
        trend: [],
        recentKeywords: [],
        recentMastered: [],
        deletedMastered: [],
      },
    }),
  )
  await page.route('**/api/v1/learning/plan', (route) =>
    route.fulfill({ json: { title: '', overview: '', estimatedMinutes: 0, progress: 0, weeklySummary: '', todaySteps: [], packages: [], steps: [], version: 0 } }),
  )
  await page.route('**/api/v1/packages?**', (route) => {
    const filter = new URL(route.request().url()).searchParams.get('mastery') ?? 'ACTIVE'
    const visible = filter === 'ALL'
      || filter === 'MASTERED' && mastered
      || filter === 'ACTIVE' && !mastered
    return route.fulfill({
      json: visible ? [{
        id: packageId,
        title: 'Java 并发任务调度',
        packageType: 'MIXED',
        status: 'READY',
        currentStage: 'ILLUSTRATION',
        progress: 100,
        warnings: [],
        createdAt: '2026-06-09T00:00:00Z',
        cover: { keywords: ['线程池', '任务队列'] },
        mastery: { packageId, mastered },
      }] : [],
    })
  })
  await page.route(`**/api/v1/packages/${packageId}/mastery`, async (route) => {
    mastered = Boolean((await route.request().postDataJSON()).mastered)
    return route.fulfill({
      json: {
        packageId,
        mastered,
        masteredAt: mastered ? '2026-06-09T12:00:00Z' : null,
        updatedAt: '2026-06-09T12:00:00Z',
      },
    })
  })

  await page.goto('/')
  await page.getByRole('button', { name: /标记已掌握/ }).click()
  await expect(page.getByRole('heading', { name: 'Java 并发任务调度' })).toHaveCount(0)

  await page.getByLabel('学习状态').click()
  await page.getByText('已掌握', { exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Java 并发任务调度' })).toBeVisible()

  await page.getByRole('button', { name: /恢复学习/ }).click()
  await expect(page.getByRole('heading', { name: 'Java 并发任务调度' })).toHaveCount(0)
})
