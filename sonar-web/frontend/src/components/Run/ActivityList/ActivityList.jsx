import { useMemo, useState } from 'react'
import { describeEvent, EVENT_LABELS, formatTime } from '../../Common/format'
import Hint from '../../Common/Hint/Hint'
import { HINTS } from '../../Common/hints'
import './ActivityList.css'

const PAGE = 300

/** The events of a run, newest first, filtered by scenario and type; an event opens the inspector. */
export default function ActivityList({ events, scenarios, onOpen }) {
  const [scenario, setScenario] = useState('all')
  const [type, setType] = useState('all')
  const [limit, setLimit] = useState(PAGE)
  const shown = useMemo(() => {
    const matching = []
    for (let index = events.length - 1; index >= 0; index--) {
      const event = events[index]
      if (
        (scenario === 'all' || event.scenario === scenario) &&
        (type === 'all' || event.type === type)
      ) {
        matching.push(event)
      }
    }
    return matching
  }, [events, scenario, type])

  return (
    <section className="ActivityList">
      <div className="ActivityList-bar">
        {scenarios.length > 1 && (
          <select
            className="input"
            aria-label="Scenario"
            value={scenario}
            onChange={(event) => setScenario(event.target.value)}
          >
            <option value="all">All scenarios</option>
            {scenarios.map((name) => (
              <option key={name} value={name}>
                {name}
              </option>
            ))}
          </select>
        )}
        <select
          className="input"
          aria-label="Event type"
          value={type}
          onChange={(event) => setType(event.target.value)}
        >
          <option value="all">All events</option>
          {Object.entries(EVENT_LABELS).map(([key, label]) => (
            <option key={key} value={key}>
              {label}
            </option>
          ))}
        </select>
        <Hint label="event types" {...HINTS.activity.types} />
        <span className="muted">{shown.length} events</span>
      </div>
      {shown.length === 0 && <div className="ActivityList-empty muted">No events yet</div>}
      <ol className="ActivityList-items card">
        {shown.slice(0, limit).map((event) => (
          <li key={event.seq}>
            <button type="button" className="ActivityList-item" onClick={() => onOpen(event.seq)}>
              <span className="ActivityList-time mono">{formatTime(event.at)}</span>
              {scenarios.length > 1 && (
                <span className="ActivityList-scenario mono">{event.scenario}</span>
              )}
              <span className={`ActivityList-type ${event.type}`}>
                {EVENT_LABELS[event.type] || event.type}
              </span>
              <span className="ActivityList-text">{describeEvent(event)}</span>
            </button>
          </li>
        ))}
      </ol>
      {shown.length > limit && (
        <button
          type="button"
          className="button quiet ActivityList-more"
          onClick={() => setLimit(limit + PAGE)}
        >
          Show more
        </button>
      )}
    </section>
  )
}
