import { useState } from 'react'
import { ChevronLeft, ChevronRight, Search, Users } from 'lucide-react'

import { Panel } from '../../components/ui/panel'
import { useMe } from '../../lib/authContext'
import { formatCount } from '../../lib/money'
import { useMetrics } from '../dashboards/useMetrics'
import { fetchContacts, sourceLabel, type ContactPage } from '../opportunities/opportunityApi'

/**
 * Everyone the CRM holds, at the width the caller's role reads.
 *
 * <p><strong>Three widths, one screen, and the screen does not know which it is showing.</strong>
 * A GM reads every brand, a Brand Manager reads its own, and a sales or marketing desk reads the
 * contacts on the deals it works. That is decided entirely by {@code ContactDirectoryService} from
 * {@code Role.tier()} — there is no brand or pipeline parameter on the request, because a width
 * the client could send is a width the client could change.
 *
 * <p><strong>The Brand column appears for the GM alone</strong>, and by asking the caller's role
 * rather than by inspecting the rows. Deriving it from "more than one distinct brand came back"
 * would hide the column on the day a GM's list happened to hold one brand, which is exactly when
 * a cross-brand reader most needs to be told which one they are looking at.
 *
 * <p><strong>Search and paging are both the server's, not a slice of what arrived.</strong> The
 * roster is four figures and grows with the CRM. Fetching it whole to show fifteen would ship the
 * entire list on every keystroke, and a client-side search would only ever search the part that
 * had been fetched — which is the failure that teaches somebody the contact is not in the system.
 *
 * <p><strong>Changing the search resets to page one.</strong> Not doing so is the classic pager
 * bug: type a narrower term while on page 4, land on an empty page, and conclude there are no
 * matches at all.
 */
/**
 * Fifteen rows a page.
 *
 * <p>Chosen to fit a laptop without scrolling the panel — the board is built for a 1366-high
 * screen and fifteen rows plus the header and the pager is what lands above the fold there.
 */
const PAGE_SIZE = 15

