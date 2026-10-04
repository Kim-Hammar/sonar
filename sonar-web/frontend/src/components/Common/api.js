/** Sends a request to the server and returns the decoded JSON, or throws an error with the server's message. */
export async function request(path, { method = 'GET', body, signal } = {}) {
  const response = await fetch(path, {
    method,
    signal,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body)
  })
  const text = await response.text()
  let data = null
  if (text) {
    try {
      data = JSON.parse(text)
    } catch {
      data = { text }
    }
  }
  if (!response.ok) {
    const error = new Error((data && data.error) || `HTTP ${response.status}`)
    error.status = response.status
    throw error
  }
  return data
}

/** Fetches a text resource, such as a router configuration. */
export async function requestText(path) {
  const response = await fetch(path)
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`)
  }
  return response.text()
}

const name = (value) => encodeURIComponent(value)

/** The server's API. */
export const api = {
  info: () => request('/api/info'),
  scenarios: () => request('/api/scenarios'),
  scenario: (scenario) => request(`/api/scenarios/${name(scenario)}`),
  topology: (scenario) => request(`/api/scenarios/${name(scenario)}/topology`),
  specifications: (scenario, percent) =>
    request(`/api/scenarios/${name(scenario)}/specifications?percent=${percent}`),
  config: (scenario, router) =>
    requestText(`/api/scenarios/${name(scenario)}/routers/${name(router)}/config`),
  verification: (scenario, percent) =>
    request(`/api/scenarios/${name(scenario)}/verification?percent=${percent}`),
  verify: (scenario, percent) =>
    request(`/api/scenarios/${name(scenario)}/verification`, {
      method: 'POST',
      body: { percent }
    }),
  verifiedSpecifications: (scenario, percent) =>
    request(`/api/scenarios/${name(scenario)}/verification/specifications?percent=${percent}`),
  verifiedTraces: (scenario, percent, specification) =>
    request(
      `/api/scenarios/${name(scenario)}/verification/traces?percent=${percent}&specification=${name(specification)}`
    ),
  verifiedForwarding: (
    scenario,
    percent,
    { node = '', action = '', prefix = '', offset = 0, limit }
  ) =>
    request(
      `/api/scenarios/${name(scenario)}/verification/forwarding?percent=${percent}&node=${name(node)}&action=${name(action)}&prefix=${name(prefix)}&offset=${offset}&limit=${limit}`
    ),
  verifiedRoutes: (scenario, percent) =>
    request(`/api/scenarios/${name(scenario)}/verification/routes?percent=${percent}`),
  search: (scenario, percent) =>
    request(`/api/scenarios/${name(scenario)}/search?percent=${percent}`),
  explore: (scenario, percent) =>
    request(`/api/scenarios/${name(scenario)}/search`, { method: 'POST', body: { percent } }),
  searchRegions: (
    scenario,
    percent,
    { kind = '', router = '', partition = '', query = '', offset = 0, limit }
  ) =>
    request(
      `/api/scenarios/${name(scenario)}/search/regions?percent=${percent}&kind=${name(kind)}&router=${name(router)}&partition=${name(partition)}&query=${name(query)}&offset=${offset}&limit=${limit}`
    ),
  searchRegion: (scenario, percent, id) =>
    request(`/api/scenarios/${name(scenario)}/search/region?percent=${percent}&id=${name(id)}`),
  expandRegion: (scenario, percent, id) =>
    request(`/api/scenarios/${name(scenario)}/search/expand`, {
      method: 'POST',
      body: { percent, region: id }
    }),
  runs: () => request('/api/runs'),
  run: (id) => request(`/api/runs/${name(id)}`),
  createRun: (body) => request('/api/runs', { method: 'POST', body }),
  cancelRun: (id) => request(`/api/runs/${name(id)}/cancel`, { method: 'POST', body: {} }),
  deleteRun: (id) => request(`/api/runs/${name(id)}`, { method: 'DELETE' }),
  events: (id, after, limit) =>
    request(`/api/runs/${name(id)}/events?after=${after}&limit=${limit}`),
  event: (id, seq) => request(`/api/runs/${name(id)}/events/${seq}`),
  streamUrl: (id, after) => `/api/runs/${name(id)}/stream?after=${after}`
}
