import { X } from 'lucide-react'
import { useEffect } from 'react'
import './Drawer.css'

/** A panel that slides in from the right with details; Escape or the close button dismisses it. */
export default function Drawer({ title, subtitle, onClose, children }) {
  useEffect(() => {
    const close = (event) => {
      if (event.key === 'Escape') {
        onClose()
      }
    }
    window.addEventListener('keydown', close)
    return () => window.removeEventListener('keydown', close)
  }, [onClose])

  return (
    <div className="Drawer-backdrop" onClick={onClose}>
      <aside
        className="Drawer"
        role="dialog"
        aria-label={title}
        onClick={(event) => event.stopPropagation()}
      >
        <header className="Drawer-header">
          <div>
            <h2>{title}</h2>
            {subtitle && <div className="Drawer-subtitle">{subtitle}</div>}
          </div>
          <button type="button" className="button quiet" aria-label="Close" onClick={onClose}>
            <X size={16} />
          </button>
        </header>
        <div className="Drawer-body">{children}</div>
      </aside>
    </div>
  )
}
