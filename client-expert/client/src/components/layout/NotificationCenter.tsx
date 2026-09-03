import { Bell, CreditCard, FileCheck2, FileText, LifeBuoy } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Badge } from '@shared/components/ui/badge'
import { Button } from '@shared/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from '@shared/components/ui/dropdown-menu'
import { listNotifications, markAllNotificationsRead } from '@/services/notificationService'
import type { AppNotification, NotificationCategory } from '@/types'
import { relativeTime } from '@shared/utils/formatters'
import { cn } from '@shared/utils/cn'

const CATEGORY_ICON: Record<NotificationCategory, typeof Bell> = {
  application: FileCheck2,
  document: FileText,
  payment: CreditCard,
  report: FileCheck2,
  support: LifeBuoy,
}

export function NotificationCenter() {
  const [notifications, setNotifications] = useState<AppNotification[]>([])
  const [open, setOpen] = useState(false)

  useEffect(() => {
    void listNotifications().then(setNotifications)
  }, [])

  const unreadCount = notifications.filter((notification) => !notification.read).length

  async function handleOpenChange(nextOpen: boolean) {
    setOpen(nextOpen)
    if (nextOpen && unreadCount > 0) {
      const updated = await markAllNotificationsRead()
      setNotifications(updated)
    }
  }

  return (
    <DropdownMenu open={open} onOpenChange={handleOpenChange}>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon" className="relative" aria-label="Notifications">
          <Bell className="h-5 w-5" />
          {unreadCount > 0 && (
            <span className="absolute right-1.5 top-1.5 h-2 w-2 rounded-full bg-destructive" aria-hidden="true" />
          )}
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-80 p-0">
        <div className="flex items-center justify-between border-b px-4 py-3">
          <p className="text-sm font-semibold">Notifications</p>
          {unreadCount > 0 && <Badge variant="info">{unreadCount} new</Badge>}
        </div>
        <div className="max-h-96 overflow-y-auto">
          {notifications.length === 0 && (
            <p className="px-4 py-8 text-center text-sm text-muted-foreground">You're all caught up.</p>
          )}
          {notifications.map((notification) => {
            const Icon = CATEGORY_ICON[notification.category]
            return (
              <div
                key={notification.id}
                className={cn('flex gap-3 border-b px-4 py-3 last:border-0', !notification.read && 'bg-accent/40')}
              >
                <span className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
                  <Icon className="h-4 w-4" />
                </span>
                <div className="min-w-0">
                  <p className="text-sm font-medium text-foreground">{notification.title}</p>
                  <p className="mt-0.5 text-sm text-muted-foreground">{notification.message}</p>
                  <p className="mt-1 text-xs text-muted-foreground">{relativeTime(notification.createdAt)}</p>
                </div>
              </div>
            )
          })}
        </div>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
