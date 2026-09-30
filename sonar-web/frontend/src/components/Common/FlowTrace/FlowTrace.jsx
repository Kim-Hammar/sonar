import { ChevronRight } from 'lucide-react'
import StatusBadge from '../StatusBadge/StatusBadge'
import './FlowTrace.css'

/** The paths traced flows took through the network, hop by hop, each ending in its disposition. */
export default function FlowTrace({ flows }) {
  if (!flows || flows.length === 0) {
    return <div className="muted">No traces</div>
  }
  return (
    <div className="FlowTrace">
      {flows.map((flow, index) => (
        <div key={index} className="FlowTrace-flow">
          {flow.hops.map((hop, hopIndex) => (
            <span key={hopIndex} className="FlowTrace-hop">
              {hopIndex > 0 && <ChevronRight size={12} className="muted" />}
              <span className="mono">{hop}</span>
            </span>
          ))}
          <StatusBadge
            status={flow.disposition === 'accepted' ? 'fixed' : 'unresolved'}
            label={flow.disposition}
          />
        </div>
      ))}
    </div>
  )
}
