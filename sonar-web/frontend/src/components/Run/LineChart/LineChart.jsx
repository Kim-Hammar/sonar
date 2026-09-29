import { bisector, max } from 'd3-array'
import { scaleLinear } from 'd3-scale'
import { curveStepAfter, line } from 'd3-shape'
import { useMemo, useState } from 'react'
import useElementSize from '../../../hooks/useElementSize'
import { formatDuration, formatNumber } from '../../Common/format'
import './LineChart.css'

const MAX_POINTS = 500
const MARGIN = { top: 10, right: 12, bottom: 22, left: 48 }
const nearest = bisector((point) => point.t)

/** Thins a series to at most `limit` points, keeping the first and the last. */
export function downsample(points, limit) {
  if (points.length <= limit) {
    return points
  }
  const step = Math.ceil(points.length / limit)
  const kept = []
  for (let index = 0; index < points.length; index += step) {
    kept.push(points[index])
  }
  if (kept[kept.length - 1] !== points[points.length - 1]) {
    kept.push(points[points.length - 1])
  }
  return kept
}

/**
 * A compact step chart of one or more cumulative metrics over the time since the run started.
 * Hovering shows the values at the nearest event.
 */
export default function LineChart({
  title,
  series,
  points,
  format = formatNumber,
  height = 170,
  width
}) {
  const [ref, size] = useElementSize()
  const [hover, setHover] = useState(null)
  const data = useMemo(() => downsample(points, MAX_POINTS), [points])
  const outer = width ?? size.width
  const inner = {
    width: Math.max(0, outer - MARGIN.left - MARGIN.right),
    height: height - MARGIN.top - MARGIN.bottom
  }
  const x = scaleLinear()
    .domain([0, Math.max(1, max(data, (point) => point.t) ?? 1)])
    .range([0, inner.width])
  const top = max(series, (entry) => max(data, (point) => point[entry.key] ?? 0)) ?? 0
  const y = scaleLinear()
    .domain([0, Math.max(1, top)])
    .nice(4)
    .range([inner.height, 0])
  const paths = series.map((entry) => ({
    ...entry,
    d:
      line()
        .defined((point) => point[entry.key] !== null && point[entry.key] !== undefined)
        .x((point) => x(point.t))
        .y((point) => y(point[entry.key]))
        .curve(curveStepAfter)(data) || ''
  }))
  const ticks = y.ticks(4)
  const last = data[data.length - 1]

  const move = (event) => {
    if (data.length === 0) {
      return
    }
    const bounds = event.currentTarget.getBoundingClientRect()
    const t = x.invert(event.clientX - bounds.left - MARGIN.left)
    setHover(data[Math.min(data.length - 1, nearest.center(data, t))])
  }
  const shown = hover ?? last

  return (
    <figure className="LineChart card">
      <figcaption className="LineChart-caption">
        <span className="LineChart-title">{title}</span>
        <span className="LineChart-legend">
          {series.map((entry) => (
            <span key={entry.key} className="LineChart-key">
              <span className="LineChart-swatch" style={{ background: entry.color }} />
              {entry.label}
              <strong>{shown ? format(shown[entry.key]) : '–'}</strong>
            </span>
          ))}
        </span>
      </figcaption>
      <div ref={ref} className="LineChart-frame" style={{ height }}>
        {data.length === 0 && <div className="LineChart-empty">No data yet</div>}
        {data.length > 0 && outer > 0 && (
          <svg
            width={outer}
            height={height}
            onMouseMove={move}
            onMouseLeave={() => setHover(null)}
            role="img"
            aria-label={title}
          >
            <g transform={`translate(${MARGIN.left},${MARGIN.top})`}>
              {ticks.map((tick) => (
                <g key={tick} transform={`translate(0,${y(tick)})`}>
                  <line className="LineChart-grid" x1={0} x2={inner.width} />
                  <text className="LineChart-axis" x={-8} dy="0.32em" textAnchor="end">
                    {format(tick)}
                  </text>
                </g>
              ))}
              <text className="LineChart-axis" x={0} y={inner.height + 16}>
                0 s
              </text>
              <text
                className="LineChart-axis"
                x={inner.width}
                y={inner.height + 16}
                textAnchor="end"
              >
                {formatDuration(last ? last.t : 0)}
              </text>
              {paths.map((entry) => (
                <path key={entry.key} className="LineChart-line" d={entry.d} stroke={entry.color} />
              ))}
              {hover && (
                <line
                  className="LineChart-rule"
                  x1={x(hover.t)}
                  x2={x(hover.t)}
                  y1={0}
                  y2={inner.height}
                />
              )}
            </g>
          </svg>
        )}
      </div>
    </figure>
  )
}
