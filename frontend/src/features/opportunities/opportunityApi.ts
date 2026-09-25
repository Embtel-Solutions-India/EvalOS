import { api, unwrap } from '../../lib/api'

/**
 * `OpportunityBoardService.Deal` — one card on the board.
 *
 * `amount` is null when GHL holds no value for the opportunity, **not** zero: "nobody has priced
 * it" and "it is worth nothing" are different claims, and the second one loses deals.
 */
export type Deal = {
  opportunityId: string
  name: string | null
  contactId: string
  status: string
  amount: number | null
  /**
   * GHL's own last-modified stamp — **not** when EvalOS last read the row.
   *
   * Mirrors `OpportunityBoardService.Deal.updatedAt`. Null when GHL sends none, which the desk
   * must render as "age unknown" rather than assume is fresh: a deal nobody has touched is the
   * thing these screens exist to surface, so guessing in the optimistic direction defeats them.
   */
  updatedAt: string | null
  /** The deal's source, else its lead-source field. Null when neither says. */
  source: string | null
  /** The service-requested field, else the portal request's service. Null when neither says. */
  service: string | null
}

/** One stage of the pipeline, named by GHL and ordered by GHL's own `position`. */
export type BoardColumn = {
  stageId: string
  stageName: string
  position: number
  deals: readonly Deal[]
  total: number
}

/**
 * The whole board.
 *
 * `lastSyncedAt` and `stale` are on the payload because a screen served from a mirror is one the
 * reader has to trust blindly otherwise. Unit 46 made that load-bearing: the board never reads GHL,
 * so these two are the only way to know whether what is on screen is current.
 *
 * `lastSyncedAt` is **null when the sync has never confirmed these pipelines**. It is not
 * substituted with "now" — saying "synced just now" when nothing has ever synced is the one lie
 * this indicator exists to prevent — and a null is `stale` by definition.
 */
export type OpportunityBoard = {
  columns: readonly BoardColumn[]
  totalDeals: number
  totalValue: number
  lastSyncedAt: string | null
  stale: boolean
  /**
   * False when `evalos.ghl.sales-brand` is blank on the server.
   *
   * A blank one makes every mirror a no-op, so the board is empty and unsynced for a reason that
   * has nothing to do with the sweep being behind. Without this the screen blames the sync for a
   * configuration mistake — which is exactly what happened on 2026-09-17.
   */
  syncConfigured: boolean
}

/**
 * The caller's board.
 *
 * **There is no argument, and there must never be one.** What a caller sees is decided entirely
 * by their own principal: a `SALES` or `MARKETING` member gets the one pipeline their row names,
 * and the GM gets the union of the selling brand's. A `pipelineId` parameter here would make the
 * whole access model advisory — the server takes none, and the day one is added the server's own
 * test fails first.
 */
export function fetchOpportunityBoard(signal?: AbortSignal): Promise<OpportunityBoard> {
  return unwrap<OpportunityBoard>(api.get('/opportunities/board', { signal }))
}

/**
 * Sync the mirror for the caller's pipelines, then get the board back.
 *
 * **This reconciles; it does not read GHL on the board's behalf.** The board is drawn from EvalOS
 * rows either way — what this does is bring those rows forward first. POST because it writes.
 */
/** Who the deal is with. Null when the mirror has not absorbed the contact yet. */
export type DealContact = {
  name: string | null
  email: string | null
  phone: string | null
  company: string | null
  /** `SourceChannel`'s name — `WEBSITE`, `GOOGLE_ADS`, … — or null. Label it with {@link sourceLabel}. */
  source: string | null
  /** The GHL user's display name, already resolved server-side. Never an id. */
  assignedTo: string | null
  /** When GHL opened the deal, not when EvalOS mirrored it. */
  createdAt: string | null
}

/**
 * `GOOGLE_ADS` -> `Google ads`.
 *
 * **Not a lookup table.** A map from the eight `SourceChannel` constants to prose would be a
 * second list to keep in step with the enum, and the one that drifts is this one — a channel added
 * server-side would render blank here rather than merely unpolished. Reshaping the name cannot
 * miss a value.
 */
