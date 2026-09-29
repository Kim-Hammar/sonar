import { act, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import useRunEvents from './useRunEvents'

const event = (seq, type = 'decided') => ({
  seq,
  scenario: 's1',
  step: seq,
  at: 1000 + seq,
  type,
  usage: { verifications: seq, decisions: 0, providerCalls: 0, inputTokens: 0, outputTokens: 0 }
})

class FakeSource {
  static last = null

  constructor(url) {
    this.url = url
    this.readyState = 0
    this.listeners = {}
    this.closed = false
    FakeSource.last = this
  }

  addEventListener(type, listener) {
    this.listeners[type] = listener
  }

  emit(type, data) {
    this.listeners[type]({ data: JSON.stringify(data) })
  }

  close() {
    this.closed = true
    this.readyState = 2
  }
}

const openSource = (url) => new FakeSource(url)

function Probe({ fetchPage }) {
  const state = useRunEvents('20260101-000000-abcd', { openSource, fetchPage })
  return (
    <div>
      <span data-testid="count">{state.events.length}</span>
      <span data-testid="last">{state.lastSeq}</span>
      <span data-testid="connection">{state.connection}</span>
    </div>
  )
}

describe('useRunEvents', () => {
  it('loads the history, then streams new events once each', async () => {
    const fetchPage = async () => ({
      events: [event(1), event(2)],
      last: 2,
      size: 2,
      sealed: false
    })
    render(<Probe fetchPage={fetchPage} />)
    await waitFor(() => expect(FakeSource.last).not.toBeNull())
    expect(FakeSource.last.url).toBe('/api/runs/20260101-000000-abcd/stream?after=2')
    expect(screen.getByTestId('count')).toHaveTextContent('2')
    act(() => FakeSource.last.emit('events', [event(2), event(3), event(4)]))
    await waitFor(() => expect(screen.getByTestId('count')).toHaveTextContent('4'))
    act(() => FakeSource.last.listeners.end({ data: '{}' }))
    await waitFor(() => expect(screen.getByTestId('connection')).toHaveTextContent('ended'))
    expect(FakeSource.last.closed).toBe(true)
  })

  it('never opens a stream for a run that has ended', async () => {
    FakeSource.last = null
    const fetchPage = async () => ({
      events: [event(1, 'finished')],
      last: 1,
      size: 1,
      sealed: true
    })
    render(<Probe fetchPage={fetchPage} />)
    await waitFor(() => expect(screen.getByTestId('connection')).toHaveTextContent('ended'))
    expect(FakeSource.last).toBeNull()
    expect(screen.getByTestId('last')).toHaveTextContent('1')
  })
})
