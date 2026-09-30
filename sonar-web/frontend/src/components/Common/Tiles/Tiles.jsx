import Hint from '../Hint/Hint'
import './Tiles.css'

/**
 * Headline numbers, one tile each. A tile names a `label` with an optional `hint`, a `value` and
 * an optional `detail` line beneath it.
 */
export default function Tiles({ tiles }) {
  return (
    <div className="Tiles">
      {tiles.map((tile) => (
        <div key={tile.label} className="Tiles-tile card">
          <div className="Tiles-label">
            {tile.hint ? <Hint {...tile.hint}>{tile.label}</Hint> : tile.label}
          </div>
          <div className="Tiles-value">{tile.value}</div>
          {tile.detail && <div className="Tiles-detail">{tile.detail}</div>}
        </div>
      ))}
    </div>
  )
}
