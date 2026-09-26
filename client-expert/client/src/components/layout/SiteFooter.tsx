import { Link } from 'react-router-dom'
import { COMPANY, LEGAL } from '@/constants/legal'

const link = 'font-medium text-foreground underline-offset-4 hover:underline'

/**
 * The legal footer on every client-portal screen, signed in or not (2026-09-25).
 *
 * **Three short paragraphs, each ending in the page that says it in full.** A footer of bare links
 * is the one nobody reads; one sentence of what each policy actually commits to — not a law firm,
 * never sold, kept seven years — is what a client needs before they upload a transcript.
 */
export function SiteFooter() {
  return (
    <footer className="relative w-full border-t border-border px-4 py-6 text-xs text-muted-foreground sm:px-6 lg:px-8">
      <div className="mx-auto max-w-3xl space-y-3">
        <p>
          {COMPANY.name} is not a law firm and does not give legal advice. Our evaluations,
          translations and expert letters support your petition; they do not guarantee its outcome.
          See our{' '}
          <Link to={LEGAL.disclaimer.to} className={link}>
            {LEGAL.disclaimer.label}
          </Link>
          .
        </p>
        <p>
          We use your information only to deliver the service you asked for, and we never sell it.
          See our{' '}
          <Link to={LEGAL.privacy.to} className={link}>
            {LEGAL.privacy.label}
          </Link>
          .
        </p>
        <p>
          We keep case documents for seven years so we can stand behind our work, and delete copies
          of government ID within 90 days of delivery. See our{' '}
          <Link to={LEGAL.retention.to} className={link}>
            {LEGAL.retention.label}
          </Link>
          .
        </p>

        <nav aria-label="Legal" className="flex flex-wrap gap-x-4 gap-y-1 pt-1">
          {Object.values(LEGAL).map((page) => (
            <Link key={page.to} to={page.to} className={link}>
              {page.label}
            </Link>
          ))}
        </nav>
        <p>
          © {new Date().getFullYear()} {COMPANY.name} · {COMPANY.street}, {COMPANY.city} ·{' '}
          <a href={`mailto:${COMPANY.email}`} className={link}>
            {COMPANY.email}
          </a>
        </p>
      </div>
    </footer>
  )
}
