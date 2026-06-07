import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import StatusBadge from './StatusBadge'

describe('StatusBadge', () => {
  it('renders the localized status', () => {
    render(<StatusBadge status="PARTIALLY_READY" />)
    expect(screen.getByText('部分完成')).toBeInTheDocument()
  })
})

