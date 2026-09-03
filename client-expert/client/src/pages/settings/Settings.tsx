import { useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@shared/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@shared/components/ui/card'
import { Switch } from '@shared/components/ui/switch'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@shared/components/ui/tabs'
import { PageHeader } from '@shared/components/common/PageHeader'
import { useAuth } from '@/hooks/useAuth'
import { getNotificationPreferences, saveNotificationPreferences } from '@/services/notificationService'
import type { NotificationPreferences } from '@/types'
import { formatDate } from '@shared/utils/formatters'

const NOTIFICATION_LABELS: { key: keyof NotificationPreferences; label: string; description: string }[] = [
  { key: 'emailNotifications', label: 'Email Notifications', description: 'Receive portal updates by email.' },
  { key: 'applicationUpdates', label: 'Application Updates', description: 'Status changes to your applications.' },
  { key: 'documentUpdates', label: 'Document Updates', description: 'Changes to your document review status.' },
  { key: 'paymentNotifications', label: 'Payment Notifications', description: 'Payment confirmations and receipts.' },
  { key: 'reportNotifications', label: 'Report Notifications', description: 'When a new evaluation report is ready.' },
  { key: 'supportNotifications', label: 'Support Notifications', description: 'Replies to your messages and tickets.' },
]

export default function Settings() {
  const { user } = useAuth()
  const [preferences, setPreferences] = useState<NotificationPreferences>(getNotificationPreferences)
  const [isSaving, setIsSaving] = useState(false)

  async function togglePreference(key: keyof NotificationPreferences, value: boolean) {
    const updated = { ...preferences, [key]: value }
    setPreferences(updated)
    setIsSaving(true)
    try {
      await saveNotificationPreferences(updated)
      toast.success('Notification preferences updated.')
    } finally {
      setIsSaving(false)
    }
  }

  return (
    <div>
      <PageHeader title="Settings" description="Manage your account, security and notification preferences." />

      <Tabs defaultValue="account">
        <TabsList>
          <TabsTrigger value="account">Account</TabsTrigger>
          <TabsTrigger value="security">Security</TabsTrigger>
          <TabsTrigger value="notifications">Notifications</TabsTrigger>
          <TabsTrigger value="privacy">Privacy</TabsTrigger>
        </TabsList>

        <TabsContent value="account">
          <Card>
            <CardHeader>
              <CardTitle>Account</CardTitle>
              <CardDescription>Your basic account information.</CardDescription>
            </CardHeader>
            <CardContent className="space-y-3 text-sm">
              <div className="flex justify-between">
                <span className="text-muted-foreground">Name</span>
                <span className="font-medium text-foreground">{user?.firstName} {user?.lastName}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-muted-foreground">Email</span>
                <span className="font-medium text-foreground">{user?.email}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-muted-foreground">Member Since</span>
                <span className="font-medium text-foreground">{user?.createdAt ? formatDate(user.createdAt) : '—'}</span>
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="security">
          <Card>
            <CardHeader>
              <CardTitle>Security</CardTitle>
              <CardDescription>Manage how you sign in to your account.</CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="flex items-center justify-between rounded-lg border p-4">
                <div>
                  <p className="text-sm font-medium text-foreground">Password</p>
                  <p className="text-xs text-muted-foreground">Change your account password from your profile page.</p>
                </div>
                <Button variant="outline" size="sm" asChild>
                  <a href="/profile">Manage</a>
                </Button>
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="notifications">
          <Card>
            <CardHeader>
              <CardTitle>Notification Preferences</CardTitle>
              <CardDescription>Choose what you'd like to be notified about.</CardDescription>
            </CardHeader>
            <CardContent className="divide-y">
              {NOTIFICATION_LABELS.map((item) => (
                <div key={item.key} className="flex items-center justify-between gap-4 py-4 first:pt-0 last:pb-0">
                  <div>
                    <p className="text-sm font-medium text-foreground">{item.label}</p>
                    <p className="text-xs text-muted-foreground">{item.description}</p>
                  </div>
                  <Switch
                    checked={preferences[item.key]}
                    disabled={isSaving}
                    onCheckedChange={(checked) => void togglePreference(item.key, checked)}
                    aria-label={item.label}
                  />
                </div>
              ))}
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="privacy">
          <Card>
            <CardHeader>
              <CardTitle>Privacy</CardTitle>
              <CardDescription>How your information is handled within the client portal.</CardDescription>
            </CardHeader>
            <CardContent className="space-y-3 text-sm text-muted-foreground">
              <p>
                Your documents and personal information are stored securely and are only accessible to you and the
                International Evaluations team assigned to your case.
              </p>
              <p>
                For questions about how your data is used, please contact our support team from the Support
                Tickets page.
              </p>
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  )
}
