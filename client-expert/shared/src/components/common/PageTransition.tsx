import { useLocation, Outlet } from 'react-router-dom'

// Wraps a layout's <Outlet/> so every route change gets a consistent,
// subtle fade + rise instead of an abrupt swap. Keyed by pathname so each
// route mounts a fresh element and the CSS entry animation replays.
export function PageTransition() {
  const location = useLocation()
  return (
    <div key={location.pathname} className="animate-page-enter">
      <Outlet />
    </div>
  )
}