export function sourceLabel(source: string | null): string | null {
  if (!source) return null
  const words = source.replace(/_/g, ' ').toLowerCase()
  return words.charAt(0).toUpperCase() + words.slice(1)
}

export function fetchDealContact(
  opportunityId: string,
  signal?: AbortSignal,
): Promise<DealContact | null> {
  return unwrap<DealContact | null>(
    api.get(`/opportunities/${opportunityId}/contact`, { signal }),
  )
}

export function refreshOpportunityBoard(signal?: AbortSignal): Promise<OpportunityBoard> {
  return unwrap<OpportunityBoard>(api.post('/opportunities/board/refresh', undefined, { signal }))
}

// --- Unit 39: the marketing desk --------------------------------------------

/** What GHL returned after a lead was opened or matched. */
export type Lead = {
  contactId: string
  opportunityId: string
  name: string | null
  monetaryValue: number | null
  /**
   * Whether anything was actually created.
   *
   * GHL has no idempotency key, so creates go through upsert and this flag is how the desk can
   * say "opened" rather than "already open" — a repeat submission is one deal, and the marketer
   * should be told which happened instead of guessing.
   */
  created: boolean
}

/**
 * One entry in a deal's note timeline — EvalOS's own notes and GHL's, merged by the server
 * (`OpportunityNoteService.Note`, Unit 54). Stored apart, shown together.
 */
export type Note = {
  id: string
  body: string
  /** A team member for an EvalOS note; null for a GHL one. */
  authorId: string | null
  /** Null on the note the server has only just written, before the database stamps it. */
  createdAt: string | null
  origin: 'EVALOS' | 'GHL'
  authorName: string | null
  /** GHL's note title; EvalOS notes have none. */
  title: string | null
  /** An EvalOS note's push has landed in GHL. Null for a GHL note. */
  inGhl: boolean | null
  /** A GHL note on the contact rather than this deal — it shows on every deal of that contact. */
  onContact: boolean
  /** When its author last edited it (Unit 54a); null if never, and always null for a GHL note. */
  updatedAt: string | null
}

export type NewLead = {
  firstName?: string
  lastName?: string
  email?: string
  phone?: string
  name?: string
  monetaryValue?: number
}

/**
 * Opens a lead on the caller's own pipeline.
 *
 * **No pipeline argument, deliberately** — the server takes the caller's from their token. An
 * email or a phone is required, because GHL matches an existing contact on those and a lead with
 * neither makes every save create another contact. The server enforces it; the form asks for it.
 */
export function openLead(lead: NewLead): Promise<Lead> {
  return unwrap<Lead>(api.post('/marketing/leads', lead))
}

/** Records the valuation on an opportunity the caller owns. */
export function valueLead(
  opportunityId: string,
  update: { name?: string; monetaryValue?: number },
): Promise<Lead> {
  return unwrap<Lead>(api.put(`/marketing/leads/${opportunityId}`, update))
}

/**
 * One deal's notes.
 *
 * **Not under /marketing or /sales.** Unit 40 moved these routes: a lead is nurtured by
 * Marketing, promoted by GHL's automation and closed by Sales, so the conversation belongs to
 * the deal rather than to whichever desk currently holds it.
 */
export function fetchNotes(opportunityId: string, signal?: AbortSignal): Promise<readonly Note[]> {
  return unwrap<readonly Note[]>(api.get(`/opportunities/${opportunityId}/notes`, { signal }))
}

/**
 * Adds a note. There is no edit and no delete, here or on the server or in the database.
 *
 * This is the client conversation rather than a record of it, so a correction is a new note.
 */
export function addNote(opportunityId: string, body: string): Promise<Note> {
  return unwrap<Note>(api.post(`/opportunities/${opportunityId}/notes`, { body }))
}

/** Overwrites a note — its author only; the server refuses anyone else (Unit 54a). */
export function editNote(opportunityId: string, noteId: string, body: string): Promise<Note> {
  return unwrap<Note>(api.put(`/opportunities/${opportunityId}/notes/${noteId}`, { body }))
}

