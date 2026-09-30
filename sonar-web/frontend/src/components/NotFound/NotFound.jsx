import { Link } from 'react-router'
import Empty from '../Common/Empty/Empty'

/** The page for addresses that do not exist. */
export default function NotFound() {
  return (
    <div className="page">
      <Empty title="Page not found" action={<Link to="/executions">Go to agent executions</Link>}>
        The address does not match any page.
      </Empty>
    </div>
  )
}
