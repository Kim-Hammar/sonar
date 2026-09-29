import './Empty.css'

/** A calm placeholder for a page or panel with nothing to show yet. */
export default function Empty({ title, children, action }) {
  return (
    <div className="Empty">
      <h2>{title}</h2>
      {children && <p className="muted">{children}</p>}
      {action}
    </div>
  )
}
