import axios from 'axios'
import { clearToken, getToken } from './session'

/**
 * Shared HTTP client for the EvalOS Spring Boot API.
 *
 * In dev the base URL stays relative ("/api") so Vite's proxy forwards to
 * localhost:8080. In other environments set VITE_API_BASE_URL.
 */
export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api',
  headers: { 'Content-Type': 'application/json' },
  timeout: 15_000,
})

/** Every call carries the token if there is one. Login and health are the exceptions
 *  only because they are called before one exists. */
api.interceptors.request.use((config) => {
  const token = getToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

api.interceptors.response.use(
  (response) => response,
  (error) => {
    // An expired or rejected token is dropped here rather than in each caller, so the
    // next render sees no session and the router sends the user to login. 403 is left
    // alone: it means "signed in, not allowed", which is a screen, not a logout.
    if (error.response?.status === 401) clearToken()
    if (import.meta.env.DEV) {
      const status = error.response?.status ?? 'network error'
      console.error(`[api] ${error.config?.method?.toUpperCase()} ${error.config?.url} -> ${status}`)
    }
    return Promise.reject(error)
  },
)

/** Unwraps the envelope so callers deal in data or a thrown error, never both. */
export async function unwrap<T>(request: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  const { data } = await request
  if (!data.success) throw new Error(data.error.message)
  return data.data
}

/** The standard response envelope every EvalOS endpoint returns. */
export type ApiResponse<T> =
  | { success: true; data: T }
  | { success: false; error: { code: string; message: string } }
