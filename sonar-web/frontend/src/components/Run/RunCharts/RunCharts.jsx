import { formatCompact, formatNumber } from '../../Common/format'
import Hint from '../../Common/Hint/Hint'
import { HINTS } from '../../Common/hints'
import LineChart from '../LineChart/LineChart'
import './RunCharts.css'

const CHARTS = [
  {
    title: 'Specifications remaining',
    hint: HINTS.charts.remaining,
    format: formatNumber,
    series: [{ key: 'remaining', label: 'Remaining', color: 'var(--series-1)' }]
  },
  {
    title: 'Tokens',
    hint: HINTS.charts.tokens,
    format: formatCompact,
    series: [
      { key: 'inputTokens', label: 'Input', color: 'var(--series-1)' },
      { key: 'outputTokens', label: 'Output', color: 'var(--series-2)' }
    ]
  },
  {
    title: 'Decisions and provider calls',
    hint: HINTS.charts.decisions,
    format: formatNumber,
    series: [
      { key: 'decisions', label: 'Decisions', color: 'var(--series-3)' },
      { key: 'providerCalls', label: 'Provider calls', color: 'var(--series-4)' }
    ]
  },
  {
    title: 'Verifications',
    hint: HINTS.charts.verifications,
    format: formatNumber,
    series: [{ key: 'verifications', label: 'Verifications', color: 'var(--series-2)' }]
  }
]

/** The four live charts of a run, for all scenarios together or for one. */
export default function RunCharts({ state, scenario, onScenario }) {
  const points = scenario === 'all' ? state.aggregate : state.series[scenario] || []
  return (
    <section className="RunCharts">
      <div className="RunCharts-bar">
        <h2>
          <Hint {...HINTS.charts.progress}>Progress</Hint>
        </h2>
        {state.order.length > 1 && (
          <select
            className="input"
            aria-label="Scenario"
            value={scenario}
            onChange={(event) => onScenario(event.target.value)}
          >
            <option value="all">All scenarios</option>
            {state.order.map((name) => (
              <option key={name} value={name}>
                {name}
              </option>
            ))}
          </select>
        )}
      </div>
      <div className="RunCharts-grid">
        {CHARTS.map((chart) => (
          <LineChart
            key={chart.title}
            title={chart.title}
            hint={chart.hint}
            series={chart.series}
            format={chart.format}
            points={points}
          />
        ))}
      </div>
    </section>
  )
}
