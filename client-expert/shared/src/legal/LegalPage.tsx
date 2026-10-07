import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { Logo } from '@shared/components/common/Logo'
import { COMPANY, LEGAL_UPDATED } from './legal'

/**
 * The shell for the three legal pages: public, outside `PortalLayout`, because a policy a client
 * relies on has to be readable before they first sign in.
 *
 * Content is JSX rather than a Markdown renderer: three static pages do not earn a parser
 * dependency, and JSX keeps the links to each other real `<Link>`s.
 */
export function LegalPage({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="flex min-h-dvh flex-col">
      <header className="px-4 py-6 sm:px-6 lg:px-8">
        <Link to="/" aria-label="Back to the client portal">
          <Logo />
        </Link>
      </header>
      <main className="flex-1 px-4 pb-12 sm:px-6 lg:px-8">
        <article className="mx-auto max-w-3xl space-y-4 text-sm leading-relaxed text-foreground [&_h2]:pt-4 [&_h2]:text-base [&_h2]:font-semibold [&_li]:ml-5 [&_li]:list-disc [&_ul]:space-y-1">
          <div>
            <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
            <p className="mt-1 text-muted-foreground">
              {COMPANY.name} · Last updated: {LEGAL_UPDATED}
            </p>
          </div>
          {children}
        </article>
      </main>
    </div>
  )
}

/** The "Contact us" block every policy ends with. */
export function ContactBlock() {
  return (
    <address className="not-italic">
      <strong>{COMPANY.name}</strong>
      <br />
      {COMPANY.street}
      <br />
      {COMPANY.city}
      <br />
      Phone: {COMPANY.phone}
      <br />
      Email:{' '}
      <a href={`mailto:${COMPANY.email}`} className="text-primary underline-offset-4 hover:underline">
        {COMPANY.email}
      </a>
    </address>
  )
}
