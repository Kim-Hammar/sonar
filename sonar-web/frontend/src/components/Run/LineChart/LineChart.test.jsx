import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import LineChart, { downsample } from './LineChart'

const SERIES = [
  { key: 'inputTokens', label: 'Input', color: 'blue' },
  { key: 'outputTokens', label: 'Output', color: 'orange' }
]

describe('LineChart', () => {
  it('thins long series but keeps the first and last points', () => {
    const points = Array.from({ length: 1001 }, (_, index) => ({ t: index, value: index }))
    const thinned = downsample(points, 500)
    expect(thinned.length).toBeLessThanOrEqual(501)
    expect(thinned[0]).toBe(points[0])
    expect(thinned[thinned.length - 1]).toBe(points[1000])
    expect(downsample(points.slice(0, 10), 500)).toHaveLength(10)
  })

  it('draws one line per series and shows the latest values', () => {
    const points = [
      { t: 0, inputTokens: 0, outputTokens: 0 },
      { t: 1000, inputTokens: 900, outputTokens: 120 },
      { t: 2000, inputTokens: 1800, outputTokens: 250 }
    ]
    const { container } = render(
      <LineChart title="Tokens" series={SERIES} points={points} width={400} />
    )
    expect(container.querySelectorAll('path.LineChart-line')).toHaveLength(2)
    expect(screen.getByText('1,800')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Tokens' })).toBeInTheDocument()
  })

  it('says so when there is no data yet', () => {
    render(<LineChart title="Tokens" series={SERIES} points={[]} width={400} />)
    expect(screen.getByText('No data yet')).toBeInTheDocument()
  })
})
