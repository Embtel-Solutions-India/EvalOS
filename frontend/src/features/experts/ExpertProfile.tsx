import { useCallback, useEffect, useState } from 'react'
import { ArrowLeft, Check, SquarePen } from 'lucide-react'
import { SheetContent, SheetRoot } from '../../components/ui/dialog'
import { useMe } from '../../lib/authContext'
import { formatMoney } from '../../lib/money'
import { useFilters } from '../shell/filtersContext'
import { createExpert, fetchExpert, putPaymentDetail, setAvailability, updateExpert } from './expertApi'
import {
  AFFILIATION_TYPES,
  AVAILABILITIES,
  AVAILABILITY_TOKEN,
  FIELD_TAGS,
  LETTER_TYPES,
  TIERS,
  initials,
  label,
  VISA_CATEGORIES,
  type AffiliationType,
  type Availability,
  type Dossier,
  type ExpertForm,
  type ExpertProfile as Profile,
  type FieldTag,
  type LetterType,
  type VisaCategory,
} from './expertRules'

/**
 * One expert: what is on file, and the form that changes it.
 *
 * **A sheet, not a panel under the table** (redesigned 2026-08-28). It was an `<aside>`
 * appended below the roster, which meant opening an expert pushed the list around under you
 * and the way back was a Close button somewhere off the bottom of the screen. A sheet is what
 * `ui-context.md` already prescribes for *inspecting a record without losing your place*, and
 * it arrives with the focus trap, the escape key and the entry animation already written — so
 * the list keeps its search, filters, page and scroll position for free, because it is never
 * unmounted.
 *
 * The one thing that costs: this component only mounts while an expert is open, so the sheet
 * animates in and then *disappears* on close rather than sliding out — the exit keyframe needs
 * the element to survive its own removal. The fix is Radix's `forceMount` plus keeping the last
 * id in state, which is real state carried for an animation; worth it only if the missing slide
 * out is ever noticed.
 *
 * **Read first, edit on request.** The facts and the fifteen-field form used to be stacked on
 * one screen, so every reader paid for the form. `mode` now starts at `view` and the form sits
 * behind *Edit profile*; a save returns to `view` with a confirmation rather than closing, so
 * the answer to "did that take?" is the screen already in front of you.
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

  /**
   * The id writes go to. `expertId` is a prop and stays `'new'` after a create, so the id the
   * server assigned has to be kept here — otherwise a second Save on an expert who was just
   * added would POST a duplicate instead of updating the one on screen. This is what lets the
   * create flow finish on the new expert's profile rather than closing the sheet.
   */
  const [createdId, setCreatedId] = useState<string | null>(null)
  const targetId = createdId ?? expertId
  const creating = targetId === 'new'

  const [profile, setProfile] = useState<Profile | null>(null)
  const [form, setForm] = useState<ExpertForm>(EMPTY_FORM)
  const [state, setState] = useState<'loading' | 'ready' | 'saving'>(
    expertId === 'new' ? 'ready' : 'loading',
  )
  const [mode, setMode] = useState<'view' | 'edit'>(expertId === 'new' ? 'edit' : 'view')
  const [failure, setFailure] = useState<string | null>(null)
  const [flash, setFlash] = useState<string | null>(null)
  const [detail, setDetail] = useState('')

  // Keyed on the prop, never on `creating` — that flips to false the moment a create returns,
  // and an effect watching it would re-run and fetch the expert `new`.
  useEffect(() => {
    if (expertId === 'new') return
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
  }, [expertId])

  const save = useCallback(
    async (event: React.FormEvent) => {
      event.preventDefault()
      setState('saving')
      setFailure(null)
      setFlash(null)
      try {
        const saved = creating
          ? await createExpert(activeBrandId, form)
          : await updateExpert(targetId, form)
        setProfile(saved)
        setForm(formOf(saved))
        if (creating) setCreatedId(saved.expert.id)
        // Back to the profile, not out to the list: whoever just typed fifteen fields wants to
        // see them on the record, and a sheet that vanishes on save leaves them hunting the row
        // to check it landed.
        setMode('view')
        setFlash(creating ? 'Expert added.' : 'Changes saved.')
        onSaved()
      } catch (error: unknown) {
        setFailure(error instanceof Error ? error.message : 'Could not save this expert')
      } finally {
        setState('ready')
      }
    },
    [activeBrandId, creating, form, onSaved, targetId],
  )

  const changeAvailability = useCallback(
    async (availability: Availability) => {
      if (creating) return setForm((current) => ({ ...current, availability }))
      setFailure(null)
      try {
        const saved = await setAvailability(targetId, availability)
        setProfile(saved)
        setForm(formOf(saved))
        setFlash(`Availability set to ${label(availability).toLowerCase()}.`)
        onSaved()
      } catch (error: unknown) {
        setFailure(error instanceof Error ? error.message : 'Could not set availability')
      }
    },
    [creating, onSaved, targetId],
  )

  const savePaymentDetail = useCallback(async () => {
    if (creating || !detail.trim()) return
    setFailure(null)
    try {
      setProfile(await putPaymentDetail(targetId, detail.trim()))
      setDetail('')
      setFlash('Payment detail stored.')
      onSaved()
    } catch (error: unknown) {
      setFailure(error instanceof Error ? error.message : 'Could not set the payment detail')
    }
  }, [creating, detail, onSaved, targetId])

  const editing = mode === 'edit' && mayWrite
  const brandMissing = creating && me.role === 'GM' && !activeBrandId
  const expert = profile?.expert
  const dossier = profile?.dossier

  return (
    <SheetRoot
      open
      onOpenChange={(next) => {
        if (!next) onClose()
      }}
    >
      <SheetContent
        title={creating ? 'Add an expert' : (expert?.fullName ?? 'Expert')}
        description={
          creating
            ? 'A new expert on this brand’s roster.'
            : [expert?.title, expert?.institution].filter(Boolean).join(' · ') ||
              'No title or institution on file'
        }
        footer={
          state === 'loading' ? undefined : editing ? (
            <>
              <button
                type="button"
                onClick={() => {
                  if (creating) return onClose()
                  if (profile) setForm(formOf(profile))
                  setFailure(null)
                  setMode('view')
                }}
                className="rounded-md bg-(--bg-raised) px-3 py-1.5 text-sm font-medium transition-colors hover:bg-(--border-default)"
              >
                Cancel
              </button>
              <button
                type="submit"
                form={FORM_ID}
                disabled={state === 'saving' || brandMissing}
                className="rounded-md bg-(--accent-primary) px-3 py-1.5 text-sm font-medium text-white transition-colors enabled:hover:bg-(--accent-hover) disabled:opacity-60"
              >
                {state === 'saving' ? 'Saving…' : creating ? 'Add the expert' : 'Save changes'}
              </button>
            </>
          ) : mayWrite && expert ? (
            <button
              type="button"
              onClick={() => {
                setFlash(null)
                setMode('edit')
              }}
              className="inline-flex items-center gap-1.5 rounded-md bg-(--accent-primary) px-3 py-1.5 text-sm font-medium text-white transition-colors hover:bg-(--accent-hover)"
            >
              <SquarePen className="h-4 w-4" aria-hidden />
              Edit profile
            </button>
          ) : undefined
        }
      >
        <div className="flex flex-col gap-4">
          {/* The labelled way back. The sheet's own X does the same thing, but a drawer whose
              only exit is a 16px glyph in the corner is the friction this redesign is about —
              and on a phone the sheet is the whole screen, where a back arrow is what a reader
              looks for. */}
          <button
            type="button"
            onClick={onClose}
            className="-mt-1 inline-flex w-fit items-center gap-1.5 rounded-md text-xs font-medium text-(--text-muted) transition-colors hover:text-(--accent-primary)"
          >
            <ArrowLeft className="h-4 w-4" aria-hidden />
            Back to experts
          </button>

          {flash && (
            <p
              role="status"
              className="inline-flex items-center gap-1.5 rounded-md bg-(--status-green-bg) px-2.5 py-1.5 text-sm font-medium text-(--status-green)"
            >
              <Check className="h-4 w-4" aria-hidden />
              {flash}
            </p>
          )}

          {/* One place for a failure, whatever produced it. There used to be two blocks — one
              inside the form and a second for readers, who have no form to show it in. */}
          {failure && (
            <p role="alert" className="text-sm font-medium text-(--status-red)">
              {failure}
            </p>
          )}

          {brandMissing && (
            <p className="text-sm text-(--status-amber)">
              Pick a brand in the header first — an expert belongs to one roster, and you read both.
            </p>
          )}

          {state === 'loading' && <p className="text-sm text-(--text-muted)">Loading the expert…</p>}

          {expert && (
            <div className="flex items-center gap-3 rounded-lg border border-(--border-default) bg-(--bg-base) p-3">
              {/* Identity, once. The name and the role line are the sheet's own title and
                  description directly above this, so they are not repeated here — what is left
                  is the mark standing where a photo would, the capacity badge, the tier and the
                  id. */}
              <Avatar name={expert.fullName} size="lg" />
              <div className="min-w-0">
                <AvailabilityBadge availability={expert.availability} />
                <p className="font-mono mt-1.5 truncate text-[11px] text-(--text-muted)" title={expert.id}>
                  {expert.id.slice(0, 8)}
                </p>
              </div>
              {expert.tier && (
                <span className="ml-auto rounded-md bg-(--bg-raised) px-2 py-1 text-[11px] font-semibold">
                  {label(expert.tier)}
                </span>
              )}
            </div>
          )}

          {expert && !editing && (
            <>
              <Section title="Contact">
                <Facts>
                  <Fact term="Email" value={expert.email ?? '—'} />
                  <Fact term="Phone" value={expert.phone ?? '—'} />
                  {/* US-based is preferred for USCIS letters, so where they are is a fact the
                      ENM reads, not a footnote. */}
                  <Fact term="Location" value={place(dossier)} />
                  <Fact term="Languages" value={dossier?.languages ?? '—'} />
                  <Fact term="LinkedIn" value={dossier?.linkedinUrl ?? '—'} />
                </Facts>
              </Section>

              {/* Unit 33. Everything below is on the profile and on no list: the roster table
                  stays a table, and this is what somebody opens an expert to read. */}
              <Section title="Credentials">
                <Facts>
                  <Fact term="Expert ID" value={dossier?.expertCode ?? '—'} />
                  <Fact term="Highest degree" value={dossier?.highestDegree ?? '—'} />
                  <Fact term="Degree field" value={dossier?.degreeField ?? '—'} />
                  {/* Not `institution` — that is where they work now. */}
                  <Fact term="Degree from" value={dossier?.degreeInstitution ?? '—'} />
                  <Fact term="Current position" value={dossier?.currentPosition ?? '—'} />
                  <Fact term="Affiliation" value={label(dossier?.affiliationType ?? null)} />
                  <Fact term="Experience" value={years(dossier?.yearsExperience ?? null)} numeric />
                  <Fact term="Sub-specialisation" value={dossier?.subSpecialization ?? '—'} />
                </Facts>
              </Section>

              <Section
                title="Standing"
                note="What an expert opinion letter rests on. Entered from the expert’s CV — EvalOS does not compute these."
              >
                <Facts>
                  <Fact term="Publications" value={count(dossier?.publications ?? null)} numeric />
                  <Fact term="Citations" value={count(dossier?.citations ?? null)} numeric />
                  <Fact term="h-index" value={count(dossier?.hIndex ?? null)} numeric />
                  <Fact term="Patents" value={count(dossier?.patents ?? null)} numeric />
                </Facts>
                <div className="mt-3 flex flex-col gap-2.5">
                  {/* The petitions they will write for, which is not the same list as the
                      letter types they will sign. */}
                  <TagRow term="Visa categories" values={dossier?.visaCategories ?? []} />
                </div>
                <div className="mt-3 flex flex-col gap-2.5">
                  <Prose term="Notable awards" value={dossier?.notableAwards ?? null} />
                  <Prose term="Memberships" value={dossier?.professionalMemberships ?? null} />
                  <Prose term="Editorial roles" value={dossier?.editorialRoles ?? null} />
                </div>
              </Section>

              <Section title="Professional">
                <Facts>
                  <Fact term="Tier" value={label(expert.tier)} />
                  <Fact
                    term="Quality"
                    value={expert.qualityScore == null ? '—' : `${expert.qualityScore} / 10`}
                    numeric
                  />
                  <Fact term="Standard fee" value={money(expert.standardFee)} numeric />
                  <Fact term="Onboarded" value={profile?.dateOnboarded ?? '—'} numeric />
                  <Fact term="Recruited via" value={profile?.recruitmentSource ?? '—'} />
                </Facts>
                <div className="mt-3 flex flex-col gap-2.5">
                  <TagRow term="Primary fields" values={expert.primaryFields} />
                  <TagRow term="Secondary fields" values={expert.secondaryFields} muted />
                  <TagRow term="Letter types" values={expert.letterTypes} />
                </div>
              </Section>

              {/* Open cases are counted from the cases themselves; the columns V7 created for
                  this have never been written. Said out loud because a number that looks stale
                  is the first thing somebody distrusts. */}
              <Section
                title="Assignment and workload"
                note="Case counts are live from the cases. Payments pending and response time are Unit 16’s and Unit 12’s figures and read zero until those land."
              >
                <Facts>
                  <Fact term="Open cases" value={String(expert.activeLoad)} numeric />
                  <Fact term="Completed" value={String(expert.completedCases)} numeric />
                  <Fact term="Payout pending" value={money(expert.pendingTotal)} numeric />
                  <Fact
                    term="Avg response"
                    value={profile?.avgResponseHours == null ? '—' : `${profile.avgResponseHours} h`}
                    numeric
                  />
                  {/* Not the same figure as avg response: one is how fast they answer an
                      offer, the other how long the letter takes. */}
                  <Fact term="Avg turnaround" value={days(dossier?.avgTurnaroundDays ?? null)} numeric />
                  {/* Derived from the offer table, never stored. Null is "never approached",
                      which is not a date and must not be drawn as one. */}
                  <Fact term="Last approached" value={profile?.lastActiveAt?.slice(0, 10) ?? 'Never'} numeric />
                  <Fact term="Agreement" value={label(profile?.agreementStatus ?? null)} />
                  <Fact term="Payment status" value={label(profile?.paymentStatus ?? null)} />
                </Facts>
              </Section>

              <Section
                title="Availability"
                note="Only an available expert can be put on a case, so anything else takes them out of the assignment picker."
              >
                <p className="mb-2.5 text-sm">
                  <span className="text-[10px] font-medium tracking-[0.06em] text-(--text-muted) uppercase">
                    Rush work
                  </span>{' '}
                  {dossier?.rushAvailable ? 'Takes 48-hour rush cases' : 'No rush work'}
                </p>
                {mayWrite ? (
                  <div className="flex flex-wrap gap-1.5">
                    {AVAILABILITIES.map((availability) => {
                      const active = expert.availability === availability
                      return (
                        <button
                          key={availability}
                          type="button"
                          aria-pressed={active}
                          onClick={() => void changeAvailability(availability)}
                          className="rounded-md px-2 py-1 text-xs font-semibold transition-colors"
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
                  <AvailabilityBadge availability={expert.availability} />
                )}
              </Section>

              <Section
                title="Payment detail"
                note={
                  mayWrite
                    ? 'Encrypted, and never shown again — not here and not to anyone. To correct it, type the whole value.'
                    : undefined
                }
              >
                <span
                  className="rounded-md px-1.5 py-0.5 text-xs font-semibold"
                  style={
                    expert.paymentDetailOnFile
                      ? { color: 'var(--status-green)', background: 'var(--status-green-bg)' }
                      : { color: 'var(--text-muted)', background: 'var(--bg-raised)' }
                  }
                >
                  {expert.paymentDetailOnFile ? 'On file' : 'Not on file'}
                </span>
                {mayWrite && (
                  <div className="mt-2.5 flex gap-2">
                    <input
                      type="text"
                      value={detail}
                      aria-label="New payment detail"
                      placeholder="how this expert is paid"
                      onChange={(event) => setDetail(event.target.value)}
                      className="flex-1 rounded-md border border-(--border-default) bg-(--bg-surface) px-2.5 py-1.5 text-sm"
                    />
                    <button
                      type="button"
                      disabled={!detail.trim()}
                      onClick={() => void savePaymentDetail()}
                      className="rounded-md bg-(--accent-primary) px-3 py-1.5 text-sm font-medium text-white transition-colors enabled:hover:bg-(--accent-hover) disabled:opacity-60"
                    >
                      Save
                    </button>
                  </div>
                )}
              </Section>

              {profile?.notes && (
                <Section title="Notes">
                  <p className="text-sm whitespace-pre-wrap">{profile.notes}</p>
                </Section>
              )}

              {/* No form at all for a reader. Disabling the fields would still invite the work;
                  the facts above are what a Project Manager came here for. */}
              {!mayWrite && (
                <p className="text-xs text-(--text-muted)">
                  Read-only: the Expert Network Manager maintains this roster.
                </p>
              )}
            </>
          )}

          {editing && (
            <form id={FORM_ID} className="flex flex-col gap-3" onSubmit={(event) => void save(event)}>
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
                {/* No "Not set" option: the server coerces a missing availability to AVAILABLE,
                    so offering the blank would be the screen and the server disagreeing about
                    what happens next. Tier keeps its blank — nothing defaults that. */}
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

              {/* Unit 33. Behind a <details> rather than another twenty rows always open: the
                  fifteen fields above are what an ENM edits weekly, and the CV is transcribed
                  once. Native disclosure, no accordion component. */}
              <details className="rounded-lg border border-(--border-default) px-3 py-2">
                <summary className="cursor-pointer text-xs font-medium">Credentials and standing</summary>
                <div className="mt-3 grid gap-3 sm:grid-cols-2">
                  <Text
                    label="Expert ID"
                    value={form.expertCode ?? ''}
                    onChange={(v) => set(setForm, 'expertCode', v)}
                  />
                  <Text
                    label="Sub-specialisation"
                    value={form.subSpecialization ?? ''}
                    onChange={(v) => set(setForm, 'subSpecialization', v)}
                  />
                  <Text
                    label="Highest degree"
                    value={form.highestDegree ?? ''}
                    onChange={(v) => set(setForm, 'highestDegree', v)}
                  />
                  <Text
                    label="Degree field"
                    value={form.degreeField ?? ''}
                    onChange={(v) => set(setForm, 'degreeField', v)}
                  />
                  <Text
                    label="Degree institution"
                    value={form.degreeInstitution ?? ''}
                    onChange={(v) => set(setForm, 'degreeInstitution', v)}
                  />
                  <Text
                    label="Current position"
                    value={form.currentPosition ?? ''}
                    onChange={(v) => set(setForm, 'currentPosition', v)}
                  />
                  <Choice
                    label="Affiliation type"
                    options={AFFILIATION_TYPES}
                    value={form.affiliationType ?? ''}
                    onChange={(v) =>
                      setForm((c) => ({ ...c, affiliationType: v === '' ? null : (v as AffiliationType) }))
                    }
                  />
                  <Text
                    label="Years of experience"
                    type="number"
                    min="0"
                    max="80"
                    value={form.yearsExperience == null ? '' : String(form.yearsExperience)}
                    onChange={(v) => setForm((c) => ({ ...c, yearsExperience: v === '' ? null : Number(v) }))}
                  />
                  <Text label="Country" value={form.country ?? ''} onChange={(v) => set(setForm, 'country', v)} />
                  <Text
                    label="State / region"
                    value={form.stateRegion ?? ''}
                    onChange={(v) => set(setForm, 'stateRegion', v)}
                  />
                  <Text
                    label="LinkedIn"
                    value={form.linkedinUrl ?? ''}
                    onChange={(v) => set(setForm, 'linkedinUrl', v)}
                  />
                  <Text
                    label="Languages"
                    value={form.languages ?? ''}
                    onChange={(v) => set(setForm, 'languages', v)}
                  />
                  <Text
                    label="Publications"
                    type="number"
                    min="0"
                    value={form.publications == null ? '' : String(form.publications)}
                    onChange={(v) => setForm((c) => ({ ...c, publications: v === '' ? null : Number(v) }))}
                  />
                  <Text
                    label="Citations"
                    type="number"
                    min="0"
                    value={form.citations == null ? '' : String(form.citations)}
                    onChange={(v) => setForm((c) => ({ ...c, citations: v === '' ? null : Number(v) }))}
                  />
                  <Text
                    label="h-index"
                    type="number"
                    min="0"
                    value={form.hIndex == null ? '' : String(form.hIndex)}
                    onChange={(v) => setForm((c) => ({ ...c, hIndex: v === '' ? null : Number(v) }))}
                  />
                  <Text
                    label="Patents"
                    type="number"
                    min="0"
                    value={form.patents == null ? '' : String(form.patents)}
                    onChange={(v) => setForm((c) => ({ ...c, patents: v === '' ? null : Number(v) }))}
                  />
                  <Text
                    label="Avg turnaround (days)"
                    type="number"
                    min="0"
                    max="365"
                    value={form.avgTurnaroundDays == null ? '' : String(form.avgTurnaroundDays)}
                    onChange={(v) => setForm((c) => ({ ...c, avgTurnaroundDays: v === '' ? null : Number(v) }))}
                  />
                  <label className="flex items-end gap-2 text-xs font-medium">
                    <input
                      type="checkbox"
                      checked={form.rushAvailable}
                      onChange={(event) => setForm((c) => ({ ...c, rushAvailable: event.target.checked }))}
                    />
                    Takes 48-hour rush cases
                  </label>
                </div>

                {/* The petitions they will write for. A separate list from letter types on
                    purpose — one is the deliverable, the other what it supports. */}
                <div className="mt-3">
                  <Tags
                    label="Visa categories supported"
                    options={VISA_CATEGORIES}
                    value={form.visaCategories}
                    onChange={(v) => setForm((c) => ({ ...c, visaCategories: v as VisaCategory[] }))}
                  />
                </div>

                <div className="mt-3 grid gap-3">
                  <Text
                    label="Notable awards"
                    value={form.notableAwards ?? ''}
                    onChange={(v) => set(setForm, 'notableAwards', v)}
                  />
                  <Text
                    label="Professional memberships"
                    value={form.professionalMemberships ?? ''}
                    onChange={(v) => set(setForm, 'professionalMemberships', v)}
                  />
                  <Text
                    label="Editorial roles"
                    value={form.editorialRoles ?? ''}
                    onChange={(v) => set(setForm, 'editorialRoles', v)}
                  />
                </div>
              </details>

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
            </form>
          )}
        </div>
      </SheetContent>
    </SheetRoot>
  )
}

/**
 * The form lives in the sheet's body and its Save button in the sheet's footer, which are
 * siblings rather than nested — so the button reaches the form by `form=` rather than by being
 * inside it. Native HTML, and it keeps the primary action in one place in both modes without
 * touching `components/ui/dialog.tsx`, which is a protected path.
 */
const FORM_ID = 'expert-profile-form'

const INPUT_STYLE = { background: 'var(--bg-base)', borderColor: 'var(--border-default)' }

/**
 * Unit 33's fields, blank.
 *
 * <p>Shared between the new-expert form and {@link formOf} so the two cannot drift: the form
 * is sent whole and the server applies every field, so a dossier field missing from either
 * literal would not be "left alone" — it would arrive as undefined and blank the column.
 */
const EMPTY_DOSSIER = {
  expertCode: null,
  subSpecialization: null,
  highestDegree: null,
  degreeField: null,
  degreeInstitution: null,
  currentPosition: null,
  affiliationType: null,
  country: null,
  stateRegion: null,
  yearsExperience: null,
  linkedinUrl: null,
  visaCategories: [],
  publications: null,
  citations: null,
  hIndex: null,
  patents: null,
  notableAwards: null,
  professionalMemberships: null,
  editorialRoles: null,
  languages: null,
  rushAvailable: false,
  avgTurnaroundDays: null,
} satisfies Partial<ExpertForm>

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
  ...EMPTY_DOSSIER,
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
    // Spread whole: an edit sends the form as it stands, so a dossier field the form did not
    // carry back would be saved as null and quietly erase a transcribed CV.
    ...profile.dossier,
  }
}

/** Blank means "nothing on file", which the server stores as null rather than an empty string. */
function set(
  setForm: React.Dispatch<React.SetStateAction<ExpertForm>>,
  field:
    | 'fullName'
    | 'email'
    | 'phone'
    | 'title'
    | 'institution'
    | 'recruitmentSource'
    | 'dateOnboarded'
    | 'notes'
    // Unit 33's free-text dossier fields. The numbers and the two closed vocabularies are set
    // directly instead, because "" -> null is not the right coercion for either.
    | 'expertCode'
    | 'subSpecialization'
    | 'highestDegree'
    | 'degreeField'
    | 'degreeInstitution'
    | 'currentPosition'
    | 'country'
    | 'stateRegion'
    | 'linkedinUrl'
    | 'notableAwards'
    | 'professionalMemberships'
    | 'editorialRoles'
    | 'languages',
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

/**
 * The initials that stand where a photo would. The roster stores no image and is not getting
 * one, so this is the mark — exported because the roster row shows the same one, and two
 * derivations of a person's two letters can disagree.
 *
 * `aria-hidden`: the name it abbreviates is always beside it, and a screen reader announcing
 * "J S, Dr. John Smith" reads the same person twice.
 */
export function Avatar({ name, size = 'sm' }: { name: string | null; size?: 'sm' | 'lg' }) {
  const large = size === 'lg'
  return (
    <span
      aria-hidden
      className={`inline-flex shrink-0 items-center justify-center rounded-full bg-(--accent-soft) font-semibold text-(--accent-primary) ${
        large ? 'h-11 w-11 text-sm' : 'h-7 w-7 text-[11px]'
      }`}
    >
      {initials(name)}
    </span>
  )
}

/**
 * Availability as a capacity badge. Exported for the roster row, which drew the same fact from
 * its own copy of the token lookup and its own wording for the empty case.
 *
 * The dot takes the badge's own foreground, so it cannot drift from the label beside it.
 */
export function AvailabilityBadge({ availability }: { availability: Availability | null }) {
  const token = availability ? AVAILABILITY_TOKEN[availability] : null
  return (
    <span
      className="inline-flex items-center gap-1.5 rounded-md px-1.5 py-0.5 text-[11px] font-semibold"
      style={
        token
          ? { color: token.fg, background: token.bg }
          : { color: 'var(--text-muted)', background: 'var(--bg-raised)' }
      }
    >
      <span className="h-1.5 w-1.5 rounded-full" style={{ background: 'currentColor' }} aria-hidden />
      {availability ? label(availability) : 'Not set'}
    </span>
  )
}

/**
 * One group of facts in the profile.
 *
 * Grouped rather than the flat `<dl>` of nine terms this was: somebody after a phone number
 * should not have to read past a quality score to find it. The heading is the small uppercase
 * eyebrow the shell already uses for a section label.
 */
function Section({ title, note, children }: { title: string; note?: string; children: React.ReactNode }) {
  return (
    <section className="rounded-lg border border-(--border-default) bg-(--bg-base) p-3.5">
      <h3 className="text-[11px] font-semibold tracking-[0.08em] text-(--text-muted) uppercase">{title}</h3>
      <div className="mt-2.5">{children}</div>
      {note && <p className="mt-2.5 text-xs text-(--text-muted)">{note}</p>}
    </section>
  )
}

/**
 * A dossier value that is a sentence rather than a term: awards, memberships, editorial
 * roles. Full width and wrapping, because truncating "IEEE Fellow (2019)" to fit a
 * two-column grid loses the year that makes it evidence.
 */
function Prose({ term, value }: { term: string; value: string | null }) {
  return (
    <div>
      <p className="text-[10px] font-medium tracking-[0.06em] text-(--text-muted) uppercase">{term}</p>
      <p className={`text-sm ${value ? '' : 'text-(--text-muted)'}`}>{value ?? 'None recorded'}</p>
    </div>
  )
}

/** Country and state as one line, because either alone reads as half an address. */
function place(dossier: Dossier | undefined): string {
  if (!dossier) return '—'
  const parts = [dossier.stateRegion, dossier.country].filter(Boolean)
  return parts.length === 0 ? '—' : parts.join(', ')
}

// A missing count is not zero: nobody has transcribed the CV yet, and rendering 0 would be a
// claim about the expert rather than about our record of them.
const count = (value: number | null): string => (value == null ? '—' : String(value))
const years = (value: number | null): string => (value == null ? '—' : `${value} yrs`)
const days = (value: number | null): string => (value == null ? '—' : `${value} d`)

function Facts({ children }: { children: React.ReactNode }) {
  return <dl className="grid grid-cols-2 gap-x-4 gap-y-3">{children}</dl>
}

function Fact({ term, value, numeric }: { term: string; value: string; numeric?: boolean }) {
  return (
    <div className="min-w-0">
      <dt className="text-[10px] font-medium tracking-[0.06em] text-(--text-muted) uppercase">{term}</dt>
      <dd className={`truncate text-sm ${numeric ? 'font-num tabular-nums' : ''}`} title={value}>
        {value}
      </dd>
    </div>
  )
}

/** A closed vocabulary read back: the tags themselves, not a count of them. */
function TagRow({ term, values, muted }: { term: string; values: readonly string[]; muted?: boolean }) {
  return (
    <div>
      <p className="text-[10px] font-medium tracking-[0.06em] text-(--text-muted) uppercase">{term}</p>
      {values.length === 0 ? (
        <p className="text-sm text-(--text-muted)">None</p>
      ) : (
        <p className="mt-1 flex flex-wrap gap-1">
          {values.map((value) => (
            <span
              key={value}
              className={`rounded-md px-1.5 py-0.5 text-[11px] ${
                muted ? 'bg-(--bg-surface) text-(--text-muted)' : 'bg-(--bg-raised) font-medium'
              }`}
            >
              {label(value)}
            </span>
          ))}
        </p>
      )}
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
        <span className="ml-1 font-normal text-(--text-muted)">
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
