import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import './index.css'
import App from './App.tsx'

/**
 * The root, and nothing else. The router provider lives here so `App` renders without a second
 * one; `AuthProvider` deliberately does **not** — `App` mounts it around the staff surface only,
 * because the client portal must run with no staff session at all (Unit 14).
 */
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
)