export default function ContactsPage() {
  const role = useMe().role
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  // Debounced by the re-fetch itself rather than by a timer: `useMetrics` aborts the in-flight
  // request whenever the inputs change, so a fast typist issues several requests and reads only
  // the last. A timer here would be a second thing to get wrong for no fewer round trips.
  const { data: found, state } = useMetrics<ContactPage>(
    (signal) => fetchContacts(search, page, PAGE_SIZE, signal),
    [search, page],
  )

  const crossBrand = role === 'GM'
  const contacts = found?.contacts
  // From the server's applied size, never from `contacts.length` — the last page is short and
  // would otherwise inflate the count.
  const pageCount = found ? Math.max(1, Math.ceil(found.total / found.size)) : 1

  function onSearch(term: string) {
    setSearch(term)
    setPage(0)
  }

  return (
    <section className="space-y-4">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">Contacts</h1>
          <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
            {scopeNote(role)}
          </p>
        </div>

        <div className="relative">
          <Search
            className="pointer-events-none absolute top-1/2 left-2.5 h-4 w-4 -translate-y-1/2"
            style={{ color: 'var(--text-muted)' }}
            aria-hidden
          />
          <input
            value={search}
            onChange={(event) => onSearch(event.target.value)}
            placeholder="Name, email or company"
            aria-label="Search contacts"
            className="field field-search w-72"
          />
        </div>
      </header>

      <Panel
        title="Everyone you can see"
        icon={<Users />}
        action={
          found && (
            <span className="text-xs font-num" style={{ color: 'var(--text-muted)' }}>
              {/* The TOTAL, not this page's length: "15" on a roster of 1,407 is a worse answer
                  than no number at all. `formatCount` because four figures reads as a code
                  without its separator. */}
              {formatCount(found.total)}
            </span>
          )
        }
      >
        {state.kind === 'error' && (
          <p className="text-sm" style={{ color: 'var(--status-red)' }}>
            {state.note}
          </p>
        )}

        {state.kind === 'loading' && (
          <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
            Loading…
          </p>
        )}

        {contacts && contacts.length === 0 && (
          <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
            {/* The two empty cases are not the same thing and must not read the same: an empty
                search is "nobody matched", an empty list is "you have nobody yet". */}
            {search.trim()
              ? `Nobody matches “${search.trim()}”.`
              : 'No contacts yet. They arrive with the deals and the portal sign-ups.'}
          </p>
        )}

        {contacts && contacts.length > 0 && (
          <div className="overflow-x-auto">
            <table className="tbl">
              <thead>
                <tr>
                  <th className="num">#</th>
                  <th>Name</th>
                  <th>Email</th>
                  <th>Phone</th>
                  <th>Company</th>
                  {crossBrand && <th>Brand</th>}
                  <th>Source</th>
                  <th>Deals</th>
                  <th>Last activity</th>
                </tr>
              </thead>
              <tbody>
                {contacts.map((contact, index) => (
                  <tr key={contact.id}>
                    {/* Numbered across the whole roster, not within the page: the second row of
                        page 3 is #17, and restarting at 1 on every page makes the column a
                        decoration rather than a position. */}
                    <td className="num">{(found?.page ?? 0) * (found?.size ?? PAGE_SIZE) + index + 1}</td>
                    <td className="font-medium">{contact.name?.trim() || '—'}</td>
                    <td>
                      {contact.email?.trim() ? (
                        <a
                          href={`mailto:${contact.email.trim()}`}
                          className="hover:underline"
                          style={{ color: 'var(--accent-primary)' }}
                        >
                          {contact.email}
                        </a>
                      ) : (
                        '—'
                      )}
                    </td>
                    <td style={{ color: 'var(--text-muted)' }} className="whitespace-nowrap">
                      {contact.phone?.trim() || '—'}
                    </td>
                    <td style={{ color: 'var(--text-muted)' }}>{contact.company?.trim() || '—'}</td>
                    {crossBrand && (
                      <td>
                        <span className="chip">{contact.brandName ?? '—'}</span>
                      </td>
                    )}
                    <td style={{ color: 'var(--text-muted)' }} className="whitespace-nowrap">
                      {sourceLabel(contact.source) ?? '—'}
                    </td>
                    <td className="font-num">{contact.dealCount}</td>
                    <td style={{ color: 'var(--text-muted)' }} className="whitespace-nowrap">
                      {/* A contact with no deal has no activity, which is a fact rather than a
                          gap: they signed up and nobody has sold them anything yet. */}
                      {contact.lastActivityAt
                        ? new Date(contact.lastActivityAt).toLocaleDateString()
                        : '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {found && found.total > 0 && pageCount > 1 && (
          <nav
            className="mt-4 flex items-center justify-between gap-3 border-t pt-3"
            style={{ borderColor: 'var(--border-default)' }}
            aria-label="Contacts pages"
          >
            <p className="text-xs font-num" style={{ color: 'var(--text-muted)' }}>
              {/* The row range rather than just the page number: "31–45 of 1,407" tells a reader
                  where they are in the roster, which "page 3" does not. */}
              {found.page * found.size + 1}–{found.page * found.size + (contacts?.length ?? 0)} of{' '}
              {formatCount(found.total)}
            </p>

            <div className="flex items-center gap-2">
              <button
                type="button"
                className="btn"
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                disabled={found.page === 0}
              >
                <ChevronLeft className="h-3.5 w-3.5" aria-hidden />
                Previous
              </button>
              <span className="text-xs font-num" style={{ color: 'var(--text-muted)' }}>
                Page {found.page + 1} of {formatCount(pageCount)}
              </span>
              <button
                type="button"
                className="btn"
                onClick={() => setPage((p) => p + 1)}
                disabled={found.page + 1 >= pageCount}
              >
                Next
                <ChevronRight className="h-3.5 w-3.5" aria-hidden />
              </button>
            </div>
          </nav>
        )}
      </Panel>
    </section>
  )
}

/**
 * What this reader is looking at, said on the screen.
 *
 * <p>Stated rather than left implicit, because the three widths are indistinguishable from inside
 * one of them: a desk seeing forty contacts cannot tell whether that is the business or their own
 * pipelines, and the difference changes what they conclude from a name being absent.
 */
function scopeNote(role: string): string {
  switch (role) {
    case 'GM':
      return 'Every contact in the business, across all brands.'
    case 'BRAND_MANAGER':
      return 'Every contact in your brand.'
    default:
      return 'The contacts on the deals in your pipelines.'
  }
}
