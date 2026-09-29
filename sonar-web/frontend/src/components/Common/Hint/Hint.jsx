import { Info } from 'lucide-react'
import { useId, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import './Hint.css'

const GAP = 6
const MARGIN = 8

let closeShown = null

function place(anchor, bubble) {
  const target = anchor.getBoundingClientRect()
  const size = bubble.getBoundingClientRect()
  const above = target.top - GAP - size.height
  const top = above >= MARGIN ? above : target.bottom + GAP
  const centred = target.left + target.width / 2 - size.width / 2
  const left = Math.max(MARGIN, Math.min(centred, window.innerWidth - MARGIN - size.width))
  bubble.style.top = `${Math.round(top)}px`
  bubble.style.left = `${Math.round(left)}px`
}

function describe(text, items) {
  return [text, ...items.map(([term, meaning]) => `${term}: ${meaning}`)].filter(Boolean).join(' ')
}

/**
 * A small info icon that explains a label while it is hovered, focused or tapped; opening one
 * closes any other. With children, the icon follows them. `text` is a sentence or two; `items`
 * lists terms with their meanings.
 */
export default function Hint({ label, text, items = [], children }) {
  const id = useId()
  const anchor = useRef(null)
  const bubble = useRef(null)
  const [open, setOpen] = useState(false)
  const name = label ?? children

  useLayoutEffect(() => {
    if (!open) {
      return undefined
    }
    const close = () => setOpen(false)
    if (closeShown) {
      closeShown()
    }
    closeShown = close
    const update = () => place(anchor.current, bubble.current)
    const escape = (event) => {
      if (event.key === 'Escape') {
        setOpen(false)
      }
    }
    const outside = (event) => {
      if (!anchor.current.contains(event.target)) {
        setOpen(false)
      }
    }
    update()
    window.addEventListener('scroll', update, true)
    window.addEventListener('resize', update)
    document.addEventListener('keydown', escape)
    document.addEventListener('pointerdown', outside)
    return () => {
      if (closeShown === close) {
        closeShown = null
      }
      window.removeEventListener('scroll', update, true)
      window.removeEventListener('resize', update)
      document.removeEventListener('keydown', escape)
      document.removeEventListener('pointerdown', outside)
    }
  }, [open])

  const hover = (next) => (event) => {
    if (event.pointerType !== 'touch') {
      setOpen(next)
    }
  }

  return (
    <span className="Hint">
      {children !== undefined && <span className="Hint-label">{children}</span>}
      <button
        ref={anchor}
        type="button"
        className="Hint-icon"
        aria-label={`About ${name}`}
        aria-describedby={id}
        onPointerEnter={hover(true)}
        onPointerLeave={hover(false)}
        onFocus={() => setOpen(true)}
        onBlur={() => setOpen(false)}
        onClick={(event) => {
          event.stopPropagation()
          setOpen(true)
        }}
      >
        <Info size={13} aria-hidden="true" />
      </button>
      <span id={id} hidden>
        {describe(text, items)}
      </span>
      {open &&
        createPortal(
          <div ref={bubble} className="Hint-bubble" aria-hidden="true">
            {text && <p>{text}</p>}
            {items.length > 0 && (
              <ul>
                {items.map(([term, meaning]) => (
                  <li key={term}>
                    <strong>{term}:</strong> {meaning}
                  </li>
                ))}
              </ul>
            )}
          </div>,
          document.body
        )}
    </span>
  )
}
