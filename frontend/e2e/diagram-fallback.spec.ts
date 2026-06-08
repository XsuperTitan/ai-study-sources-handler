import { expect, test } from '@playwright/test'

test('keeps the note available when the knowledge flowchart is unavailable', async ({ page }) => {
  const packageId = '55555555-5555-5555-5555-555555555555'
  const illustrationId = '66666666-6666-6666-6666-666666666666'
  await page.route(`**/api/v1/packages/${packageId}`, (route) =>
    route.fulfill({
      json: {
        id: packageId,
        title: 'Illustration Only Package',
        packageType: 'MIXED',
        status: 'READY',
        currentStage: 'ILLUSTRATION',
        progress: 100,
        warnings: [],
        createdAt: new Date().toISOString(),
        outputs: {
          noteReady: true,
          reportReady: false,
          diagramReady: false,
          illustrationReady: true,
          illustrationAssetId: illustrationId,
          illustrationAssetUrl: `/api/v1/packages/${packageId}/assets/${illustrationId}`,
        },
      },
    }),
  )
  await page.route(`**/api/v1/packages/${packageId}/jobs`, (route) => route.fulfill({ json: [] }))
  await page.route(`**/api/v1/packages/${packageId}/sources`, (route) =>
    route.fulfill({ json: { items: [], assets: [] } }),
  )
  await page.route(`**/api/v1/packages/${packageId}/note`, (route) =>
    route.fulfill({ contentType: 'text/markdown;charset=UTF-8', body: '# Theme image remains available' }),
  )
  await page.route(`**/api/v1/packages/${packageId}/assets/${illustrationId}`, (route) =>
    route.fulfill({
      contentType: 'image/png',
      body: Buffer.from(
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAFgwJ/lxV2NwAAAABJRU5ErkJggg==',
        'base64',
      ),
    }),
  )

  await page.goto(`/packages/${packageId}`)

  await expect(page.getByRole('tab', { name: /知识流程图/ })).toHaveCount(0)
  await expect(page.getByRole('tab', { name: /AI 主题图/ })).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByRole('img', { name: 'AI 主题图' })).toBeVisible()
  await expect(page.getByText('Theme image remains available')).toBeVisible()
  await expect(page.getByRole('link', { name: '下载 Markdown' })).toHaveAttribute(
    'href',
    `/api/v1/packages/${packageId}/note.md`,
  )
})
