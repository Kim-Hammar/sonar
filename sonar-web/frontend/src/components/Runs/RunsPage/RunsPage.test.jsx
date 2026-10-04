import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../Common/api'
import RunsPage from './RunsPage'

vi.mock('../../Common/api', () => ({
  api: {
    runs: vi.fn(),
    deleteRun: vi.fn()
  }
}))

const RUN = { scenarios: ['scenario-001'], created: 0, started: 0, finished: 1000, demo: false }

describe('RunsPage', () => {
  beforeEach(() => {
    api.runs.mockResolvedValue({
      runs: [
        { ...RUN, id: 'a', name: 'ended', status: 'finished' },
        { ...RUN, id: 'b', name: 'busy', status: 'running', finished: null }
      ]
    })
    api.deleteRun.mockReset().mockResolvedValue(null)
  })

  afterEach(() => vi.restoreAllMocks())

  const page = () =>
    render(
      <MemoryRouter>
        <RunsPage />
      </MemoryRouter>
    )

  it('deletes an ended execution after confirmation and offers nothing for a running one', async () => {
    const user = userEvent.setup()
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    page()
    await user.click(await screen.findByRole('button', { name: 'Delete ended' }))
    expect(api.deleteRun).toHaveBeenCalledWith('a')
    await waitFor(() => expect(screen.queryByText('ended')).not.toBeInTheDocument())
    expect(screen.getByText('busy')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Delete busy' })).not.toBeInTheDocument()
  })

  it('keeps the execution when the deletion is not confirmed', async () => {
    const user = userEvent.setup()
    vi.spyOn(window, 'confirm').mockReturnValue(false)
    page()
    await user.click(await screen.findByRole('button', { name: 'Delete ended' }))
    expect(api.deleteRun).not.toHaveBeenCalled()
    expect(screen.getByText('ended')).toBeInTheDocument()
  })
})
