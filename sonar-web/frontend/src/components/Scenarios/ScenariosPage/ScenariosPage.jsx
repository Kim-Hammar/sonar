import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router'
import Alert from '../../Common/Alert/Alert'
import { api } from '../../Common/api'
import DataTable from '../../Common/DataTable/DataTable'
import { HINTS } from '../../Common/hints'
import { useInfo } from '../../Common/info'
import PageHeader from '../../Layout/PageHeader/PageHeader'

/** The scenarios of the dataset the server was started with. */
export default function ScenariosPage() {
  const info = useInfo()
  const navigate = useNavigate()
  const [scenarios, setScenarios] = useState(null)
  const [query, setQuery] = useState('')
  const [error, setError] = useState(null)

  useEffect(() => {
    api.scenarios().then(
      (body) => setScenarios(body.scenarios),
      (failure) => setError(failure.message)
    )
  }, [])

  const shown = useMemo(
    () =>
      (scenarios || [])
        .filter((scenario) => scenario.includes(query.trim()))
        .map((name) => ({ name })),
    [scenarios, query]
  )

  return (
    <div className="page">
      <PageHeader
        title="Scenarios"
        meta={info && info.dataset ? <span className="mono">{info.dataset}</span> : null}
        actions={
          <input
            className="input"
            placeholder="Filter scenarios"
            aria-label="Filter scenarios"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
        }
      />
      <Alert>{error}</Alert>
      {scenarios && (
        <DataTable
          columns={[
            {
              id: 'name',
              header: `${shown.length} scenarios`,
              hint: HINTS.scenarios.list,
              cell: (row) => <span className="mono">{row.name}</span>
            }
          ]}
          rows={shown}
          rowKey={(row) => row.name}
          onRowClick={(row) => navigate(`/scenarios/${row.name}`)}
          empty="No scenario matches"
        />
      )}
    </div>
  )
}
