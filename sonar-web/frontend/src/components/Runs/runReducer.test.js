import { describe, expect, it } from 'vitest'
import { initialState, runReducer } from './runReducer'

const usage = (verifications, decisions = 0, inputTokens = 0) => ({
  verifications,
  decisions,
  providerCalls: decisions,
  inputTokens,
  outputTokens: 0
})

const EVENTS = [
  {
    seq: 1,
    scenario: 's1',
    step: 1,
    at: 1000,
    type: 'loaded',
    usage: usage(0),
    routers: 3,
    links: 2,
    specifications: 8,
    remaining: 5
  },
  {
    seq: 2,
    scenario: 's2',
    step: 1,
    at: 1100,
    type: 'loaded',
    usage: usage(0),
    routers: 3,
    links: 2,
    specifications: 8,
    remaining: 3
  },
  { seq: 3, scenario: 's1', step: 2, at: 1500, type: 'decided', usage: usage(1, 1, 900) },
  {
    seq: 4,
    scenario: 's1',
    step: 3,
    at: 2000,
    type: 'tried',
    usage: usage(2, 1, 900),
    remaining: 1,
    change: 'improvement'
  },
  {
    seq: 5,
    scenario: 's1',
    step: 4,
    at: 2100,
    type: 'adopted',
    usage: usage(2, 1, 900),
    remaining: 4,
    adopted: 1,
    routers: ['r1']
  },
  {
    seq: 6,
    scenario: 's1',
    step: 5,
    at: 3000,
    type: 'finished',
    usage: usage(2, 1, 900),
    remaining: 0,
    stop: 'solved'
  },
  {
    seq: 7,
    scenario: 's2',
    step: 2,
    at: 3100,
    type: 'failed',
    usage: usage(0),
    reason: 'engine missing'
  }
]

describe('runReducer', () => {
  it('folds events into scenario states and totals', () => {
    const state = runReducer(initialState, { type: 'events', events: EVENTS })
    expect(state.order).toEqual(['s1', 's2'])
    expect(state.scenarios.s1).toMatchObject({
      status: 'solved',
      remaining: 0,
      initial: 5,
      adopted: 1,
      tried: 1,
      changed: ['r1']
    })
    expect(state.scenarios.s2).toMatchObject({
      status: 'failed',
      remaining: 3,
      reason: 'engine missing'
    })
    expect(state.totals).toMatchObject({
      remaining: 3,
      verifications: 2,
      decisions: 1,
      inputTokens: 900
    })
    expect(state.lastSeq).toBe(7)
  })

  it('takes remaining specifications only from events that describe the state', () => {
    const state = runReducer(initialState, { type: 'events', events: EVENTS.slice(0, 4) })
    expect(state.scenarios.s1.remaining).toBe(5)
    expect(state.aggregate.map((point) => point.remaining)).toEqual([5, 8, 8, 8])
  })

  it('builds per-scenario and aggregate series with times since the first event', () => {
    const state = runReducer(initialState, { type: 'events', events: EVENTS })
    expect(state.series.s1.map((point) => point.t)).toEqual([0, 500, 1000, 1100, 2000])
    expect(state.aggregate).toHaveLength(7)
    expect(state.aggregate[6]).toMatchObject({ t: 2100, remaining: 3, verifications: 2 })
  })

  it('ignores events it has already seen', () => {
    const once = runReducer(initialState, { type: 'events', events: EVENTS })
    const twice = runReducer(once, { type: 'events', events: EVENTS.slice(3) })
    expect(twice).toBe(once)
    const ended = runReducer(twice, { type: 'ended' })
    expect(runReducer(ended, { type: 'connection', status: 'live' }).connection).toBe('ended')
  })
})
