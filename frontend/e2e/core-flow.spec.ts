import { expect, test } from '@playwright/test'

test('creates a pasted-text package and opens its progress page', async ({ page }) => {
  await page.route('**/api/v1/capabilities', (route) =>
    route.fulfill({ json: { deepseek: { available: true }, qwenVl: { available: true } } }),
  )
  await page.route('**/api/v1/packages?limit=50', (route) => route.fulfill({ json: [] }))
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
        status: 'PROCESSING',
        currentStage: 'DIGEST',
        progress: 58,
        warnings: [],
        createdAt: new Date().toISOString(),
        outputs: { noteReady: false, reportReady: false, illustrationReady: false },
      },
    }),
  )
  await page.route('**/api/v1/packages/11111111-1111-1111-1111-111111111111/jobs', (route) =>
    route.fulfill({ json: [] }),
  )

  await page.goto('/packages/new')
  await page.getByLabel('资料标题').fill('Java Thread Pool')
  await page.getByLabel('粘贴文本').fill('Thread pools reuse worker threads.')
  await page.getByRole('button', { name: '开始处理' }).click()

  await expect(page).toHaveURL(/packages\/11111111/)
  await expect(page.getByText('Java Thread Pool')).toBeVisible()
  await expect(page.getByText('58%')).toBeVisible()
})

