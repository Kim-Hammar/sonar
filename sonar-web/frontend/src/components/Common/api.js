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
  runs: () => request('/api/runs'),
  run: (id) => request(`/api/runs/${name(id)}`),
  createRun: (body) => request('/api/runs', { method: 'POST', body }),
  cancelRun: (id) => request(`/api/runs/${name(id)}/cancel`, { method: 'POST', body: {} }),
  events: (id, after, limit) =>
    request(`/api/runs/${name(id)}/events?after=${after}&limit=${limit}`),
  event: (id, seq) => request(`/api/runs/${name(id)}/events/${seq}`),
  streamUrl: (id, after) => `/api/runs/${name(id)}/stream?after=${after}`
}
