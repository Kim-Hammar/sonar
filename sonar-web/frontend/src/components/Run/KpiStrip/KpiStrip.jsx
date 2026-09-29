import { formatCompact, formatDuration, formatNumber } from '../../Common/format'
import './KpiStrip.css'

/** The headline numbers of a run, one tile each. */
export default function KpiStrip({ run, state, now }) {
  const scenarios = Object.values(state.scenarios)
  const done = scenarios.filter((scenario) =>
    ['solved', 'stopped', 'failed', 'cancelled'].includes(scenario.status)
  ).length
  const solved = scenarios.filter((scenario) => scenario.status === 'solved').length
  const initial = scenarios.reduce((sum, scenario) => sum + (scenario.initial ?? 0), 0)
  const elapsed = run.started ? (run.finished || now) - run.started : null
  const tiles = [
    { label: 'Scenarios', value: `${done} of ${run.scenarios.length}`, detail: `${solved} solved` },
    {
      label: 'Specifications remaining',
      value: formatNumber(state.totals.remaining),
      detail: initial ? `of ${formatNumber(initial)} violated` : null
    },
    { label: 'Verifications', value: formatNumber(state.totals.verifications) },
    {
      label: 'Decisions',
      value: formatNumber(state.totals.decisions),
      detail: `${formatNumber(state.totals.providerCalls)} provider calls`
    },
    {
      label: 'Tokens',
      value: formatCompact(state.totals.inputTokens + state.totals.outputTokens),
      detail: `${formatCompact(state.totals.inputTokens)} in, ${formatCompact(state.totals.outputTokens)} out`
    },
    { label: 'Elapsed', value: formatDuration(elapsed) }
  ]
  return (
    <div className="KpiStrip">
      {tiles.map((tile) => (
        <div key={tile.label} className="KpiStrip-tile card">
          <div className="KpiStrip-label">{tile.label}</div>
          <div className="KpiStrip-value">{tile.value}</div>
          {tile.detail && <div className="KpiStrip-detail">{tile.detail}</div>}
        </div>
      ))}
    </div>
  )
}
