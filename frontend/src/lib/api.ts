import axios from 'axios'

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

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (import.meta.env.DEV) {
      const status = error.response?.status ?? 'network error'
      console.error(`[api] ${error.config?.method?.toUpperCase()} ${error.config?.url} -> ${status}`)
    }
    return Promise.reject(error)
  },
)

/** The standard response envelope every EvalOS endpoint returns. */
export type ApiResponse<T> =
  | { success: true; data: T }
  | { success: false; error: { code: string; message: string } }
