import { useEffect, useState } from 'react'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router'
import { api } from './components/Common/api'
import { InfoContext } from './components/Common/info'
import Layout from './components/Layout/Layout/Layout'
import NotFound from './components/NotFound/NotFound'
import NewRunPage from './components/Runs/NewRunPage/NewRunPage'
import RunPage from './components/Runs/RunPage/RunPage'
import RunsPage from './components/Runs/RunsPage/RunsPage'
import ScenarioPage from './components/Scenarios/ScenarioPage/ScenarioPage'
import ScenariosPage from './components/Scenarios/ScenariosPage/ScenariosPage'

/** The application: server information for every page, and the routes. */
export default function App() {
  const [info, setInfo] = useState(null)

  useEffect(() => {
    api.info().then(setInfo, () => setInfo({ unavailable: true }))
  }, [])

  return (
    <InfoContext.Provider value={info}>
      <BrowserRouter>
        <Routes>
          <Route element={<Layout />}>
            <Route index element={<Navigate to="/executions" replace />} />
            <Route path="executions" element={<RunsPage />} />
            <Route path="executions/new" element={<NewRunPage />} />
            <Route path="executions/:runId" element={<RunPage />} />
            <Route path="scenarios" element={<ScenariosPage />} />
            <Route path="scenarios/:name" element={<ScenarioPage />} />
            <Route path="*" element={<NotFound />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </InfoContext.Provider>
  )
}