/** Deletes a note outright — its author only. The GHL copy follows within a drain tick. */
export function deleteNote(opportunityId: string, noteId: string): Promise<void> {
  return unwrap<void>(api.delete(`/opportunities/${opportunityId}/notes/${noteId}`))
}

// --- Unit 40: the sales desk ------------------------------------------------

/** What the sales desk gets back after an action — GHL's answer, not the request echoed. */
export type SalesDeal = {
  opportunityId: string
  contactId: string
  name: string | null
  stageId: string
  status: string
  monetaryValue: number | null
}

/**
 * The three outcomes a salesperson may set.
 *
 * `open` is absent deliberately: re-opening a won deal would not un-create the case its
 * webhook already made, so it is a correction with a case-side answer rather than a sales
 * action. The server refuses anything else, lowercase included — GHL's enum is lowercase.
 */
export type CloseStatus = 'won' | 'lost' | 'abandoned'

export function updateDeal(
  opportunityId: string,
  update: { name?: string; monetaryValue?: number },
): Promise<SalesDeal> {
  return unwrap<SalesDeal>(api.put(`/sales/opportunities/${opportunityId}`, update))
}

/**
 * Moves a deal to another stage **of the pipeline it is already in**.
 *
 * There is no target-pipeline parameter and there must never be one: promotion between
 * pipelines is GHL's workflow, and a second path here would race the automation the business
 * already owns.
 */
export function moveStage(opportunityId: string, stageId: string): Promise<SalesDeal> {
  return unwrap<SalesDeal>(api.put(`/sales/opportunities/${opportunityId}/stage`, { stageId }))
}

/**
 * Closes the deal.
 *
 * **Winning returns before the case exists.** EvalOS tells GHL; GHL's `opportunity.won` webhook
 * creates the case (invariant 8, Handoff A). The screen must show that gap as pending rather
 * than as nothing, or a salesperson presses Won twice.
 */
export function closeDeal(opportunityId: string, status: CloseStatus): Promise<SalesDeal> {
  return unwrap<SalesDeal>(api.put(`/sales/opportunities/${opportunityId}/status`, { status }))
}

/**
 * One open follow-up on the caller's pipeline, as the queue shows it.
 *
 * Mirrors `SalesCalendarController.FollowUpView`. Read from EvalOS's mirror rather than GHL —
 * GHL lists tasks only per contact, so a desk-wide view over GHL would be one call per contact.
 */
export type FollowUpItem = {
  taskId: string
  opportunityId: string
  contactId: string
  title: string
  note: string | null
  dueAt: string
  completed: boolean
}

/** Open follow-ups due before a cutoff, soonest first. */
export function fetchFollowUps(
  before: Date,
  signal?: AbortSignal,
): Promise<readonly FollowUpItem[]> {
  return unwrap<readonly FollowUpItem[]>(
    api.get('/sales/follow-ups', { params: { before: before.toISOString() }, signal }),
  )
}

/**
 * Marks a follow-up done — GHL first, then EvalOS's mirror.
 *
 * That order is the server's and it matters: if GHL refuses, nothing locally claims to be done.
 */
export function completeFollowUp(opportunityId: string, taskId: string): Promise<void> {
  return unwrap<void>(
    api.put(`/sales/opportunities/${opportunityId}/follow-ups/${taskId}/complete`, {}),
  )
}

/** A follow-up is a GHL task on the deal's contact, not an EvalOS reminder. */
export function setFollowUp(
  opportunityId: string,
  followUp: {
    contactId: string
    title: string
    dueAt: string
    /** GHL's task `body`. What the salesperson wants to remember, beyond the title. */
    note?: string
    /** Omit for GHL's own default assignment. */
    assignedUserId?: string
  },
): Promise<{ ghlTaskId: string }> {
  return unwrap<{ ghlTaskId: string }>(
    api.post(`/sales/opportunities/${opportunityId}/follow-ups`, followUp),
  )
}

