import { ChevronRight } from 'lucide-react'
import { useState } from 'react'
import './Disclosure.css'

/** A titled section that stays closed until opened, so details never clutter the view. */
export default function Disclosure({ title, meta, defaultOpen = false, children }) {
  const [open, setOpen] = useState(defaultOpen)
  return (
    <section className={open ? 'Disclosure open' : 'Disclosure'}>
      <button
        type="button"
        className="Disclosure-toggle"
        aria-expanded={open}
        onClick={() => setOpen(!open)}
      >
        <ChevronRight size={14} className="Disclosure-chevron" />
        <span className="Disclosure-title">{title}</span>
        {meta && <span className="Disclosure-meta">{meta}</span>}
      </button>
      {open && <div className="Disclosure-body">{children}</div>}
    </section>
  )
}
