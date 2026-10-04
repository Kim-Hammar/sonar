import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../../Common/api'
import { HINTS } from '../../Common/hints'
import { InfoContext } from '../../Common/info'
import NewRunPage, { runRequest, validate } from './NewRunPage'

vi.mock('../../Common/api', () => ({
  api: {
    scenarios: vi.fn(),
    createRun: vi.fn()
  }
}))

const INFO = {
  demo: false,
  model: '~typesafe/jev-latest',
  endpoint: 'https://example.invalid/decisions',
  defaults: {
    specificationPercent: 10,
    verificationBatch: 5,
    verificationWorkers: 1,
    decisionConcurrency: 4,
    generationParallelism: 8,
    scenarioParallelism: 1,
    seed: 0
  },
  limits: {
    verificationBatch: 64,
    verificationWorkers: 16,
    decisionConcurrency: 64,
    generationParallelism: 256,
    scenarioParallelism: 64
  }
}

const FORM = {
  name: ' first ',
  scenarios: new Set(['scenario-002', 'scenario-001']),
  percent: '20',
  budgets: { verifications: '25', decisions: '', input_tokens: '' },
  minutes: '1.5',
  seed: '7',
  advanced: {
    verificationBatch: '3',
    verificationWorkers: '2',
    decisionConcurrency: '4',
    generationParallelism: '8',
    scenarioParallelism: '1'
  }
}

describe('NewRunPage', () => {
  beforeEach(() => {
    api.scenarios.mockResolvedValue({ scenarios: ['scenario-001', 'scenario-002'] })
    api.createRun.mockReset()
  })

  it('turns the form into the request the server expects', () => {
    expect(runRequest(FORM)).toEqual({
      name: 'first',
      scenarios: ['scenario-001', 'scenario-002'],
      settings: {
        specificationPercent: 20,
        verificationBatch: 3,
        verificationWorkers: 2,
        decisionConcurrency: 4,
        generationParallelism: 8,
        scenarioParallelism: 1,
        limits: { verifications: 25 },
        timeLimitSeconds: 90,
        seed: 7
      }
    })
  })

  it('reports invalid values before anything is sent', () => {
    expect(validate({ ...FORM, scenarios: new Set() })).toMatch('at least one scenario')
    expect(
      validate({ ...FORM, advanced: { ...FORM.advanced, verificationWorkers: '99' } }, INFO.limits)
    ).toMatch('1 to 16')
    expect(validate({ ...FORM, budgets: { ...FORM.budgets, decisions: '-3' } })).toMatch(
      'Maximum decisions'
    )
    expect(validate(FORM, INFO.limits)).toBeNull()
  })

  it('starts a run with the chosen scenarios and budgets', async () => {
    const user = userEvent.setup()
    api.createRun.mockResolvedValue({ run: { id: '20260101-000000-abcd' } })
    render(
      <InfoContext.Provider value={INFO}>
        <MemoryRouter>
          <NewRunPage />
        </MemoryRouter>
      </InfoContext.Provider>
    )
    await user.click(screen.getByRole('button', { name: 'Start agent execution' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Choose at least one scenario.')
    await user.click(await screen.findByRole('checkbox', { name: 'scenario-002' }))
    await user.type(screen.getByLabelText('Maximum verifications'), '30')
    expect(screen.queryByLabelText('Seed')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Start agent execution' }))
    await waitFor(() => expect(api.createRun).toHaveBeenCalledTimes(1))
    const body = api.createRun.mock.calls[0][0]
    expect(body.scenarios).toEqual(['scenario-002'])
    expect(body.settings.limits).toEqual({ verifications: 30 })
    expect(body.settings.decisionConcurrency).toBe(4)
    expect(body.settings.scenarioParallelism).toBe(1)
    expect(body.settings.verificationBatch).toBe(5)
  })

  it('explains every field with an info icon', async () => {
    const user = userEvent.setup()
    render(
      <InfoContext.Provider value={INFO}>
        <MemoryRouter>
          <NewRunPage />
        </MemoryRouter>
      </InfoContext.Provider>
    )
    await user.click(screen.getByRole('button', { name: 'Advanced' }))
    expect(screen.queryByLabelText('Scenarios at a time')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Decision requests in flight')).not.toBeInTheDocument()
    const fields = [
      'Name',
      'Scenarios',
      'Specifications',
      'Maximum verifications',
      'Maximum decisions',
      'Maximum input tokens',
      'Maximum minutes',
      'Verification workers',
      'Generation threads',
      'Verification batch size',
      'Seed',
      'Decision model'
    ]
    for (const field of fields) {
      expect(screen.getByRole('button', { name: `About ${field}` })).toHaveAccessibleDescription(
        /\w/
      )
    }
    expect(screen.getAllByRole('button', { name: /^About / })).toHaveLength(fields.length)
    expect(screen.getByRole('button', { name: 'About Seed' })).toHaveAccessibleDescription(
      HINTS.settings.seed.text
    )
    expect(screen.getByLabelText('Seed')).toHaveValue('0')
  })
})
