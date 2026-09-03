import { STORAGE_KEYS } from '@shared/constants/storage'
import { mockDelay } from '@shared/mock/mockDelay'
import type { AppNotification, NotificationPreferences } from '@/types'
import { readStorage, writeStorage } from '@shared/utils/storage'

const DEFAULT_NOTIFICATIONS: AppNotification[] = [
  {
    id: 'note-001',
    category: 'application',
    title: 'Application status updated',
    message: 'Your application IE-2026-000001 is now in evaluation.',
    createdAt: '2026-08-18T14:30:00.000Z',
    read: false,
  },
  {
    id: 'note-002',
    category: 'document',
    title: 'Document requires attention',
    message: 'Your academic transcript is under review.',
    createdAt: '2026-08-19T09:00:00.000Z',
    read: false,
  },
  {
    id: 'note-003',
    category: 'payment',
    title: 'Payment successful',
    message: 'Your payment of $225.00 was received.',
    createdAt: '2026-06-02T09:21:00.000Z',
    read: true,
  },
  {
    id: 'note-004',
    category: 'report',
    title: 'Evaluation report ready',
    message: 'Your evaluation report for IE-2026-000042 is ready to view.',
    createdAt: '2026-05-02T10:05:00.000Z',
    read: true,
  },
  {
    id: 'note-005',
    category: 'support',
    title: 'Support replied to your ticket',
    message: 'International Evaluations Support replied to TCK-10021.',
    createdAt: '2026-08-19T10:30:00.000Z',
    read: false,
  },
]

const DEFAULT_PREFERENCES: NotificationPreferences = {
  emailNotifications: true,
  applicationUpdates: true,
  documentUpdates: true,
  paymentNotifications: true,
  reportNotifications: true,
  supportNotifications: true,
}

export async function listNotifications(): Promise<AppNotification[]> {
  await mockDelay(300)
  return readStorage<AppNotification[]>(STORAGE_KEYS.notifications, DEFAULT_NOTIFICATIONS)
}

export async function markAllNotificationsRead(): Promise<AppNotification[]> {
  const notifications = await listNotifications()
  const updated = notifications.map((notification) => ({ ...notification, read: true }))
  writeStorage(STORAGE_KEYS.notifications, updated)
  return updated
}

export function getNotificationPreferences(): NotificationPreferences {
  return readStorage<NotificationPreferences>(STORAGE_KEYS.notificationPreferences, DEFAULT_PREFERENCES)
}

export async function saveNotificationPreferences(
  preferences: NotificationPreferences,
): Promise<NotificationPreferences> {
  await mockDelay(400)
  writeStorage(STORAGE_KEYS.notificationPreferences, preferences)
  return preferences
}
