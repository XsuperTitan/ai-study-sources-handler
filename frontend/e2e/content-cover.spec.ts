import { expect, test } from '@playwright/test'

test('uses the generated title on the card and switches package detail memory images', async ({ page }) => {
  const packageId = '77777777-7777-7777-7777-777777777777'
  const abstractId = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
  const illustrationId = '88888888-8888-8888-8888-888888888888'
  const whiteboardId = '99999999-9999-9999-9999-999999999999'
  const title = 'Transformer 自注意力计算机制'
  const abstractUrl = `/api/v1/packages/${packageId}/assets/${abstractId}`
  const imageUrl = `/api/v1/packages/${packageId}/assets/${illustrationId}`
  const whiteboardUrl = `/api/v1/packages/${packageId}/assets/${whiteboardId}`

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
  await page.route('**/api/v1/learning/plan', (route) =>
    route.fulfill({ json: { title: '', overview: '', estimatedMinutes: 0, progress: 0, weeklySummary: '', todaySteps: [], packages: [], steps: [], version: 0 } }),
  )
  await page.route('**/api/v1/packages?**', (route) =>
    route.fulfill({
      json: [{
        id: packageId,
        title,
        packageType: 'MIXED',
        status: 'READY',
        currentStage: 'ILLUSTRATION',
        progress: 100,
        warnings: [],
        createdAt: '2026-06-09T00:00:00Z',
        cover: {
          imageUrl: abstractUrl,
          keywords: ['QKV 映射', '注意力权重'],
          visualVariants: {
            abstract: { imageUrl: abstractUrl, ready: true, generating: false },
            classic: { imageUrl, ready: true, generating: false },
            whiteboard: { imageUrl: whiteboardUrl, ready: true, generating: false },
          },
        },
      }],
    }),
  )
  await page.route(`**/api/v1/packages/${packageId}`, (route) =>
    route.fulfill({
      json: {
        id: packageId,
        title,
        packageType: 'MIXED',
        status: 'READY',
        currentStage: 'ILLUSTRATION',
        progress: 100,
        warnings: [],
        createdAt: '2026-06-09T00:00:00Z',
        outputs: {
          noteReady: true,
          reportReady: true,
          abstractIllustrationReady: true,
          abstractIllustrationAssetId: abstractId,
          abstractIllustrationAssetUrl: abstractUrl,
          illustrationReady: true,
          illustrationAssetId: illustrationId,
          illustrationAssetUrl: imageUrl,
          whiteboardIllustrationReady: true,
          whiteboardIllustrationAssetId: whiteboardId,
          whiteboardIllustrationAssetUrl: whiteboardUrl,
        },
      },
    }),
  )
  await page.route(`**/api/v1/packages/${packageId}/jobs`, (route) => route.fulfill({ json: [] }))
  await page.route(`**/api/v1/packages/${packageId}/sources`, (route) =>
    route.fulfill({ json: { items: [], assets: [] } }),
  )
  await page.route(`**/api/v1/packages/${packageId}/note`, (route) =>
    route.fulfill({ contentType: 'text/markdown;charset=UTF-8', body: `# ${title}` }),
  )
  await page.route(`**/api/v1/packages/${packageId}/report`, (route) =>
    route.fulfill({
      json: {
        overview: '理解自注意力如何根据输入动态计算上下文。',
        targetAudience: [],
        difficulty: '中级',
        estimatedMinutes: 30,
        prerequisites: [],
        learningObjectives: [],
        recommendedSequence: ['输入映射', '权重计算'],
        coreKnowledgePoints: ['QKV 映射'],
        keyPoints: ['缩放点积'],
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
  await page.route(`**${imageUrl}`, (route) =>
    route.fulfill({
      contentType: 'image/png',
      body: Buffer.from(
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAFgwJ/lxV2NwAAAABJRU5ErkJggg==',
        'base64',
      ),
    }),
  )
  await page.route(`**${abstractUrl}`, (route) =>
    route.fulfill({
      contentType: 'image/png',
      body: Buffer.from(
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAFgwJ/lxV2NwAAAABJRU5ErkJggg==',
        'base64',
      ),
    }),
  )
  await page.route(`**${whiteboardUrl}`, (route) =>
    route.fulfill({
      contentType: 'image/png',
      body: Buffer.from(
        'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAFgwJ/lxV2NwAAAABJRU5ErkJggg==',
        'base64',
      ),
    }),
  )

  await page.goto('/')
  await expect(page.getByRole('heading', { name: title })).toBeVisible()
  await expect(page.locator('.package-cover img')).toHaveAttribute('src', abstractUrl)
  await expect(page.locator('.package-cover-overlay')).toHaveCount(0)
  await expect(page.locator('.card-keywords')).toContainText('QKV 映射')

  await page.getByRole('heading', { name: title }).click()
  await expect(page).toHaveURL(`/packages/${packageId}`)
  await expect(page.locator('.detail-heading h1')).toHaveText(title)
  await expect(page.getByRole('tab', { name: /抽象记忆图/ })).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByRole('img', { name: 'AI 主题图' })).toHaveAttribute('src', abstractUrl)
  await page.getByRole('tab', { name: /图表记忆/ }).click()
  await expect(page.getByRole('img', { name: 'AI 主题图' })).toHaveAttribute('src', imageUrl)
  await page.getByRole('tab', { name: /白板记忆图/ }).click()
  await expect(page.getByRole('img', { name: 'AI 主题图' })).toHaveAttribute('src', whiteboardUrl)
  await expect(page.locator('.note-visuals .ant-tabs-tabpane-active').getByLabel('AI 主题图可读摘要')).toContainText(
    '理解自注意力如何根据输入动态计算上下文。',
  )
})
