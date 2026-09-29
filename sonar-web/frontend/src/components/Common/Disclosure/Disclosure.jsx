import { ChevronRight } from 'lucide-react'
import { useState } from 'react'
import Hint from '../Hint/Hint'
import './Disclosure.css'

/**
 * A titled section that stays closed until opened, so details never clutter the view. The whole
 * header toggles it, except the info icon that an optional `hint` adds after the title.
 */
export default function Disclosure({ title, meta, hint, defaultOpen = false, children }) {
  const [open, setOpen] = useState(defaultOpen)
  return (
    <section className={open ? 'Disclosure open' : 'Disclosure'}>
      <div className="Disclosure-header">
        <button
          type="button"
          className="Disclosure-toggle"
          aria-expanded={open}
          onClick={() => setOpen(!open)}
        >
          <ChevronRight size={14} className="Disclosure-chevron" />
          <span className="Disclosure-title">{title}</span>
        </button>
        {hint && <Hint label={title} {...hint} />}
        {meta && <span className="Disclosure-meta">{meta}</span>}
      </div>
      {open && <div className="Disclosure-body">{children}</div>}
    </section>
  )
}
