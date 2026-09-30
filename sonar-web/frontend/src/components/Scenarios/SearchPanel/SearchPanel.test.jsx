import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import SearchPanel from './SearchPanel'

const FACTORS = ['spectrum', 'relation', 'frontier']

const BUILDING = {
  scenario: 'scenario-001',
  percent: 10,
  status: 'building',
  requested: 1,
  started: 2,
  elapsedMillis: 700,
  factors: FACTORS
}

const FINISHED = {
  ...BUILDING,
  status: 'finished',
  ended: 2400,
  elapsedMillis: 2398,
  summary: {
    routers: 3,
    specifications: 8,
    unresolved: 4,
    regions: 2,
    partitions: 1,
    kinds: { site: 1, relation: 1, incidence: 0, transfer: 0 },
    bound: 19,
    expanded: 0,
    dropped: 0,
    expanding: 0,
    candidates: 0
  }
}

const ROWS = {
  total: 2,
  matched: 2,
  offset: 0,
  kinds: ['incidence', 'relation', 'site', 'transfer'],
  routers: ['r1', 'r2'],
  partitions: [{ id: 'abc-0', regions: 2, mass: 1 }],
  regions: [
    {
      id: 'relation:bgp-session:1234',
      kind: 'relation',
      routers: ['r1', 'r2'],
      targets: 2,
      bound: 16,
      partition: 'abc-0',
      prior: 0.75,
      weight: 3,
      factors: { spectrum: 1.5, relation: 2, frontier: 1 },
      expansion: 'deferred'
    },
    {
      id: 'site:interface:r1:GigabitEthernet0/0',
      kind: 'site',
      routers: ['r1'],
      targets: 1,
      bound: 3,
      partition: 'abc-0',
      prior: 0.25,
      weight: 1,
      factors: { spectrum: 1, relation: 1, frontier: 1 },
      expansion: 'deferred'
    }
  ]
}

const SITE = {
  ...ROWS.regions[1],
  specifications: [
    {
      key: 'reachability|r1|10.0.0.2/32',
      type: 'reachability',
      source: 'r1',
      destination: '10.0.0.2/32',
      status: 'broken_removed',
      initial: 'broken'
    }
  ],
  site: {
    ref: 'interface:r1:GigabitEthernet0/0',
    type: 'interface',
    router: 'r1',
    key: ['GigabitEthernet0/0'],
    attributes: { address: ['10.1.1.1'] },
    statements: [
      {
        first: 6,
        last: 7,
        lines: ['interface GigabitEthernet0/0', ' ip address 10.1.1.1 255.255.255.254']
      }
    ]
  },
  expansion: { status: 'deferred' }
}

const ADMITTED = {
  ...SITE,
  expansion: { status: 'admitted', requested: 1, started: 2, ended: 900, elapsedMillis: 898 },
  candidates: [
    {
      id: 'abcdef0123456789',
      abbreviated: 'abcdef01',
      cost: 1,
      support: 'observed',
      routers: ['r1'],
      restores: [{ relation: 'link', strength: 'soft', text: 'soft equal a.address b.address' }],
      edits: [
        {
          router: 'r1',
          op: 'replace',
          first: 7,
          last: 7,
          removed: [' ip address 10.1.1.1 255.255.255.254'],
          lines: [' ip address 10.1.1.0 255.255.255.254']
        }
      ]
    }
  ]
}

function notFound() {
  const error = new Error('scenario-001 has no search space at 10%')
  error.status = 404
  return error
}

function Page(props) {
  const [region, setRegion] = useState(null)
  return <SearchPanel {...props} region={region} onRegion={setRegion} />
}

function fakeClient() {
  return {
    search: vi.fn(),
    explore: vi.fn(),
    searchRegions: vi.fn().mockResolvedValue(ROWS),
    searchRegion: vi.fn(),
    expandRegion: vi.fn()
  }
}

