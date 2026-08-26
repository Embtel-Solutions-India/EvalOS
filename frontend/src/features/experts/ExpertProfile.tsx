import { useCallback, useEffect, useState } from 'react'
import { useMe } from '../../lib/authContext'
import { formatMoney } from '../../lib/money'
import { useFilters } from '../shell/filtersContext'
import { createExpert, fetchExpert, putPaymentDetail, setAvailability, updateExpert } from './expertApi'
import {
  AVAILABILITIES,
  AVAILABILITY_TOKEN,
  FIELD_TAGS,
  LETTER_TYPES,
  TIERS,
  label,
  type Availability,
  type ExpertForm,
  type ExpertProfile as Profile,
  type FieldTag,
  type LetterType,
} from './expertRules'

/**
 * One expert: what is on file, and the form that changes it.
 *
 * **Field tags and letter types are multi-selects over the closed vocabulary, never text
 * inputs.** That is the whole reason the vocabulary is closed: Unit 12 matches on these tags
 * by equality, and a free-text box is how "mechanical engg" gets pasted out of the old
 * spreadsheet. The list has to be visible for the ENM to pick from it.
 *
 * **The payment detail is write-only.** The panel shows whether one is on file and offers a
 * box to set a new one. It never shows the value — there is no endpoint that returns it, not
 * even for the person who typed it.
 *
 * Agreement status, payment status, response time and payments pending are shown and not
 * editable: Units 12, 15 and 16 own them. A roster edit that could flip "agreement signed"
 * would be a way to claim a signature nobody gave.
 */
