import { describe, expect, it } from 'vitest'
import { prepareMarkdown } from '../markdown'

describe('prepareMarkdown', () => {
  it('hides internal citations and turns assets into safe application URLs', () => {
    const result = prepareMarkdown(
      'Evidence [[cite:blk_1234567890abcdef1234567890abcdef]] blk_abcdef1234567890abcdef1234567890 ![](asset://11111111-1111-1111-1111-111111111111)',
      'package-id',
    )

    expect(result).not.toContain('blk_')
    expect(result).toContain('/api/v1/packages/package-id/assets/11111111-1111-1111-1111-111111111111')
  })
})
