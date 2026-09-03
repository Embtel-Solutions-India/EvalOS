import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery } from '@tanstack/react-query'
import { KeyRound, Pencil } from 'lucide-react'
import { useState } from 'react'
import { Controller, useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { Badge } from '@shared/components/ui/badge'
import { Button } from '@shared/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@shared/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@shared/components/ui/dialog'
import { Input } from '@shared/components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@shared/components/ui/select'
import { FormField } from '@shared/components/common/FormField'
import { PageHeader } from '@shared/components/common/PageHeader'
import { COUNTRIES } from '@/constants/countries'
import { useAuth } from '@/hooks/useAuth'
import { listRequests } from '@/services/intakeService'
import {
  changePasswordSchema,
  profileEditSchema,
  type ChangePasswordFormValues,
  type ProfileEditFormValues,
} from '@/schemas/profile'
import { formatDate } from '@shared/utils/formatters'

const CLIENT_TYPE_LABELS: Record<string, string> = {
  individual: 'Individual',
  employer: 'Employer',
  attorney: 'Attorney / Law Firm',
  organization: 'Organization',
  other: 'Other',
}

export default function Profile() {
  const { user, updateUser } = useAuth()
  const { data: requests } = useQuery({ queryKey: ['requests'], queryFn: listRequests })
  const [editOpen, setEditOpen] = useState(false)
  const [passwordOpen, setPasswordOpen] = useState(false)

  // Occupation / employer aren't collected on every service's questionnaire
  // (education-only evaluations never ask), so this shows whatever the
  // most recent request happened to capture, if any.
  const latestProfessionalAnswers = requests?.[0]?.answers

  const editForm = useForm<ProfileEditFormValues>({
    resolver: zodResolver(profileEditSchema),
    values: {
      firstName: user?.firstName ?? '',
      lastName: user?.lastName ?? '',
      phone: user?.phone ?? '',
      countryOfResidence: user?.countryOfResidence ?? '',
    },
  })

  const passwordForm = useForm<ChangePasswordFormValues>({
    resolver: zodResolver(changePasswordSchema),
    defaultValues: { currentPassword: '', newPassword: '', confirmNewPassword: '' },
  })

  function onSaveProfile(values: ProfileEditFormValues) {
    updateUser(values)
    toast.success('Profile saved successfully.')
    setEditOpen(false)
  }

  function onChangePassword() {
    passwordForm.reset()
    toast.success('Password updated successfully.')
    setPasswordOpen(false)
  }

  return (
    <div>
      <PageHeader
        title="Profile"
        description="Manage your personal information and account security."
        actions={
          <>
            <Button variant="outline" onClick={() => setPasswordOpen(true)}>
              <KeyRound className="h-4 w-4" />
              Change Password
            </Button>
            <Button onClick={() => setEditOpen(true)}>
              <Pencil className="h-4 w-4" />
              Edit Profile
            </Button>
          </>
        }
      />

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>Personal Information</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3 text-sm">
            <Row label="Full Name" value={`${user?.firstName ?? ''} ${user?.lastName ?? ''}`} />
            <Row label="Email" value={user?.email} />
            <Row label="Phone" value={user?.phone} />
            <Row label="Country" value={user?.countryOfResidence} />
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Professional Information</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3 text-sm">
            <Row label="Occupation" value={latestProfessionalAnswers?.jobTitle} />
            <Row label="Employer" value={latestProfessionalAnswers?.employerName} />
            <Row label="Client Type" value={user?.clientType ? CLIENT_TYPE_LABELS[user.clientType] : undefined} />
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Communication</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3 text-sm">
            <Row label="Email" value={user?.email} />
            <Row label="Phone" value={user?.phone} />
            <div className="flex items-center justify-between">
              <span className="text-muted-foreground">Preferred Method</span>
              <Badge variant="outline">
                {user?.preferredContactMethod
                  ? user.preferredContactMethod.charAt(0).toUpperCase() + user.preferredContactMethod.slice(1)
                  : '—'}
              </Badge>
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Security</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3 text-sm">
            <div className="flex items-center justify-between">
              <span className="text-muted-foreground">Email Verification</span>
              <Badge variant={user?.emailVerified ? 'success' : 'warning'}>
                {user?.emailVerified ? 'Verified' : 'Unverified'}
              </Badge>
            </div>
            <Row label="Member Since" value={user?.createdAt ? formatDate(user.createdAt) : undefined} />
            <Row label="Login Method" value="Email & Password" />
          </CardContent>
        </Card>
      </div>

      <Dialog open={editOpen} onOpenChange={setEditOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Edit Profile</DialogTitle>
            <DialogDescription className="sr-only">Update your personal information.</DialogDescription>
          </DialogHeader>
          <form className="space-y-5" onSubmit={editForm.handleSubmit(onSaveProfile)} noValidate>
            <div className="grid grid-cols-1 gap-5 sm:grid-cols-2">
              <FormField label="First Name" htmlFor="edit-firstName" required error={editForm.formState.errors.firstName?.message}>
                <Input id="edit-firstName" {...editForm.register('firstName')} />
              </FormField>
              <FormField label="Last Name" htmlFor="edit-lastName" required error={editForm.formState.errors.lastName?.message}>
                <Input id="edit-lastName" {...editForm.register('lastName')} />
              </FormField>
            </div>
            <FormField label="Phone Number" htmlFor="edit-phone" required error={editForm.formState.errors.phone?.message}>
              <Input id="edit-phone" type="tel" {...editForm.register('phone')} />
            </FormField>
            <FormField
              label="Country of Residence"
              htmlFor="edit-countryOfResidence"
              required
              error={editForm.formState.errors.countryOfResidence?.message}
            >
              <Controller
                name="countryOfResidence"
                control={editForm.control}
                render={({ field }) => (
                  <Select value={field.value} onValueChange={field.onChange}>
                    <SelectTrigger id="edit-countryOfResidence">
                      <SelectValue placeholder="Select country" />
                    </SelectTrigger>
                    <SelectContent>
                      {COUNTRIES.map((country) => (
                        <SelectItem key={country} value={country}>
                          {country}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                )}
              />
            </FormField>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setEditOpen(false)}>
                Cancel
              </Button>
              <Button type="submit" loading={editForm.formState.isSubmitting}>
                Save Changes
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <Dialog open={passwordOpen} onOpenChange={setPasswordOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Change Password</DialogTitle>
            <DialogDescription className="sr-only">Update your account password.</DialogDescription>
          </DialogHeader>
          <form className="space-y-5" onSubmit={passwordForm.handleSubmit(onChangePassword)} noValidate>
            <FormField label="Current Password" htmlFor="currentPassword" required error={passwordForm.formState.errors.currentPassword?.message}>
              <Input id="currentPassword" type="password" {...passwordForm.register('currentPassword')} />
            </FormField>
            <FormField label="New Password" htmlFor="newPassword" required error={passwordForm.formState.errors.newPassword?.message}>
              <Input id="newPassword" type="password" {...passwordForm.register('newPassword')} />
            </FormField>
            <FormField label="Confirm New Password" htmlFor="confirmNewPassword" required error={passwordForm.formState.errors.confirmNewPassword?.message}>
              <Input id="confirmNewPassword" type="password" {...passwordForm.register('confirmNewPassword')} />
            </FormField>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setPasswordOpen(false)}>
                Cancel
              </Button>
              <Button type="submit" loading={passwordForm.formState.isSubmitting}>
                Update Password
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  )
}

function Row({ label, value }: { label: string; value?: string }) {
  return (
    <div className="flex items-center justify-between gap-4">
      <span className="text-muted-foreground">{label}</span>
      <span className="font-medium text-foreground">{value || '—'}</span>
    </div>
  )
}
