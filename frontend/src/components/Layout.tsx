import { Outlet } from 'react-router-dom'

export default function Layout() {
  return (
    <div className="flex min-h-svh flex-col">
      <header className="border-b" style={{ borderColor: 'var(--border-default)', background: 'var(--bg-surface)' }}>
        <div className="mx-auto flex w-full max-w-5xl items-center px-6 py-4">
          <span className="font-semibold tracking-tight">EvalOS</span>
        </div>
      </header>

      <main className="mx-auto w-full max-w-5xl flex-1 px-6 py-10">
        <Outlet />
      </main>
    </div>
  )
}
