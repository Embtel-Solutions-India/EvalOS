import type { ReactNode } from 'react'
import signInArt from '@shared/assets/portal-signin.jpg'

/**
 * The client and expert sign-in screens (Unit 72): the form on the left, the portal's artwork on the
 * right half. The artwork is decoration — `alt=""` — and is dropped below `lg` so the form fits a phone.
 * Its subject sits on the picture's right, so `object-right` keeps it in view however narrow the half.
 */
export function AuthSplit({ children }: { children: ReactNode }) {
  return (
    <div className="flex flex-1 lg:grid lg:grid-cols-2">
      <div className="flex flex-1 flex-col">{children}</div>
      <div className="relative hidden bg-[#0b1f4d] lg:block">
        <img src={signInArt} alt="" className="absolute inset-0 h-full w-full object-cover object-right" />
      </div>
    </div>
  )
}
