import { useMemo, useState } from 'react'
import './ScenarioPicker.css'

/** A searchable list of scenarios with checkboxes. */
export default function ScenarioPicker({ scenarios, selected, onChange }) {
  const [query, setQuery] = useState('')
  const shown = useMemo(
    () => scenarios.filter((scenario) => scenario.includes(query.trim())),
    [scenarios, query]
  )
  const toggle = (scenario) => {
    const next = new Set(selected)
    if (next.has(scenario)) {
      next.delete(scenario)
    } else {
      next.add(scenario)
    }
    onChange(next)
  }
  return (
    <div className="ScenarioPicker">
      <div className="ScenarioPicker-bar">
        <input
          className="input"
          placeholder="Filter scenarios"
          aria-label="Filter scenarios"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
        <button
          type="button"
          className="button quiet"
          onClick={() => onChange(new Set([...selected, ...shown]))}
        >
          Select shown
        </button>
        <button type="button" className="button quiet" onClick={() => onChange(new Set())}>
          Clear
        </button>
      </div>
      <ul className="ScenarioPicker-list">
        {shown.map((scenario) => (
          <li key={scenario}>
            <label>
              <input
                type="checkbox"
                checked={selected.has(scenario)}
                onChange={() => toggle(scenario)}
              />
              <span className="mono">{scenario}</span>
            </label>
          </li>
        ))}
        {shown.length === 0 && <li className="muted">No scenario matches</li>}
      </ul>
      <div className="ScenarioPicker-count muted">
        {selected.size} of {scenarios.length} selected
      </div>
    </div>
  )
}
