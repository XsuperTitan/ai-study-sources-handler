import { expect, test } from '@playwright/test'

const longTitle =
  'Training_language_models_to_follow_instructions_with_human_feedback_and_extra_long_appendix_filename_that_should_not_expand_cards.pdf'

test('keeps recent package cards aligned when a title is very long', async ({ page }) => {
  await page.route('**/api/v1/capabilities', (route) =>
    route.fulfill({ json: { deepseek: { available: true }, qwenVl: { available: true } } }),
  )
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
      json: [
        {
          id: 'package-1',
          title: 'unsupervised-learning.png',
          packageType: 'MIXED',
          status: 'READY',
          currentStage: 'ILLUSTRATION',
          progress: 100,
          warnings: [],
          createdAt: '2026-06-07T00:00:00Z',
          cover: { keywords: ['无监督学习', '聚类'] },
        },
        {
          id: 'package-2',
          title: longTitle,
          packageType: 'MIXED',
          status: 'READY',
          currentStage: 'ILLUSTRATION',
          progress: 100,
          warnings: [],
          createdAt: '2026-06-07T00:01:00Z',
          cover: { keywords: ['强化学习'] },
        },
        {
          id: 'package-3',
          title: 'moudle.png',
          packageType: 'MIXED',
          status: 'READY',
          currentStage: 'ILLUSTRATION',
          progress: 100,
          warnings: [],
          createdAt: '2026-06-07T00:02:00Z',
          cover: { keywords: [] },
        },
        {
          id: 'package-4',
          title: 'function.png',
          packageType: 'MIXED',
          status: 'READY',
          currentStage: 'ILLUSTRATION',
          progress: 100,
          warnings: [],
          createdAt: '2026-06-07T00:03:00Z',
          cover: { keywords: [] },
        },
      ],
    }),
  )

  await page.goto('/')

  const cards = page.locator('.package-card')
  await expect(cards).toHaveCount(4)

  const widths = await cards.evaluateAll((elements) =>
    elements.map((element) => Math.round(element.getBoundingClientRect().width)),
  )
  expect(new Set(widths).size).toBe(1)

  const gridBox = await page.locator('.package-grid').boundingBox()
  const cardBoxes = await cards.evaluateAll((elements) =>
    elements.map((element) => {
      const box = element.getBoundingClientRect()
      return { left: box.left, right: box.right }
    }),
  )
  expect(gridBox).not.toBeNull()
  for (const box of cardBoxes) {
    expect(box.left).toBeGreaterThanOrEqual(gridBox!.x - 1)
    expect(box.right).toBeLessThanOrEqual(gridBox!.x + gridBox!.width + 1)
  }

  const longTitleNode = page.locator('.package-card h3', { hasText: longTitle })
  await expect(longTitleNode).toHaveAttribute('title', longTitle)
  await expect(page.locator('.package-cover-overlay')).toHaveCount(0)
  await expect(page.locator('.card-keywords').first()).toContainText('无监督学习')

  const firstCardCoverBox = await cards.first().locator('.package-cover').boundingBox()
  const firstCardActionsBox = await cards.first().locator('.card-actions').boundingBox()
  expect(firstCardCoverBox).not.toBeNull()
  expect(firstCardActionsBox).not.toBeNull()
  expect(firstCardActionsBox!.y).toBeGreaterThanOrEqual(firstCardCoverBox!.y)
  expect(firstCardActionsBox!.y).toBeLessThan(firstCardCoverBox!.y + firstCardCoverBox!.height)
  expect(firstCardActionsBox!.x).toBeGreaterThan(firstCardCoverBox!.x + firstCardCoverBox!.width / 2)

  const clampStyles = await longTitleNode.evaluate((element) => {
    const styles = window.getComputedStyle(element)
    return {
      lineClamp: styles.getPropertyValue('-webkit-line-clamp'),
      overflow: styles.overflow,
      height: element.getBoundingClientRect().height,
      lineHeight: Number.parseFloat(styles.lineHeight),
    }
  })
  expect(clampStyles.lineClamp).toBe('2')
  expect(clampStyles.overflow).toBe('hidden')
  expect(clampStyles.height).toBeLessThanOrEqual(clampStyles.lineHeight * 2 + 2)
})
