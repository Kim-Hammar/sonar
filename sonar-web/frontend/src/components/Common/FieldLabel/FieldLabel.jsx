import Hint from '../Hint/Hint'
import './FieldLabel.css'

/** A form label followed by an info icon that explains the field; `children` is the label text. */
export default function FieldLabel({ htmlFor, hint, children }) {
  return (
    <span className="FieldLabel">
      <label htmlFor={htmlFor}>{children}</label>
      {hint && <Hint label={children} {...hint} />}
    </span>
  )
}
