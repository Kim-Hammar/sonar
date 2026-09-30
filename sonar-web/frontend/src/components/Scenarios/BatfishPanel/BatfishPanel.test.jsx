import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import BatfishPanel from './BatfishPanel'

function Page(props) {
  const [view, setView] = useState('specifications')
  return <BatfishPanel {...props} view={view} onView={setView} />
}

const RUNNING = {
  scenario: 'scenario-001',
  percent: 10,
  status: 'running',
  requested: 1,
  started: 2,
  elapsedMillis: 500
}

const FINISHED = {
  ...RUNNING,
  status: 'finished',
  ended: 3402,
  elapsedMillis: 3400,
  summary: {
    routers: 3,
    forwardingRows: 5,
    specifications: 8,
    unresolved: 4,
    fixed: 1,
    sideEffects: 1,
    holding: 2,
    solved: false
  }
}

const SPECIFICATIONS = {
  specifications: [
    {
      key: 'reachability|r1|10.0.0.2/32',
      type: 'reachability',
      source: 'r1',
      destination: '10.0.0.2/32',
      status: 'broken_removed',
      initial: 'broken',
      state: 'unresolved'
    },
    {
      key: 'reachability|r2|10.0.0.1/32',
      type: 'reachability',
      source: 'r2',
      destination: '10.0.0.1/32',
      status: 'intact',
      initial: 'intact',
      state: 'holding'
    }
  ]
}

const TRACES = {
  key: 'reachability|r1|10.0.0.2/32',
  state: 'unresolved',
  flows: [{ hops: ['r1', 'r2'], disposition: 'no-route' }],
  routeHolders: ['r3'],
  forwarders: ['r1', 'r2']
}

const FORWARDING = {
  total: 5,
  matched: 2,
  offset: 0,
  rows: [
    { node: 'r1', prefix: '10.0.0.2/32', action: 'no_route', receivers: [] },
    {
      node: 'r1',
      prefix: '10.0.0.3/32',
      nextHopIp: '10.1.1.0',
      nextHopInterface: 'GigabitEthernet0/0',
      action: 'forwarded',
      receivers: []
    }
  ],
  nodes: ['r1', 'r2', 'r3'],
  actions: ['accepted', 'forwarded', 'no_route']
}

function notFound() {
  const error = new Error('scenario-001 has not been verified at 10%')
  error.status = 404
  return error
}

function fakeClient() {
  return {
    verification: vi.fn(),
    verify: vi.fn(),
    verifiedSpecifications: vi.fn().mockResolvedValue(SPECIFICATIONS),
    verifiedTraces: vi.fn().mockResolvedValue(TRACES),
    verifiedForwarding: vi.fn().mockResolvedValue(FORWARDING),
    verifiedRoutes: vi.fn().mockResolvedValue({ routes: [] })
  }
}

describe('BatfishPanel', () => {
  it('starts a verification, follows it, and shows what Batfish found', async () => {
    const user = userEvent.setup()
    const client = fakeClient()
    let phase = 'none'
    client.verification.mockImplementation(() =>
      phase === 'none'
        ? Promise.reject(notFound())
        : Promise.resolve({ verification: phase === 'running' ? RUNNING : FINISHED })
    )
    client.verify.mockImplementation(() => {
      phase = 'running'
      return Promise.resolve({ verification: { ...RUNNING, status: 'queued' } })
    })
    render(<Page scenario="scenario-001" percent={10} client={client} pollMillis={5} />)

    await user.click(await screen.findByRole('button', { name: 'Run Batfish' }))
    expect(client.verify).toHaveBeenCalledWith('scenario-001', 10)
    expect(
      await screen.findByText(/computing how the faulty network forwards traffic/)
    ).toBeInTheDocument()
    phase = 'finished'
    expect(await screen.findByText('Label mismatches')).toBeInTheDocument()
    expect(screen.getByText('of 8 specifications')).toBeInTheDocument()
    expect(screen.getByText('1 fixed, 1 side effects')).toBeInTheDocument()
    expect(screen.getByText('3.4 s')).toBeInTheDocument()

    expect(await screen.findByText('10.0.0.2/32')).toBeInTheDocument()
    expect(client.verifiedSpecifications).toHaveBeenCalledWith('scenario-001', 10)
    await user.click(screen.getByText('10.0.0.2/32'))
    expect(client.verifiedTraces).toHaveBeenCalledWith(
      'scenario-001',
      10,
      'reachability|r1|10.0.0.2/32'
    )
    expect(await screen.findByText('no-route')).toBeInTheDocument()
    expect(screen.getByText('r3')).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: /Forwarding table/ }))
    expect(await screen.findByText('GigabitEthernet0/0')).toBeInTheDocument()
    expect(client.verifiedForwarding).toHaveBeenCalledWith('scenario-001', 10, {
      node: '',
      action: '',
      prefix: '',
      offset: 0,
      limit: 200
    })
    expect(screen.getByText('2 of 5 rows')).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Router'), 'r2')
    await waitFor(() =>
      expect(client.verifiedForwarding).toHaveBeenLastCalledWith('scenario-001', 10, {
        node: 'r2',
        action: '',
        prefix: '',
        offset: 0,
        limit: 200
      })
    )
  })

  it('shows why a verification failed and offers to run it again', async () => {
    const client = fakeClient()
    client.verification.mockResolvedValue({
      verification: { ...RUNNING, status: 'failed', ended: 900, error: 'Batfish exploded' }
    })
    render(<Page scenario="scenario-001" percent={10} client={client} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Batfish failed: Batfish exploded')
    expect(screen.getByRole('button', { name: 'Run again' })).toBeInTheDocument()
    expect(client.verify).not.toHaveBeenCalled()
  })
})
