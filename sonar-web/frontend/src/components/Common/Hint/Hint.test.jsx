import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import Disclosure from '../Disclosure/Disclosure'
import Hint from './Hint'

const bubble = () => document.body.querySelector('.Hint-bubble')

describe('Hint', () => {
  it('explains its label while the icon is hovered', async () => {
    const user = userEvent.setup()
    render(<Hint text="The seed of every random choice.">Seed</Hint>)
    const icon = screen.getByRole('button', { name: 'About Seed' })
    expect(icon).toHaveAccessibleDescription('The seed of every random choice.')
    expect(bubble()).toBeNull()
    await user.hover(icon)
    expect(bubble()).toHaveTextContent('The seed of every random choice.')
    await user.unhover(icon)
    expect(bubble()).toBeNull()
  })

  it('opens on keyboard focus, lists terms, and closes on Escape', async () => {
    const user = userEvent.setup()
    render(
      <Hint
        label="Status"
        items={[
          ['Solved', 'Every specification holds.'],
          ['Failed', 'An error ended it.']
        ]}
      />
    )
    await user.tab()
    expect(screen.getByRole('button', { name: 'About Status' })).toHaveFocus()
    expect(bubble()).toHaveTextContent('Solved: Every specification holds.')
    expect(bubble()).toHaveTextContent('Failed: An error ended it.')
    await user.keyboard('{Escape}')
    expect(bubble()).toBeNull()
  })

  it('opens on a tap without triggering what surrounds it, and a tap elsewhere closes it', async () => {
    const user = userEvent.setup()
    const onRow = vi.fn()
    render(
      <div>
        <div onClick={onRow}>
          <Hint text="Why it matters.">Remaining</Hint>
        </div>
        <p>Elsewhere</p>
      </div>
    )
    await user.pointer({
      keys: '[TouchA]',
      target: screen.getByRole('button', { name: 'About Remaining' })
    })
    expect(onRow).not.toHaveBeenCalled()
    expect(bubble()).toHaveTextContent('Why it matters.')
    await user.pointer({ keys: '[TouchA]', target: screen.getByText('Elsewhere') })
    expect(bubble()).toBeNull()
  })

  it('leaves a disclosure closed when its icon is used, while the header still toggles it', async () => {
    const user = userEvent.setup()
    render(
      <Disclosure title="Fixed" meta="3" hint={{ text: 'Violated at the start, holds now.' }}>
        <p>Body</p>
      </Disclosure>
    )
    await user.click(screen.getByRole('button', { name: 'About Fixed' }))
    expect(screen.queryByText('Body')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Fixed' }))
    expect(screen.getByText('Body')).toBeInTheDocument()
  })
})
