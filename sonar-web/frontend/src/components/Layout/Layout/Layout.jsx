import { Outlet } from 'react-router'
import Sidebar from '../Sidebar/Sidebar'
import './Layout.css'

/** The frame of every page: the sidebar on the left and the page on the right. */
export default function Layout() {
  return (
    <div className="Layout">
      <Sidebar />
      <main className="Layout-main">
        <Outlet />
      </main>
    </div>
  )
}
