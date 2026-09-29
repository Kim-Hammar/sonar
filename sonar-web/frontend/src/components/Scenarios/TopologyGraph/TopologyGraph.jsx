import { forceCenter, forceLink, forceManyBody, forceSimulation, forceX, forceY } from 'd3-force'
import { select } from 'd3-selection'
import { zoom, zoomIdentity } from 'd3-zoom'
import { useEffect, useMemo, useRef, useState } from 'react'
import './TopologyGraph.css'

const PALETTE = [
  '#2563eb',
  '#ea580c',
  '#16a34a',
  '#dc2626',
  '#9333ea',
  '#0d9488',
  '#ca8a04',
  '#db2777',
  '#92400e',
  '#0891b2',
  '#65a30d',
  '#4338ca'
]
const TICKS = 300

/** Lays routers out with a force simulation; the same input always gives the same layout. */
export function layout(routers, links) {
  const nodes = routers.map((router) => ({ id: router.name, asn: router.asn }))
  const edges = links.map((link) => ({ source: link.a, target: link.b }))
  const simulation = forceSimulation(nodes)
    .force(
      'link',
      forceLink(edges)
        .id((node) => node.id)
        .distance(26)
        .strength(0.8)
    )
    .force('charge', forceManyBody().strength(-40))
    .force('center', forceCenter(0, 0))
    .force('x', forceX().strength(0.04))
    .force('y', forceY().strength(0.04))
    .stop()
  for (let tick = 0; tick < TICKS; tick++) {
    simulation.tick()
  }
  return { nodes, edges }
}

/** Returns the autonomous systems in ascending order, each with its colour. */
export function systems(routers) {
  const numbers = [
    ...new Set(routers.map((router) => router.asn).filter((asn) => asn !== undefined))
  ].sort((a, b) => a - b)
  return numbers.map((asn, index) => ({ asn, color: PALETTE[index % PALETTE.length] }))
}

/**
 * The physical topology as a zoomable graph, routers coloured by BGP autonomous system.
 * Highlighted routers get a ring; clicking a router selects it.
 */
export default function TopologyGraph({
  routers,
  links,
  highlighted = new Set(),
  selected,
  onSelect,
  height = 520
}) {
  const svg = useRef(null)
  const [transform, setTransform] = useState(zoomIdentity)
  const { nodes, edges } = useMemo(() => layout(routers, links), [routers, links])
  const colors = useMemo(
    () => new Map(systems(routers).map((entry) => [entry.asn, entry.color])),
    [routers]
  )
  const legend = useMemo(() => systems(routers), [routers])
  const bounds = useMemo(() => {
    const xs = nodes.map((node) => node.x)
    const ys = nodes.map((node) => node.y)
    const pad = 24
    return {
      x: Math.min(...xs, 0) - pad,
      y: Math.min(...ys, 0) - pad,
      width: Math.max(...xs, 0) - Math.min(...xs, 0) + 2 * pad,
      height: Math.max(...ys, 0) - Math.min(...ys, 0) + 2 * pad
    }
  }, [nodes])

  useEffect(() => {
    const element = svg.current
    if (!element) {
      return undefined
    }
    const behaviour = zoom()
      .extent([
        [0, 0],
        [bounds.width, bounds.height]
      ])
      .scaleExtent([0.5, 8])
      .on('zoom', (event) => setTransform(event.transform))
    select(element).call(behaviour)
    return () => select(element).on('.zoom', null)
  }, [bounds])

  return (
    <div className="TopologyGraph card">
      <svg
        ref={svg}
        className="TopologyGraph-canvas"
        viewBox={`${bounds.x} ${bounds.y} ${bounds.width} ${bounds.height}`}
        style={{ height }}
        role="img"
        aria-label={`Topology of ${routers.length} routers`}
      >
        <g transform={transform.toString()}>
          {edges.map((edge, index) => (
            <line
              key={index}
              className="TopologyGraph-link"
              x1={edge.source.x}
              y1={edge.source.y}
              x2={edge.target.x}
              y2={edge.target.y}
            />
          ))}
          {nodes.map((node) => (
            <g
              key={node.id}
              className="TopologyGraph-node"
              transform={`translate(${node.x},${node.y})`}
              onClick={onSelect ? () => onSelect(node.id) : undefined}
            >
              {highlighted.has(node.id) && <circle r={7.5} className="TopologyGraph-ring" />}
              <circle r={node.id === selected ? 6 : 4.5} fill={colors.get(node.asn) || '#98a2b3'} />
              <title>{node.asn === undefined ? node.id : `${node.id}, AS ${node.asn}`}</title>
              {node.id === selected && (
                <text className="TopologyGraph-label" y={-10} textAnchor="middle">
                  {node.id}
                </text>
              )}
            </g>
          ))}
        </g>
      </svg>
      <div className="TopologyGraph-legend">
        <span className="muted">
          {routers.length} routers, {links.length} links
        </span>
        {legend.map((entry) => (
          <span key={entry.asn} className="TopologyGraph-system">
            <span className="TopologyGraph-dot" style={{ background: entry.color }} />
            AS {entry.asn}
          </span>
        ))}
        {highlighted.size > 0 && (
          <span className="TopologyGraph-system">
            <span className="TopologyGraph-dot ring" />
            changed
          </span>
        )}
      </div>
    </div>
  )
}