export default function ExpertProfile({
  expertId,
  mayWrite,
  onSaved,
  onClose,
}: {
  /** An id, or `new` for the create form. */
  expertId: string | 'new'
  /**
   * Whether this role may maintain the roster (`ExpertController.ROSTER_WRITE`). False for a
   * Project Manager, who reads the roster to know who they are picking from.
   *
   * Passed in rather than re-derived from `useMe()` here, so one place decides it and the
   * roster's "Add an expert" button and this panel's form cannot disagree. Without it, a PM
   * got a live Save button on every control below and lost the edit to a 403 — the same
   * failure Units 09 and 10 were each reviewed for, a client offering what the server will
   * refuse.
   */
  mayWrite: boolean
  onSaved: () => void
  onClose: () => void
}) {
  const me = useMe()
  const { activeBrandId } = useFilters()
  const creating = expertId === 'new'
  const [profile, setProfile] = useState<Profile | null>(null)
  const [form, setForm] = useState<ExpertForm>(EMPTY_FORM)
  const [state, setState] = useState<'loading' | 'ready' | 'saving'>(creating ? 'ready' : 'loading')
  const [failure, setFailure] = useState<string | null>(null)
  const [detail, setDetail] = useState('')

  useEffect(() => {
    if (creating) return
    const controller = new AbortController()
    fetchExpert(expertId, controller.signal)
      .then((loaded) => {
        setProfile(loaded)
        setForm(formOf(loaded))
        setState('ready')
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        setFailure(error instanceof Error ? error.message : 'Could not load this expert')
        setState('ready')
      })
    return () => controller.abort()
  }, [creating, expertId])

  const save = useCallback(
    async (event: React.FormEvent) => {
      event.preventDefault()
      setState('saving')
      setFailure(null)
      try {
        const saved = creating
          ? await createExpert(activeBrandId, form)
          : await updateExpert(expertId, form)
        setProfile(saved)
        setForm(formOf(saved))
        onSaved()
        if (creating) onClose()
      } catch (error: unknown) {
        setFailure(error instanceof Error ? error.message : 'Could not save this expert')
      } finally {
        setState('ready')
      }
    },
    [activeBrandId, creating, expertId, form, onClose, onSaved],
  )

  const changeAvailability = useCallback(
    async (availability: Availability) => {
      if (creating) return setForm((current) => ({ ...current, availability }))
      try {
        const saved = await setAvailability(expertId, availability)
        setProfile(saved)
        setForm(formOf(saved))
        onSaved()
      } catch (error: unknown) {
        setFailure(error instanceof Error ? error.message : 'Could not set availability')
      }
    },
    [creating, expertId, onSaved],
  )

  const savePaymentDetail = useCallback(async () => {
    if (creating || !detail.trim()) return
    try {
      setProfile(await putPaymentDetail(expertId, detail.trim()))
      setDetail('')
      onSaved()
    } catch (error: unknown) {
      setFailure(error instanceof Error ? error.message : 'Could not set the payment detail')
    }
  }, [creating, detail, expertId, onSaved])

  if (state === 'loading') {
    return (
      <aside className="rounded-lg border p-4" style={PANEL}>
        <p className="text-sm" style={{ color: 'var(--text-muted)' }}>
          Loading the expert…
        </p>
      </aside>
    )
  }

  const brandMissing = creating && me.role === 'GM' && !activeBrandId

  return (
    <aside className="rounded-lg border p-4" style={PANEL}>
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-base font-semibold tracking-tight">
            {creating ? 'Add an expert' : (profile?.expert.fullName ?? 'Expert')}
          </h2>
          {!creating && profile && (
            <p className="mt-0.5 text-sm" style={{ color: 'var(--text-muted)' }}>
              {[profile.expert.title, profile.expert.institution].filter(Boolean).join(' · ') ||
                'No title or institution on file'}
            </p>
          )}
        </div>
        <button
          type="button"
          onClick={onClose}
          className="rounded-md px-2.5 py-1 text-sm font-medium"
          style={{ background: 'var(--bg-raised)' }}
        >
          Close
        </button>
      </header>

      {brandMissing && (
        <p className="mt-3 text-sm" style={{ color: 'var(--status-amber)' }}>
          Pick a brand in the header first — an expert belongs to one roster, and you read both.
        </p>
      )}

      {!creating && profile && (
        <>
          <dl className="font-num mt-4 grid grid-cols-2 gap-x-4 gap-y-2 text-sm tabular-nums sm:grid-cols-3">
            <Fact term="Open cases" value={String(profile.expert.activeLoad)} />
            <Fact term="Completed" value={String(profile.expert.completedCases)} />
            <Fact
              term="Quality"
              value={profile.expert.qualityScore == null ? '—' : `${profile.expert.qualityScore} / 10`}
            />
            <Fact term="Standard fee" value={money(profile.expert.standardFee)} />
            <Fact term="Email" value={profile.expert.email ?? '—'} />
            <Fact term="Phone" value={profile.expert.phone ?? '—'} />
            <Fact term="Agreement" value={label(profile.agreementStatus)} />
            <Fact term="Payment status" value={label(profile.paymentStatus)} />
            <Fact
              term="Avg response"
              value={profile.avgResponseHours == null ? '—' : `${profile.avgResponseHours} h`}
            />
          </dl>
          {/* Open cases are counted from the cases themselves; the columns V7 created for
              this have never been written. Said out loud because a number that looks stale
              is the first thing somebody distrusts. */}
          <p className="mt-1 text-xs" style={{ color: 'var(--text-muted)' }}>
            Case counts are live from the cases. Payments pending and response time are Unit 16's and
            Unit 12's figures and read zero until those land.
          </p>

          <section className="mt-4">
            <h3 className="text-sm font-semibold tracking-tight">Availability</h3>
            {mayWrite ? (
              <div className="mt-1.5 flex flex-wrap gap-1.5">
                {AVAILABILITIES.map((availability) => {
                  const active = profile.expert.availability === availability
                  return (
                    <button
                      key={availability}
                      type="button"
                      aria-pressed={active}
                      onClick={() => void changeAvailability(availability)}
                      className="rounded-md px-2 py-1 text-xs font-semibold"
                      style={
                        active
                          ? {
                              color: AVAILABILITY_TOKEN[availability].fg,
                              background: AVAILABILITY_TOKEN[availability].bg,
                            }
                          : { color: 'var(--text-muted)', background: 'var(--bg-raised)' }
                      }
                    >
                      {label(availability)}
                    </button>
                  )
                })}
              </div>
            ) : (
              // A reader gets the state, not four buttons that answer 403.
              <p className="mt-1.5 text-sm">
                <span
                  className="rounded-md px-1.5 py-0.5 text-xs font-semibold"
                  style={
                    profile.expert.availability
                      ? {
                          color: AVAILABILITY_TOKEN[profile.expert.availability].fg,
                          background: AVAILABILITY_TOKEN[profile.expert.availability].bg,
                        }
                      : { color: 'var(--text-muted)', background: 'var(--bg-raised)' }
                  }
                >
                  {label(profile.expert.availability)}
                </span>
              </p>
            )}
            <p className="mt-1 text-xs" style={{ color: 'var(--text-muted)' }}>
              Only an available expert can be put on a case, so anything else takes them out of the
              assignment picker.
            </p>
          </section>

          <section className="mt-4">
            <h3 className="text-sm font-semibold tracking-tight">Payment detail</h3>
            <p className="mt-1 text-sm">
              <span
                className="rounded-md px-1.5 py-0.5 text-xs font-semibold"
                style={
                  profile.expert.paymentDetailOnFile
                    ? { color: 'var(--status-green)', background: 'var(--status-green-bg)' }
                    : { color: 'var(--text-muted)', background: 'var(--bg-raised)' }
                }
              >
                {profile.expert.paymentDetailOnFile ? 'On file' : 'Not on file'}
              </span>
            </p>
            {mayWrite && (
              <p className="mt-1.5 text-xs" style={{ color: 'var(--text-muted)' }}>
                Encrypted, and never shown again — not here and not to anyone. To correct it, type the
                whole value.
              </p>
            )}
            {mayWrite && (
              <div className="mt-1.5 flex gap-2">
                <input
                  type="text"
                  value={detail}
                  aria-label="New payment detail"
                  placeholder="how this expert is paid"
                  onChange={(event) => setDetail(event.target.value)}
                  className="flex-1 rounded-md border px-2.5 py-1.5 text-sm"
                  style={INPUT_STYLE}
                />
                <button
                  type="button"
                  disabled={!detail.trim()}
                  onClick={() => void savePaymentDetail()}
                  className="rounded-md px-3 py-1.5 text-sm font-medium text-white disabled:opacity-60"
                  style={{ background: 'var(--accent-primary)' }}
                >
                  Save
                </button>
              </div>
            )}
          </section>
        </>
      )}

      {/* No form at all for a reader. Disabling the fields would still invite the work; the
          facts above are what a Project Manager came here for. */}
      {!mayWrite && (
        <p className="mt-5 text-xs" style={{ color: 'var(--text-muted)' }}>
          Read-only: the Expert Network Manager maintains this roster.
        </p>
      )}

      {mayWrite && (
      <form className="mt-5 flex flex-col gap-3" onSubmit={(event) => void save(event)}>
        <h3 className="text-sm font-semibold tracking-tight">
          {creating ? 'Details' : 'Edit the profile'}
        </h3>

        <div className="grid gap-3 sm:grid-cols-2">
          <Text label="Full name" required value={form.fullName} onChange={(v) => set(setForm, 'fullName', v)} />
          <Text label="Email" type="email" value={form.email ?? ''} onChange={(v) => set(setForm, 'email', v)} />
          <Text label="Phone" value={form.phone ?? ''} onChange={(v) => set(setForm, 'phone', v)} />
          <Text label="Title" value={form.title ?? ''} onChange={(v) => set(setForm, 'title', v)} />
          <Text
            label="Institution"
            value={form.institution ?? ''}
            onChange={(v) => set(setForm, 'institution', v)}
          />
          <Text
            label="Recruitment source"
            value={form.recruitmentSource ?? ''}
            onChange={(v) => set(setForm, 'recruitmentSource', v)}
          />
          {/* A native date input rather than a picker library: the platform has one. */}
          <Text
            label="Date onboarded"
            type="date"
            value={form.dateOnboarded ?? ''}
            onChange={(v) => set(setForm, 'dateOnboarded', v)}
          />
          <Text
            label="Quality score (1–10)"
            type="number"
            step="0.1"
            min="1"
            max="10"
            value={form.qualityScore == null ? '' : String(form.qualityScore)}
            onChange={(v) => setForm((c) => ({ ...c, qualityScore: v === '' ? null : Number(v) }))}
          />
          <Text
            label="Standard fee"
            type="number"
            step="0.01"
            min="0"
            value={form.standardFee == null ? '' : String(form.standardFee)}
            onChange={(v) => setForm((c) => ({ ...c, standardFee: v === '' ? null : Number(v) }))}
          />
          <Choice
            label="Tier"
            options={TIERS}
            value={form.tier ?? ''}
            onChange={(v) => setForm((c) => ({ ...c, tier: v === '' ? null : (v as (typeof TIERS)[number]) }))}
          />
          {/* No "Not set" option: the server coerces a missing availability to AVAILABLE, so
              offering the blank would be the screen and the server disagreeing about what
              happens next. Tier keeps its blank — nothing defaults that. */}
          <Choice
            label="Availability"
            options={AVAILABILITIES}
            required
            value={form.availability ?? 'AVAILABLE'}
            onChange={(v) =>
              setForm((c) => ({ ...c, availability: v === '' ? null : (v as Availability) }))
            }
          />
        </div>

        <Tags
          label="Primary field tags"
          options={FIELD_TAGS}
          value={form.primaryFields}
          onChange={(v) => setForm((c) => ({ ...c, primaryFields: v as FieldTag[] }))}
        />
        <Tags
          label="Secondary field tags"
          options={FIELD_TAGS}
          value={form.secondaryFields}
          onChange={(v) => setForm((c) => ({ ...c, secondaryFields: v as FieldTag[] }))}
        />
        <Tags
          label="Letter types"
          options={LETTER_TYPES}
          value={form.letterTypes}
          onChange={(v) => setForm((c) => ({ ...c, letterTypes: v as LetterType[] }))}
        />

        <div>
          <label className="block text-xs font-medium" htmlFor="expert-notes">
            Notes
          </label>
          <textarea
            id="expert-notes"
            rows={3}
            value={form.notes ?? ''}
            onChange={(event) => set(setForm, 'notes', event.target.value)}
            className="mt-1 w-full rounded-md border px-2.5 py-1.5 text-sm"
            style={INPUT_STYLE}
          />
        </div>

        {failure && (
          <p className="text-sm font-medium" style={{ color: 'var(--status-red)' }}>
            {failure}
          </p>
        )}

        <div>
          <button
            type="submit"
            disabled={state === 'saving' || brandMissing}
            className="rounded-md px-3 py-1.5 text-sm font-medium text-white disabled:opacity-60"
            style={{ background: 'var(--accent-primary)' }}
          >
            {state === 'saving' ? 'Saving…' : creating ? 'Add the expert' : 'Save changes'}
          </button>
        </div>
      </form>
      )}

      {/* A read failure still has to be visible to a reader, who has no form to show it in. */}
      {!mayWrite && failure && (
        <p className="mt-2 text-sm font-medium" style={{ color: 'var(--status-red)' }}>
          {failure}
        </p>
      )}
    </aside>
  )
}

