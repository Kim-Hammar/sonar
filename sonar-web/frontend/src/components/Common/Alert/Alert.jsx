import './Alert.css'

/** A message about something that went wrong, shown until the page changes. */
export default function Alert({ children, tone = 'bad' }) {
  if (!children) {
    return null
  }
  return (
    <div className={`Alert ${tone}`} role="alert">
      {children}
    </div>
  )
}
