import { Component, type ErrorInfo, type ReactNode } from 'react'
import { ErrorState } from '@shared/components/common/ErrorState'

interface ErrorBoundaryProps {
  children: ReactNode
}

interface ErrorBoundaryState {
  hasError: boolean
}

export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { hasError: false }

  static getDerivedStateFromError(): ErrorBoundaryState {
    return { hasError: true }
  }

  componentDidCatch(error: unknown, info: ErrorInfo) {
    console.error('Unhandled error in the client portal:', error, info.componentStack)
  }

  render() {
    if (this.state.hasError) {
      return (
        <div className="flex min-h-dvh items-center justify-center px-4">
          <ErrorState
            title="Something went wrong."
            description="An unexpected error occurred. Please refresh the page and try again."
            onRetry={() => window.location.reload()}
            className="max-w-md"
          />
        </div>
      )
    }
    return this.props.children
  }
}
