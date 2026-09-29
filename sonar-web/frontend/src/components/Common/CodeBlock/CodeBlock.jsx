import './CodeBlock.css'

/** Pretty-prints JSON text when it parses, and returns other text unchanged. */
export function prettyJson(text) {
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    return text
  }
}

/** Monospace text with optional line numbers and marked lines, scrolled within a fixed height. */
export default function CodeBlock({ text, numbered = false, marked = new Set(), maxHeight = 420 }) {
  const lines = text.split('\n')
  return (
    <pre className="CodeBlock" style={{ maxHeight }}>
      {lines.map((line, index) => (
        <div
          key={index}
          className={marked.has(index + 1) ? 'CodeBlock-line marked' : 'CodeBlock-line'}
        >
          {numbered && <span className="CodeBlock-number">{index + 1}</span>}
          <span>{line || ' '}</span>
        </div>
      ))}
    </pre>
  )
}