/**
 * A calendar a meeting can be booked into.
 *
 * Mirrors `GhlCalendarClient.CalendarOption`. This was id and name until 2026-09-15: GHL's own
 * booking dialog is driven by the other three, so a picker with only id and name could not
 * behave like one. `active` matters most — the location has twelve calendars and five are
 * active, and GHL refuses a booking on an inactive one with "Calendar is inactive".
 */
export type Calendar = {
  id: string
  name: string
  active: boolean
  /** The calendar's own slot length. It is why the form picks a slot rather than a duration. */
  slotMinutes: number | null
  /** GHL's `eventTitle`, e.g. `{{contact.name}}` — shown as the title's default, as GHL does. */
  titleTemplate: string | null
}

/**
 * A calendar's bookable slots, as GHL computes them — keyed by date, values offset-bearing ISO.
 *
 * Availability depends on open hours, buffers, per-day caps, the team member's other
 * appointments and minimum notice — all GHL configuration. EvalOS asks rather than calculates,
 * which is the only way the two agree.
 */
export type FreeSlots = { timezone: string; byDate: Record<string, readonly string[]> }

export function fetchSlots(
  calendarId: string,
  from: Date,
  to: Date,
  timezone: string,
  signal?: AbortSignal,
): Promise<FreeSlots> {
  return unwrap<FreeSlots>(
    api.get(`/sales/calendars/${calendarId}/slots`, {
      params: { from: from.getTime(), to: to.getTime(), timezone },
      signal,
    }),
  )
}

export function fetchCalendars(signal?: AbortSignal): Promise<readonly Calendar[]> {
  return unwrap<readonly Calendar[]>(api.get('/sales/calendars', { signal }))
}

export type Meeting = {
  id: string
  calendarId: string
  contactId: string
  title: string
  startTime: string
  endTime: string
  status: string
}

/**
 * Books a meeting in GHL with the deal's contact.
 *
 * **Booking runs GHL's automations**, which is how the client actually receives the invitation
 * — EvalOS sends nothing itself. And **pressing it twice books two meetings**: GHL offers no
 * upsert for appointments, so there is no idempotency behind this call and the UI has to be the
 * thing that stops a double-submit.
 */
export function bookMeeting(
  opportunityId: string,
  meeting: {
    calendarId: string
    contactId: string
    title: string
    startTime: string
    endTime: string
    description?: string
    /** Omit for "Calendar Default" — the calendar's own assigned member takes it. */
    assignedUserId?: string
    /** `custom` | `zoom` | `gmeet` | `phone` | `address` | `ms_teams` | `google`. */
    meetingLocationType?: string
    address?: string
    /**
     * GHL's Default | Custom toggle, as `ignoreFreeSlotValidation`.
     *
     * Default books into a slot the calendar says is free. Custom takes any time — GHL then stops
     * refusing collisions, so a double-booking becomes a silent success rather than a visible
     * failure. Off unless the salesperson deliberately chooses it.
     */
    customTime?: boolean
    /** A second GHL call the server makes after the appointment exists. Max 5000 characters. */
    internalNote?: string
  },
): Promise<Meeting> {
  return unwrap<Meeting>(api.post(`/sales/opportunities/${opportunityId}/meetings`, meeting))
}

/**
 * One meeting on the caller's own pipeline, as the diary shows it.
 *
 * Mirrors `SalesCalendarController.MeetingView` exactly. `startsAt`/`endsAt` are ISO-8601 here —
 * unlike the client portal's `ClientMeeting`, whose times are GHL's own unzoned strings and are
 * deliberately never parsed. These come from EvalOS's own column, so they are real instants.
 */
export type DiaryMeeting = {
  appointmentId: string
  opportunityId: string
  contactId: string
  title: string
  startsAt: string
  endsAt: string
  status: string | null
}

/**
 * The caller's meetings inside a window, soonest first.
 *
 * Read from EvalOS's mirror rather than GHL: a diary is a screen people leave open, and a GHL
 * call per render would spend the location's rate budget on it.
 */
