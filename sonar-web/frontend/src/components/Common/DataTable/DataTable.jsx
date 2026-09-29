import './DataTable.css'

/**
 * A plain table. Columns name a header and how to render a cell; rows become clickable when
 * `onRowClick` is given.
 */
export default function DataTable({
  columns,
  rows,
  rowKey,
  onRowClick,
  empty = 'Nothing to show',
  selected
}) {
  if (rows.length === 0) {
    return <div className="DataTable-empty">{empty}</div>
  }
  return (
    <div className="DataTable-frame">
      <table className="DataTable">
        <thead>
          <tr>
            {columns.map((column) => (
              <th key={column.id} className={column.align === 'right' ? 'right' : undefined}>
                {column.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const key = rowKey(row)
            return (
              <tr
                key={key}
                className={[onRowClick ? 'clickable' : '', key === selected ? 'selected' : ''].join(
                  ' '
                )}
                onClick={onRowClick ? () => onRowClick(row) : undefined}
              >
                {columns.map((column) => (
                  <td key={column.id} className={column.align === 'right' ? 'right' : undefined}>
                    {column.cell(row)}
                  </td>
                ))}
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
