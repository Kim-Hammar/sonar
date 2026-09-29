import { useEffect, useMemo, useState } from 'react'
import Alert from '../../Common/Alert/Alert'
import { api } from '../../Common/api'
import CodeBlock from '../../Common/CodeBlock/CodeBlock'
import './ConfigViewer.css'

/** A router list beside the selected router's configuration; `initial` chooses the first selection. */
export default function ConfigViewer({ scenario, routers, highlighted = new Set(), initial }) {
  const [query, setQuery] = useState('')
  const [selected, setSelected] = useState(initial || routers[0])
  const [loaded, setLoaded] = useState({ router: null, text: null, error: null })
  const shown = useMemo(
    () => routers.filter((router) => router.includes(query.trim())),
    [routers, query]
  )

  useEffect(() => {
    if (!selected) {
      return undefined
    }
    let active = true
    api.config(scenario, selected).then(
      (body) => active && setLoaded({ router: selected, text: body, error: null }),
      (failure) => active && setLoaded({ router: selected, text: null, error: failure.message })
    )
    return () => {
      active = false
    }
  }, [scenario, selected])

  const current = loaded.router === selected ? loaded : { text: null, error: null }

  return (
    <div className="ConfigViewer">
      <div className="ConfigViewer-list card">
        <input
          className="input"
          placeholder="Filter routers"
          aria-label="Filter routers"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
        <ul>
          {shown.map((router) => (
            <li key={router}>
              <button
                type="button"
                className={
                  router === selected ? 'ConfigViewer-router active' : 'ConfigViewer-router'
                }
                onClick={() => setSelected(router)}
              >
                <span className="mono">{router}</span>
                {highlighted.has(router) && (
                  <span className="ConfigViewer-changed" title="Changed by an adopted repair" />
                )}
              </button>
            </li>
          ))}
        </ul>
      </div>
      <div className="ConfigViewer-text">
        <Alert>{current.error}</Alert>
        {current.text === null && !current.error && <div className="muted">Loading…</div>}
        {current.text !== null && <CodeBlock text={current.text} numbered maxHeight={560} />}
      </div>
    </div>
  )
}