export function fetchDiary(
  from: Date,
  to: Date,
  signal?: AbortSignal,
): Promise<readonly DiaryMeeting[]> {
  return unwrap<readonly DiaryMeeting[]>(
    api.get('/sales/meetings', {
      params: { from: from.toISOString(), to: to.toISOString() },
      signal,
    }),
  )
}

/**
 * The fields a salesperson fills to open a deal — GHL's own "add opportunity" form, minus the
 * ones EvalOS would have to guess at.
 *
 * Absent on purpose, each mirrored from `SalesOpportunityController.NewDealRequest`:
 * `pipelineId` (the caller's own, never a field), `status` (forced to `open` — GHL accepts `won`
 * on create, which fires Handoff A and mints a *paid* case), `assignedTo` (a GHL user id EvalOS
 * does not hold), `customFields` (definitions arrive with the tier-2 mirror) and
 * `forecastProbability` (GHL derives it from the stage).
 */
export type NewDeal = {
  firstName?: string
  lastName?: string
  email?: string
  phone?: string
  name: string
  monetaryValue?: number
  stageId?: string
  expectedCloseDate?: string
  /**
   * GHL custom field values, keyed by the location's own field id.
   *
   * The ids come from {@link fetchOpportunityFields}, never from a constant here — they are
   * location-scoped, and the 2026-09-11 sub-account swap invalidated every hardcoded GHL id in
   * the codebase at once. This is where the intake facts live: Service Requested, Visa Category,
   * turnaround, and the client's own description of the case.
   */
  customFields?: Record<string, string>
  /** Set only after the caller has been shown the contact's existing open deal. */
  confirmSecondDeal?: boolean
}

/**
 * Opens a new deal on the caller's own pipeline.
 *
 * **A true create, not an upsert**, which is what makes a repeat client's second purchase a
 * second deal rather than an overwrite of their first. The cost is that the duplicate protection
 * upsert gave for free is gone, so the server answers **409 `DEAL_ALREADY_OPEN`** when the
 * contact already has an open deal — retry with `confirmSecondDeal` once the user has seen it.
 */
export function createDeal(deal: NewDeal): Promise<SalesDeal> {
  return unwrap<SalesDeal>(api.post('/sales/opportunities', deal))
}

/**
 * A custom field this GHL location puts on an opportunity.
 *
 * Mirrors `GhlCustomFieldClient.CustomField`. `dataType` is GHL's own word — `TEXT`,
 * `LARGE_TEXT`, `NUMERICAL`, `DATE`, `SINGLE_OPTIONS` — passed through rather than mapped, so a
 * type added in GHL tomorrow degrades to a text input instead of failing to parse.
 */
export type OpportunityField = {
  id: string
  name: string
  fieldKey: string
  dataType: string
  picklistOptions: readonly string[]
}

/**
 * The location's opportunity custom fields.
 *
 * Read live rather than hardcoded: the ids are location-scoped, and the 2026-09-11 sub-account
 * swap invalidated every hardcoded GHL id in the codebase at once.
 */
export function fetchOpportunityFields(
  signal?: AbortSignal,
): Promise<readonly OpportunityField[]> {
  return unwrap<readonly OpportunityField[]>(api.get('/sales/opportunity-fields', { signal }))
}

/** A GHL user, for the "Team member" picker. */
export type GhlUser = { id: string; name: string; email: string }

export function fetchGhlUsers(signal?: AbortSignal): Promise<readonly GhlUser[]> {
  return unwrap<readonly GhlUser[]>(api.get('/sales/users', { signal }))
}

// --- Unit 43: the client's own request --------------------------------------

/** `ClientApplicationService.ApplicationView` — what a client asked us for. */
export type ClientApplication = {
  id: string
  serviceId: string
  serviceName: string
  purpose: string | null
  status: 'DRAFT' | 'SUBMITTED'
  createdAt: string
  updatedAt: string
  submittedAt: string | null
}

