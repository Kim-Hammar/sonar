import './PageHeader.css'

/** The top of a page: a title, optional details below it, and actions on the right. */
export default function PageHeader({ eyebrow, title, meta, actions }) {
  return (
    <header className="PageHeader">
      <div className="PageHeader-text">
        {eyebrow && <div className="PageHeader-eyebrow">{eyebrow}</div>}
        <h1>{title}</h1>
        {meta && <div className="PageHeader-meta">{meta}</div>}
      </div>
      {actions && <div className="PageHeader-actions">{actions}</div>}
    </header>
  )
}
