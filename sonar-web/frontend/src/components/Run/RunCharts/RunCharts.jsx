import { formatCompact, formatNumber } from '../../Common/format'
import LineChart from '../LineChart/LineChart'
import './RunCharts.css'

const CHARTS = [
  {
    title: 'Specifications remaining',
    format: formatNumber,
    series: [{ key: 'remaining', label: 'Remaining', color: 'var(--series-1)' }]
  },
  {
    title: 'Tokens',
    format: formatCompact,
    series: [
      { key: 'inputTokens', label: 'Input', color: 'var(--series-1)' },
      { key: 'outputTokens', label: 'Output', color: 'var(--series-2)' }
    ]
  },
  {
    title: 'Decisions and provider calls',
    format: formatNumber,
    series: [
      { key: 'decisions', label: 'Decisions', color: 'var(--series-3)' },
      { key: 'providerCalls', label: 'Provider calls', color: 'var(--series-4)' }
    ]
  },
  {
    title: 'Verifications',
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
        <h2>Progress</h2>
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
            series={chart.series}
            format={chart.format}
            points={points}
          />
        ))}
      </div>
    </section>
  )
}
