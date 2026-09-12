export const STORAGE_KEYS = {
  accounts: 'ie_portal.mockAccounts',
  authUser: 'ie_portal.mockUser',
  isAuthenticated: 'ie_portal.mockAuth',
  intakeDraft: 'ie_portal.intakeDraft',
  requests: 'ie_portal.requests',
  notifications: 'ie_portal.notifications',
  notificationPreferences: 'ie_portal.notificationPreferences',
  pendingVerificationEmail: 'ie_portal.pendingVerificationEmail',

  // Expert Portal — deliberately separate from the client keys above; expert
  // accounts and client accounts are two distinct systems that never mix.
  expertAccounts: 'ie_portal.expertAccounts',
  expertAuthUser: 'ie_portal.expertAuthUser',
  expertIsAuthenticated: 'ie_portal.expertIsAuthenticated',
  expertCases: 'ie_portal.expertCases',
} as const