describe('SearchPanel', () => {
  it('builds the search space, lists its regions and generates candidates', async () => {
    const user = userEvent.setup()
    const client = fakeClient()
    let phase = 'none'
    client.search.mockImplementation(() =>
      phase === 'none'
        ? Promise.reject(notFound())
        : Promise.resolve({ search: phase === 'building' ? BUILDING : FINISHED })
    )
    client.explore.mockImplementation(() => {
      phase = 'building'
      return Promise.resolve({ search: { ...BUILDING, status: 'queued' } })
    })
    let expansion = 'deferred'
    client.searchRegion.mockImplementation(() =>
      Promise.resolve({ region: expansion === 'admitted' ? ADMITTED : SITE })
    )
    client.expandRegion.mockImplementation(() => {
      expansion = 'admitted'
      return Promise.resolve({ region: { ...SITE, expansion: { status: 'queued', requested: 1 } } })
    })
    render(<Page scenario="scenario-001" percent={10} client={client} pollMillis={5} />)

    await user.click(await screen.findByRole('button', { name: 'Build search space' }))
    expect(client.explore).toHaveBeenCalledWith('scenario-001', 10)
    expect(
      await screen.findByText('The regions and their prior are being computed.')
    ).toBeInTheDocument()
    phase = 'finished'
    expect(await screen.findByText('1 partitions')).toBeInTheDocument()
    expect(screen.getByText('≤ 19')).toBeInTheDocument()
    expect(await screen.findByText('relation:bgp-session:1234')).toBeInTheDocument()
    expect(client.searchRegions).toHaveBeenCalledWith('scenario-001', 10, {
      kind: '',
      router: '',
      partition: '',
      query: '',
      offset: 0,
      limit: 100
    })
    expect(screen.getByText('75.0%')).toBeInTheDocument()
    expect(screen.getAllByText('1.50')).not.toHaveLength(0)

    await user.click(screen.getByText('site:interface:r1:GigabitEthernet0/0'))
    const drawer = await screen.findByRole('dialog')
    expect(client.searchRegion).toHaveBeenCalledWith(
      'scenario-001',
      10,
      'site:interface:r1:GigabitEthernet0/0'
    )
    expect(
      await within(drawer).findByText(/ip address 10.1.1.1 255.255.255.254/)
    ).toBeInTheDocument()
    expect(within(drawer).getByText('interface:r1:GigabitEthernet0/0')).toBeInTheDocument()
    await user.click(within(drawer).getByRole('button', { name: 'Generate candidates' }))
    expect(client.expandRegion).toHaveBeenCalledWith(
      'scenario-001',
      10,
      'site:interface:r1:GigabitEthernet0/0'
    )
    await user.click(await within(drawer).findByRole('button', { name: /abcdef01/ }))
    expect(within(drawer).getByText('− ip address 10.1.1.1 255.255.255.254')).toBeInTheDocument()
    expect(within(drawer).getByText('+ ip address 10.1.1.0 255.255.255.254')).toBeInTheDocument()
    expect(within(drawer).getByText('soft equal a.address b.address')).toBeInTheDocument()
    expect(within(drawer).getByRole('button', { name: 'About soft rule' })).toBeInTheDocument()
    await waitFor(() => expect(client.searchRegions.mock.calls.length).toBeGreaterThan(1))
  })

  it('filters the regions by kind', async () => {
    const user = userEvent.setup()
    const client = fakeClient()
    client.search.mockResolvedValue({ search: FINISHED })
    render(<Page scenario="scenario-001" percent={10} client={client} />)
    await user.selectOptions(await screen.findByLabelText('Kind'), 'site')
    await waitFor(() =>
      expect(client.searchRegions).toHaveBeenLastCalledWith('scenario-001', 10, {
        kind: 'site',
        router: '',
        partition: '',
        query: '',
        offset: 0,
        limit: 100
      })
    )
  })

  it('explains how the search space is built when asked', async () => {
    const user = userEvent.setup()
    const client = fakeClient()
    client.search.mockRejectedValue(notFound())
    render(<Page scenario="scenario-001" percent={10} client={client} />)
    await user.click(await screen.findByRole('button', { name: /How the search space is built/ }))
    expect(screen.getByText(/A relation names the objects it connects/)).toBeInTheDocument()
    expect(screen.getByText(/one violated relation/)).toBeInTheDocument()
  })

  it('shows why a build failed and offers to run it again', async () => {
    const client = fakeClient()
    client.search.mockResolvedValue({
      search: { ...BUILDING, status: 'failed', ended: 900, error: 'the verification failed: boom' }
    })
    render(<Page scenario="scenario-001" percent={10} client={client} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('the verification failed: boom')
    expect(screen.getByRole('button', { name: 'Run again' })).toBeInTheDocument()
    expect(client.explore).not.toHaveBeenCalled()
  })
})