/**
 * The portal request behind a deal, or **null** when the deal did not come from the portal.
 *
 * **The server answers 200 with no payload for that case, not 404**, because most of the board is
 * deals a salesperson opened by hand and "no request here" is an ordinary answer rather than a
 * failure. Anything that actually fails still throws, so "GHL is down" and "this deal was phoned
 * in" do not look the same on the panel.
 */
export async function fetchApplication(
  opportunityId: string,
  signal?: AbortSignal,
): Promise<ClientApplication | null> {
  const found = await unwrap<ClientApplication | null>(
    api.get(`/opportunities/${opportunityId}/application`, { signal }),
  )
  // `@JsonInclude(NON_NULL)` drops the key entirely rather than sending null, so this is
  // `undefined` in practice — normalised here so one falsy shape reaches the component.
  return found ?? null
}

/**
 * One document the client sent with their request — Unit 53 (D33/D34).
 *
 * **No object key.** That is an internal S3 address; the only way to open one of these is the
 * five-minute presigned URL below, which is minted per click and never stored.
 */
export type RequestDocument = {
  id: string
  filename: string
  contentType: string | null
  sizeBytes: number | null
  uploadedAt: string
  /** True once Handoff A copied it onto the case, which is a fact a Coordinator asks about. */
  carriedToCase: boolean
}

/**
 * The documents behind a deal.
 *
 * **Its own route beside the application, not a field on it** (`53` §3, D34). Sales reaches these
 * by already being able to open the opportunity, so the documents ask no new authorisation
 * question — and an empty list is the ordinary answer for the deals somebody phoned in.
 */
export function fetchRequestDocuments(
  opportunityId: string,
  signal?: AbortSignal,
): Promise<readonly RequestDocument[]> {
  return unwrap<readonly RequestDocument[]>(
    api.get(`/opportunities/${opportunityId}/documents`, { signal }),
  )
}

/**
 * A five-minute URL for one document.
 *
 * **Fetched on the click, never held.** A URL rendered into an `href` at load time is a credential
 * sitting in the DOM for as long as the tab is open, and it expires while the reader is still
 * looking at it — so the link asks for a fresh one each time and opens what comes back.
 */
export async function requestDocumentUrl(
  opportunityId: string,
  documentId: string,
): Promise<string> {
  const answer = await unwrap<{ url: string }>(
    api.get(`/opportunities/${opportunityId}/documents/${documentId}/url`),
  )
  return answer.url
}

// --- The contacts directory -------------------------------------------------

/**
 * One row of the contacts list.
 *
 * `dealCount` is counted **inside the caller's own scope**, so a desk sees the number of deals it
 * can actually open rather than a business-wide total it cannot account for.
 */
export type DirectoryContact = {
  id: string
  brandId: string
  brandName: string | null
  name: string | null
  email: string | null
  phone: string | null
  company: string | null
  /** `SourceChannel`'s name, or null. Label it with {@link sourceLabel}. */
  source: string | null
  dealCount: number
  lastActivityAt: string | null
}

/**
 * One page of contacts, with the total behind it.
 *
 * `size` is what the server **applied**, not what was asked for — it clamps, and a last page of 7
 * out of 15 still reports 15. Compute the page count from this and not from `contacts.length`.
 */
export type ContactPage = {
  contacts: readonly DirectoryContact[]
  total: number
  page: number
  size: number
}

/**
 * Everyone the CRM holds, at the width this caller reads.
 *
 * **The scope is the server's and is never sent.** There is no brand or pipeline parameter here
 * on purpose: a list whose width came from the request would be a width the caller could change.
 *
 * **Paged on the server, not sliced on the client.** The list is 1,400+ contacts and growing with
 * the CRM; shipping all of them to slice fifteen out would cost the payload of the whole roster on
 * every keystroke of the search box.
 */
export function fetchContacts(
  search: string,
  page: number,
  size: number,
  signal?: AbortSignal,
): Promise<ContactPage> {
  return unwrap<ContactPage>(
    api.get('/contacts', {
      params: { page, size, ...(search.trim() ? { search: search.trim() } : {}) },
      signal,
    }),
  )
}