const PANEL = { background: 'var(--bg-surface)', borderColor: 'var(--border-default)' }
const INPUT_STYLE = { background: 'var(--bg-base)', borderColor: 'var(--border-default)' }

const EMPTY_FORM: ExpertForm = {
  fullName: '',
  email: null,
  phone: null,
  title: null,
  institution: null,
  primaryFields: [],
  secondaryFields: [],
  letterTypes: [],
  tier: null,
  // A new expert is available unless the ENM says otherwise: adding somebody to the roster
  // is saying they can be worked with.
  availability: 'AVAILABLE',
  qualityScore: null,
  standardFee: null,
  recruitmentSource: null,
  dateOnboarded: null,
  notes: null,
}

function formOf(profile: Profile): ExpertForm {
  return {
    fullName: profile.expert.fullName ?? '',
    email: profile.expert.email,
    phone: profile.expert.phone,
    title: profile.expert.title,
    institution: profile.expert.institution,
    primaryFields: profile.expert.primaryFields,
    secondaryFields: profile.expert.secondaryFields,
    letterTypes: profile.expert.letterTypes,
    tier: profile.expert.tier,
    availability: profile.expert.availability,
    qualityScore: profile.expert.qualityScore,
    standardFee: profile.expert.standardFee,
    recruitmentSource: profile.recruitmentSource,
    dateOnboarded: profile.dateOnboarded,
    notes: profile.notes,
  }
}

