import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { createElement } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { api } from '../api'
import { prepareMarkdown } from '../markdown'
import MarkdownViewer from './MarkdownViewer'

vi.mock('../api', () => ({
  api: {
    citation: vi.fn(),
  },
}))

describe('prepareMarkdown', () => {
  it('turns internal citations into clickable application links', () => {
    const result = prepareMarkdown(
      'Evidence [[cite:blk_1234567890abcdef1234567890abcdef]] blk_abcdef1234567890abcdef1234567890 ![](asset://11111111-1111-1111-1111-111111111111)',
      'package-id',
    )

    expect(result).toContain('[来源](citation://blk_1234567890abcdef1234567890abcdef)')
    expect(result).not.toContain(' blk_abcdef1234567890abcdef1234567890')
    expect(result).toContain('/api/v1/packages/package-id/assets/11111111-1111-1111-1111-111111111111')
  })

  it('opens citation details from rendered markdown', async () => {
    vi.mocked(api.citation).mockResolvedValue({
      blockId: 'blk_1234567890abcdef1234567890abcdef',
      sourceKind: 'TEXT_PARAGRAPH',
      displayName: '文本第 1 段',
      paragraphNumber: 1,
      excerpt: 'Thread pools reuse worker threads.',
    })

    render(
      createElement(MarkdownViewer, {
        packageId: 'package-id',
        markdown: 'Evidence [[cite:blk_1234567890abcdef1234567890abcdef]]',
      }),
    )

    fireEvent.click(screen.getByRole('button', { name: '来源' }))

    await waitFor(() =>
      expect(api.citation).toHaveBeenCalledWith(
        'package-id',
        'blk_1234567890abcdef1234567890abcdef',
      ),
    )
    expect(await screen.findByText('Thread pools reuse worker threads.')).toBeInTheDocument()
  })
})
