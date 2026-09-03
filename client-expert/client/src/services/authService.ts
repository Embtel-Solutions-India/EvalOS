import { STORAGE_KEYS } from '@shared/constants/storage'
import { mockDelay } from '@shared/mock/mockDelay'
import type { User } from '@/types'
import { readStorage, removeStorage, writeStorage } from '@shared/utils/storage'

// Mock authentication service. Every export mirrors the shape of a future
// REST call (`/api/auth/*`) so this file is the only place that needs to
// change when a real backend is connected — components and AuthContext
// consume the same function signatures either way.
//
// Registered accounts (email, a password hash, and the user record) are
// kept in their own localStorage entry, separate from the currently
// logged-in session — this is what lets us reject login for an email that
// was never registered, and reject registering an email twice, the same
// way a real backend would.

export class AuthError extends Error {}

export interface LoginPayload {
  email: string
  password: string
}

export interface RegisterPayload {
  firstName: string
  lastName: string
  email: string
  phone?: string
  password: string
}

interface StoredAccount {
  user: User
  passwordHash: string
}

function normalizeEmail(email: string): string {
  return email.trim().toLowerCase()
}

async function hashPassword(password: string): Promise<string> {
  const bytes = new TextEncoder().encode(password)
  const digest = await crypto.subtle.digest('SHA-256', bytes)
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('')
}

function readAccounts(): StoredAccount[] {
  return readStorage<StoredAccount[]>(STORAGE_KEYS.accounts, [])
}

function writeAccounts(accounts: StoredAccount[]): void {
  writeStorage(STORAGE_KEYS.accounts, accounts)
}

function findAccount(email: string): StoredAccount | undefined {
  const target = normalizeEmail(email)
  return readAccounts().find((account) => normalizeEmail(account.user.email) === target)
}

export async function login(payload: LoginPayload): Promise<User> {
  await mockDelay(700)

  const account = findAccount(payload.email)
  if (!account) {
    throw new AuthError('No account found with this email. Please create an account first.')
  }

  const passwordHash = await hashPassword(payload.password)
  if (passwordHash !== account.passwordHash) {
    throw new AuthError('Incorrect password. Please try again.')
  }

  writeStorage(STORAGE_KEYS.authUser, account.user)
  writeStorage(STORAGE_KEYS.isAuthenticated, true)
  return account.user
}

export async function register(payload: RegisterPayload): Promise<User> {
  await mockDelay(800)

  if (findAccount(payload.email)) {
    throw new AuthError('An account with this email already exists. Please log in instead.')
  }

  const user: User = {
    id: `client-${Date.now()}`,
    firstName: payload.firstName,
    lastName: payload.lastName,
    email: payload.email,
    phone: payload.phone,
    emailVerified: false,
    profileCompleted: false,
    createdAt: new Date().toISOString(),
  }
  const passwordHash = await hashPassword(payload.password)

  writeAccounts([...readAccounts(), { user, passwordHash }])
  writeStorage(STORAGE_KEYS.authUser, user)
  writeStorage(STORAGE_KEYS.isAuthenticated, true)
  writeStorage(STORAGE_KEYS.pendingVerificationEmail, payload.email)
  return user
}

export async function logout(): Promise<void> {
  await mockDelay(200)
  removeStorage(STORAGE_KEYS.isAuthenticated)
}

function syncAccountUser(updated: User): void {
  const target = normalizeEmail(updated.email)
  const accounts = readAccounts()
  const index = accounts.findIndex((account) => normalizeEmail(account.user.email) === target)
  if (index === -1) return
  accounts[index] = { ...accounts[index], user: updated }
  writeAccounts(accounts)
}

export async function verifyEmail(): Promise<User | null> {
  await mockDelay(600)
  const user = readStorage<User | null>(STORAGE_KEYS.authUser, null)
  if (!user) return null
  const updated: User = { ...user, emailVerified: true }
  writeStorage(STORAGE_KEYS.authUser, updated)
  syncAccountUser(updated)
  removeStorage(STORAGE_KEYS.pendingVerificationEmail)
  return updated
}

export async function resendVerificationEmail(): Promise<void> {
  await mockDelay(500)
}

export async function requestPasswordReset(_email: string): Promise<void> {
  await mockDelay(700)
}

export function getStoredUser(): User | null {
  return readStorage<User | null>(STORAGE_KEYS.authUser, null)
}

export function isSessionAuthenticated(): boolean {
  return readStorage<boolean>(STORAGE_KEYS.isAuthenticated, false)
}

export function updateStoredUser(updates: Partial<User>): User | null {
  const user = getStoredUser()
  if (!user) return null
  const updated = { ...user, ...updates }
  writeStorage(STORAGE_KEYS.authUser, updated)
  syncAccountUser(updated)
  return updated
}