/** Blank means "nothing on file", which the server stores as null rather than an empty string. */
function set(
  setForm: React.Dispatch<React.SetStateAction<ExpertForm>>,
  field: 'fullName' | 'email' | 'phone' | 'title' | 'institution' | 'recruitmentSource' | 'dateOnboarded' | 'notes',
  value: string,
) {
  setForm((current) => ({ ...current, [field]: field === 'fullName' ? value : value || null }))
}

/**
 * Delegates to the shared formatter, and only adds the one thing it cannot know: that a missing
 * fee is '—' rather than $0. Those are different facts about an expert.
 *
 * This used to format independently — `1,250.00`, no symbol, two decimals, default locale —
 * while the board and Revenue dashboard rendered the same kind of figure as `$1,250`. Same
 * figure class, two renderings, and `money.ts` claims to be the one place a figure becomes text.
 */
function money(value: number | null): string {
  return value == null ? '—' : formatMoney(value)
}

function Fact({ term, value }: { term: string; value: string }) {
  return (
    <div>
      <dt className="text-[10px] font-medium tracking-[0.06em] uppercase" style={{ color: 'var(--text-muted)' }}>
        {term}
      </dt>
      <dd className="truncate text-sm" title={value}>
        {value}
      </dd>
    </div>
  )
}

