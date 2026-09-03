import { useState } from 'react'
import { CheckCircle2, FileSignature } from 'lucide-react'
import { toast } from 'sonner'
import { Badge } from '@shared/components/ui/badge'
import { Button } from '@shared/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@shared/components/ui/card'
import { Switch } from '@shared/components/ui/switch'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@shared/components/ui/tabs'
import { PageHeader } from '@shared/components/common/PageHeader'
import { useExpertAuth } from '@/hooks/useExpertAuth'
import { formatDate } from '@shared/utils/formatters'

export default function ExpertProfile() {
  const { expert } = useExpertAuth()
  const [acceptingCases, setAcceptingCases] = useState(true)

  if (!expert) return null

  return (
    <div>
      <PageHeader title="Profile" description="Your expertise, availability, and payment details." />

      <Tabs defaultValue="expertise">
        <TabsList>
          <TabsTrigger value="expertise">Expertise</TabsTrigger>
          <TabsTrigger value="availability">Availability</TabsTrigger>
          <TabsTrigger value="agreement">Agreement</TabsTrigger>
          <TabsTrigger value="payment">Payment Details</TabsTrigger>
        </TabsList>

        <TabsContent value="expertise">
          <Card>
            <CardHeader>
              <CardTitle>Expertise</CardTitle>
              <CardDescription>Your name, title, and areas of expertise as shown to International Evaluations staff.</CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="flex justify-between text-sm">
                <span className="text-muted-foreground">Name</span>
                <span className="font-medium text-foreground">{expert.firstName} {expert.lastName}</span>
              </div>
              <div className="flex justify-between text-sm">
                <span className="text-muted-foreground">Title</span>
                <span className="font-medium text-foreground">{expert.title}</span>
              </div>
              <div className="flex justify-between text-sm">
                <span className="text-muted-foreground">Email</span>
                <span className="font-medium text-foreground">{expert.email}</span>
              </div>
              <div>
                <p className="mb-2 text-sm text-muted-foreground">Areas of Expertise</p>
                <div className="flex flex-wrap gap-2">
                  {expert.expertiseAreas.map((area) => (
                    <Badge key={area} variant="default">{area}</Badge>
                  ))}
                </div>
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="availability">
          <Card>
            <CardHeader>
              <CardTitle>Availability</CardTitle>
              <CardDescription>Control whether you're currently being assigned new cases.</CardDescription>
            </CardHeader>
            <CardContent>
              <div className="flex items-center justify-between rounded-lg border p-4">
                <div>
                  <p className="text-sm font-medium text-foreground">Accepting New Cases</p>
                  <p className="text-xs text-muted-foreground">Turn this off if you need to pause new assignments.</p>
                </div>
                <Switch
                  checked={acceptingCases}
                  onCheckedChange={(checked) => {
                    setAcceptingCases(checked)
                    toast.success(checked ? 'You are now accepting new cases.' : 'New case assignments paused.')
                  }}
                  aria-label="Accepting new cases"
                />
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="agreement">
          <Card>
            <CardHeader>
              <CardTitle>Expert Services Agreement</CardTitle>
              <CardDescription>Your current agreement with International Evaluations.</CardDescription>
            </CardHeader>
            <CardContent>
              <div className="flex items-center gap-3 rounded-lg border border-success/30 bg-success/5 p-4">
                <CheckCircle2 className="h-5 w-5 shrink-0 text-success" />
                <div className="flex-1">
                  <p className="text-sm font-semibold text-foreground">Agreement signed</p>
                  <p className="text-sm text-muted-foreground">Effective {formatDate(expert.createdAt)}</p>
                </div>
                <Button variant="outline" size="sm" onClick={() => toast.info('Agreement preview is a mock in this development phase.')}>
                  <FileSignature className="h-4 w-4" />
                  View
                </Button>
              </div>
            </CardContent>
          </Card>
        </TabsContent>

        <TabsContent value="payment">
          <Card>
            <CardHeader>
              <CardTitle>Payment Details</CardTitle>
              <CardDescription>Where your case payments are sent.</CardDescription>
            </CardHeader>
            <CardContent className="space-y-3">
              <div className="flex justify-between text-sm">
                <span className="text-muted-foreground">Payout Method</span>
                <span className="font-medium text-foreground">Bank Transfer</span>
              </div>
              <div className="flex justify-between text-sm">
                <span className="text-muted-foreground">Account</span>
                <span className="font-medium text-foreground">•••• 4821</span>
              </div>
              <div className="flex justify-between text-sm">
                <span className="text-muted-foreground">Payout Schedule</span>
                <span className="font-medium text-foreground">Weekly, Fridays</span>
              </div>
              <Button variant="outline" size="sm" onClick={() => toast.info('Updating payment details is a mock in this development phase.')}>
                Update Payment Details
              </Button>
            </CardContent>
          </Card>
        </TabsContent>
      </Tabs>
    </div>
  )
}
