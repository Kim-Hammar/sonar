import './DiffView.css'

function header(edit) {
  switch (edit.op) {
    case 'delete':
      return `delete lines ${edit.first}–${edit.last}`
    case 'replace':
      return `replace lines ${edit.first}–${edit.last}`
    case 'insert':
      return `insert after line ${edit.after}`
    default:
      return 'append'
  }
}

/** The line edits of a candidate, grouped by router, with added lines marked. */
export default function DiffView({ edits }) {
  if (!edits || edits.length === 0) {
    return <div className="muted">No edits</div>
  }
  return (
    <div className="DiffView">
      {edits.map((edit, index) => (
        <div key={index} className="DiffView-edit">
          <div className="DiffView-header">
            <span className="mono">{edit.router}</span>
            <span className="muted">{header(edit)}</span>
          </div>
          {edit.op === 'delete' && (
            <div className="DiffView-line removed">{`− lines ${edit.first}–${edit.last}`}</div>
          )}
          {edit.op === 'replace' && (
            <div className="DiffView-line removed">{`− lines ${edit.first}–${edit.last}`}</div>
          )}
          {(edit.lines || []).map((line, lineIndex) => (
            <div key={lineIndex} className="DiffView-line added">
              {`+ ${line}`}
            </div>
          ))}
        </div>
      ))}
    </div>
  )
}