function Text({
  label: text,
  value,
  onChange,
  type = 'text',
  required,
  ...rest
}: {
  label: string
  value: string
  onChange: (value: string) => void
  type?: string
  required?: boolean
  step?: string
  min?: string
  max?: string
}) {
  const id = `expert-${text.toLowerCase().replace(/[^a-z]/g, '-')}`
  return (
    <div>
      <label className="block text-xs font-medium" htmlFor={id}>
        {text}
        {required && <span aria-hidden> *</span>}
      </label>
      <input
        id={id}
        type={type}
        required={required}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className="mt-1 w-full rounded-md border px-2.5 py-1.5 text-sm"
        style={INPUT_STYLE}
        {...rest}
      />
    </div>
  )
}

function Choice({
  label: text,
  options,
  value,
  onChange,
  required,
}: {
  label: string
  options: readonly string[]
  value: string
  onChange: (value: string) => void
  /** Drops the blank option, for a field the server will fill in if this one does not. */
  required?: boolean
}) {
  const id = `expert-${text.toLowerCase().replace(/[^a-z]/g, '-')}`
  return (
    <div>
      <label className="block text-xs font-medium" htmlFor={id}>
        {text}
      </label>
      <select
        id={id}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className="mt-1 w-full rounded-md border px-2.5 py-1.5 text-sm"
        style={INPUT_STYLE}
      >
        {!required && <option value="">Not set</option>}
        {options.map((option) => (
          <option key={option} value={option}>
            {label(option)}
          </option>
        ))}
      </select>
    </div>
  )
}

/**
 * A multiple-select over the closed vocabulary.
 *
 * Native `<select multiple>` rather than a tag-input component: the whole requirement is
 * that the ENM cannot type a value the API would reject, and the platform control that only
 * offers legal options is the shortest way to guarantee it.
 */
function Tags({
  label: text,
  options,
  value,
  onChange,
}: {
  label: string
  options: readonly string[]
  value: readonly string[]
  onChange: (value: string[]) => void
}) {
  const id = `expert-${text.toLowerCase().replace(/[^a-z]/g, '-')}`
  return (
    <div>
      <label className="block text-xs font-medium" htmlFor={id}>
        {text}
        <span className="ml-1 font-normal" style={{ color: 'var(--text-muted)' }}>
          (pick from the list — hold ctrl or cmd for several)
        </span>
      </label>
      <select
        id={id}
        multiple
        size={6}
        value={value as string[]}
        onChange={(event) =>
          onChange(Array.from(event.target.selectedOptions).map((option) => option.value))
        }
        className="mt-1 w-full rounded-md border px-2.5 py-1.5 text-sm"
        style={INPUT_STYLE}
      >
        {options.map((option) => (
          <option key={option} value={option}>
            {label(option)}
          </option>
        ))}
      </select>
    </div>
  )
}
