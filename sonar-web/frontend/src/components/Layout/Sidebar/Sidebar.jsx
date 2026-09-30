import { ListChecks, Network, Plus, Radar } from 'lucide-react'
import { Link, useLocation } from 'react-router'
import { useInfo } from '../../Common/info'
import './Sidebar.css'

const ITEMS = [
  {
    to: '/executions',
    label: 'Agent executions',
    icon: ListChecks,
    active: (path) => path.startsWith('/executions') && path !== '/executions/new'
  },
  {
    to: '/scenarios',
    label: 'Scenarios',
    icon: Network,
    active: (path) => path.startsWith('/scenarios')
  }
]

/** The left navigation: a button to start an agent execution, the pages, and the server's versions. */
export default function Sidebar() {
  const { pathname } = useLocation()
  const info = useInfo()
  return (
    <nav className="Sidebar" aria-label="Main">
      <Link to="/executions" className="Sidebar-brand">
        <Radar size={20} strokeWidth={2.2} />
        <span>Sonar</span>
      </Link>
      <Link
        to="/executions/new"
        className={pathname === '/executions/new' ? 'Sidebar-new active' : 'Sidebar-new'}
      >
        <Plus size={16} />
        New agent execution
      </Link>
      <ul className="Sidebar-items">
        {ITEMS.map((item) => {
          const Icon = item.icon
          return (
            <li key={item.to}>
              <Link
                to={item.to}
                className={item.active(pathname) ? 'Sidebar-item active' : 'Sidebar-item'}
              >
                <Icon size={16} />
                {item.label}
              </Link>
            </li>
          )
        })}
      </ul>
      <div className="Sidebar-footer">
        {info && info.demo && <div className="Sidebar-demo">Demo mode</div>}
        {info && !info.unavailable && (
          <>
            <div>Sonar {info.sonar}</div>
            <div>Batfish {info.batfish}</div>
          </>
        )}
        {info && info.unavailable && <div>Server unavailable</div>}
      </div>
    </nav>
  )
}
