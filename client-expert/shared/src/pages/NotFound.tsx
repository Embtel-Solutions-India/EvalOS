import { Link } from 'react-router-dom'
import { Button } from '@shared/components/ui/button'

export default function NotFound() {
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center gap-3 px-4 text-center">
      <p className="text-sm font-semibold text-primary">404</p>
      <h1 className="text-xl font-semibold tracking-tight text-foreground sm:text-2xl">Page not found</h1>
      <p className="max-w-sm text-sm text-muted-foreground">
        The page you're looking for doesn't exist or may have been moved.
      </p>
      <Button asChild className="mt-4">
        <Link to="/">Return Home</Link>
      </Button>
    </div>
  )
}
