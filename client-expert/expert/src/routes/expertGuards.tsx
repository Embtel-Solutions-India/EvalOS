import { Navigate, Outlet } from 'react-router-dom'
import { useExpertAuth } from '@/hooks/useExpertAuth'

export function ExpertPublicRoute() {
  const { isAuthenticated } = useExpertAuth()
  if (isAuthenticated) {
    return <Navigate to="/expert" replace />
  }
  return <Outlet />
}

export function ExpertAuthenticatedRoute() {
  const { isAuthenticated } = useExpertAuth()
  if (!isAuthenticated) {
    return <Navigate to="/expert/login" replace />
  }
  return <Outlet />
}
