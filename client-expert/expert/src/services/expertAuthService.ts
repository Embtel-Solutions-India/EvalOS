import { STORAGE_KEYS } from '@shared/constants/storage'
import { DEMO_EXPERT } from '@/mock/expertMockData'
import { mockDelay } from '@shared/mock/mockDelay'
import type { ExpertUser } from '@/types/expert'
import { readStorage, removeStorage, writeStorage } from '@shared/utils/storage'

// A separate, self-contained mock account system for the Expert Portal.
// Experts are onboarded by International Evaluations staff rather than
// self-registering, so there's no public sign-up flow here — instead a
// single demo account is seeded automatically the first time this module
// loads, and its credentials are shown right on the Expert Login page.

export class ExpertAuthError extends Error {}

export interface ExpertLoginPayload {
  email: string
  password: string
}

interface StoredExpertAccount {
  user: ExpertUser
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

function readAccounts(): StoredExpertAccount[] {
  return readStorage<StoredExpertAccount[]>(STORAGE_KEYS.expertAccounts, [])
}

function writeAccounts(accounts: StoredExpertAccount[]): void {
  writeStorage(STORAGE_KEYS.expertAccounts, accounts)
}

async function ensureDemoAccountSeeded(): Promise<void> {
  const accounts = readAccounts()
  if (accounts.some((account) => normalizeEmail(account.user.email) === normalizeEmail(DEMO_EXPERT.user.email))) return
  const passwordHash = await hashPassword(DEMO_EXPERT.password)
  writeAccounts([...accounts, { user: DEMO_EXPERT.user, passwordHash }])
}

// Fire-and-forget at module load — by the time the login page's form can
// actually be submitted, this has long since resolved.
void ensureDemoAccountSeeded()

export async function login(payload: ExpertLoginPayload): Promise<ExpertUser> {
  await mockDelay(700)
  await ensureDemoAccountSeeded()

  const target = normalizeEmail(payload.email)
  const account = readAccounts().find((entry) => normalizeEmail(entry.user.email) === target)
  if (!account) {
    throw new ExpertAuthError('No expert account found with this email.')
  }

  const passwordHash = await hashPassword(payload.password)
  if (passwordHash !== account.passwordHash) {
    throw new ExpertAuthError('Incorrect password. Please try again.')
  }

  writeStorage(STORAGE_KEYS.expertAuthUser, account.user)
  writeStorage(STORAGE_KEYS.expertIsAuthenticated, true)
  return account.user
}

export async function logout(): Promise<void> {
  await mockDelay(200)
  removeStorage(STORAGE_KEYS.expertIsAuthenticated)
}

export function getStoredExpert(): ExpertUser | null {
  return readStorage<ExpertUser | null>(STORAGE_KEYS.expertAuthUser, null)
}

export function isExpertSessionAuthenticated(): boolean {
  return readStorage<boolean>(STORAGE_KEYS.expertIsAuthenticated, false)
}
