import { Link } from 'react-router-dom'
import { COMPANY, LEGAL } from './legal'

const link = 'font-medium text-foreground underline-offset-4 hover:underline'

/**
 * The three policies in one sentence each (2026-09-25), shared by the footer and the first-sign-in
 * acceptance screen (Unit 72), so what a person accepts is word for word what the footer says.
 *
 * @param newTab open the policy in a new tab — the acceptance screen must not be navigated away from
 */
export function PolicySummary({ className, newTab = false }: { className?: string; newTab?: boolean }) {
  const target = newTab ? { target: '_blank', rel: 'noopener noreferrer' } : {}
  return (
    <div className={className}>
      <p>
        {COMPANY.name} is not a law firm and does not give legal advice. Our evaluations, translations and expert
        letters support your petition; they do not guarantee its outcome. See our{' '}
        <Link to={LEGAL.disclaimer.to} className={link} {...target}>
          {LEGAL.disclaimer.label}
        </Link>
        .
      </p>
      <p>
        We use your information only to deliver the service you asked for, and we never sell it. See our{' '}
        <Link to={LEGAL.privacy.to} className={link} {...target}>
          {LEGAL.privacy.label}
        </Link>
        .
      </p>
      <p>
        We keep case documents for seven years so we can stand behind our work, and delete copies of government ID
        within 90 days of delivery. See our{' '}
        <Link to={LEGAL.retention.to} className={link} {...target}>
          {LEGAL.retention.label}
        </Link>
        .
      </p>
    </div>
  )
}
