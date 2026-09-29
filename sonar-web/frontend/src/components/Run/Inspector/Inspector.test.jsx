import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import Inspector from './Inspector'

const SUMMARY = {
  seq: 12,
  scenario: 'scenario-001',
  step: 4,
  at: 1,
  type: 'decided',
  question: 'region',
  outcome: 'answered',
  inputTokens: 900,
  outputTokens: 80,
  cached: false,
  usage: { verifications: 1, decisions: 2, providerCalls: 2, inputTokens: 1800, outputTokens: 160 }
}

const DETAIL = {
  seq: 12,
  type: 'decided',
  question: 'region',
  outcome: 'answered',
  context: [{ title: 'Violated specifications', body: 'reachability|r1|10.0.0.0/24' }],
  options: ['site:r1'],
  answer: { none: 0.1, regions: [{ id: 'site:r1', probability: 0.9 }] },
  exchanges: [
    {
      status: 200,
      latencyMillis: 812,
      cached: false,
      request: '{"model":"m"}',
      reply: '{"answers":{}}'
    }
  ]
}

describe('Inspector', () => {
  it('loads the detail once and keeps prompts folded until opened', async () => {
    const user = userEvent.setup()
    const load = vi.fn().mockResolvedValue(DETAIL)
    render(<Inspector runId="20260101-000000-abcd" event={SUMMARY} load={load} />)
    expect(await screen.findByText('Answer')).toBeInTheDocument()
    expect(load).toHaveBeenCalledWith('20260101-000000-abcd', 12)
    expect(load).toHaveBeenCalledTimes(1)
    expect(screen.getByText('0.900')).toBeInTheDocument()
    expect(screen.queryByText('reachability|r1|10.0.0.0/24')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Violated specifications/ }))
    expect(screen.getByText('reachability|r1|10.0.0.0/24')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Request 1/ }))
    expect(screen.getByText('"model": "m"')).toBeInTheDocument()
  })

  it('shows why a detail could not be loaded', async () => {
    const load = vi.fn().mockRejectedValue(new Error('no event 12'))
    render(<Inspector runId="20260101-000000-abcd" event={SUMMARY} load={load} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('no event 12')
  })
})
