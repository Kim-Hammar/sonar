import { Outlet } from 'react-router'
import Sidebar from '../Sidebar/Sidebar'
import './Layout.css'

/** The frame of every page: the sidebar on the left, the page on the right, and the copyright. */
export default function Layout() {
  return (
    <div className="Layout">
      <Sidebar />
      <div className="Layout-column">
        <main className="Layout-main">
          <Outlet />
        </main>
        <footer className="Layout-footer">
          Copyright © 2026 Kim Hammar (
          <a href="mailto:k.hammar@imperial.ac.uk">k.hammar@imperial.ac.uk</a>)
        </footer>
      </div>
    </div>
  )
}
