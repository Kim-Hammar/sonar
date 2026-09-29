import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import TopologyGraph, { layout, systems } from './TopologyGraph'

const ROUTERS = [
  { name: 'r1', asn: 65002 },
  { name: 'r2', asn: 65001 },
  { name: 'r3', asn: 65001 }
]
const LINKS = [
  { a: 'r1', b: 'r2', aInterface: 'GigabitEthernet0/0', bInterface: 'GigabitEthernet0/0' },
  { a: 'r2', b: 'r3', aInterface: 'GigabitEthernet0/1', bInterface: 'GigabitEthernet0/0' }
]

describe('TopologyGraph', () => {
  it('lays out the same topology the same way every time', () => {
    const first = layout(ROUTERS, LINKS).nodes.map((node) => [node.x, node.y])
    const second = layout(ROUTERS, LINKS).nodes.map((node) => [node.x, node.y])
    expect(second).toEqual(first)
  })

  it('colours routers by autonomous system in ascending order', () => {
    expect(systems(ROUTERS).map((entry) => entry.asn)).toEqual([65001, 65002])
  })

  it('draws every router and link and marks changed routers', () => {
    const { container } = render(
      <TopologyGraph routers={ROUTERS} links={LINKS} highlighted={new Set(['r2'])} height={300} />
    )
    expect(container.querySelectorAll('g.TopologyGraph-node')).toHaveLength(3)
    expect(container.querySelectorAll('line.TopologyGraph-link')).toHaveLength(2)
    expect(container.querySelectorAll('circle.TopologyGraph-ring')).toHaveLength(1)
    expect(screen.getByText('AS 65001')).toBeInTheDocument()
    expect(screen.getByText('changed')).toBeInTheDocument()
  })
})
