import { useEffect, useState } from 'react'
import { api, type ApiResponse } from '../lib/api'

type Health = { status: string; service: string; time: string }

type State =
  | { rag: 'amber'; label: 'Checking…'; detail: string }
  | { rag: 'green'; label: string; detail: string }
  | { rag: 'red'; label: 'Unreachable'; detail: string }

export default function Dashboard() {
  const [state, setState] = useState<State>({
    rag: 'amber',
    label: 'Checking…',
    detail: 'GET /api/health',
  })

  useEffect(() => {
    const controller = new AbortController()

    api
      .get<ApiResponse<Health>>('/health', { signal: controller.signal })
      .then(({ data }) => {
        if (!data.success) throw new Error(data.error.message)
        setState({
          rag: 'green',
          label: data.data.status,
          detail: `${data.data.service} · ${data.data.time}`,
        })
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        setState({
          rag: 'red',
          label: 'Unreachable',
          detail: error instanceof Error ? error.message : 'Unknown request failure',
        })
      })

    return () => controller.abort()
  }, [])

  return (
    <section
      className="rounded-lg border p-5"
      style={{
        background: 'var(--bg-surface)',
        borderColor: 'var(--border-default)',
      }}
    >
      <h1 className="text-sm font-medium">Backend health</h1>
      <div className="mt-3 flex items-center gap-2">
        <RagDot rag={state.rag} />
        <span className="font-medium">{state.label}</span>
      </div>
      <p className="mt-2 font-num text-sm tabular-nums" style={{ color: 'var(--text-muted)' }}>
        {state.detail}
      </p>
    </section>
  )
}

function RagDot({ rag }: { rag: 'red' | 'amber' | 'green' }) {
  return (
    <span
      aria-label={rag}
      className="inline-block h-2.5 w-2.5 rounded-md"
      style={{ background: `var(--status-${rag})` }}
    />
  )
}
