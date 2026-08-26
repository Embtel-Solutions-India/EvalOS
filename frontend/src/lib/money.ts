/**
 * The one place a figure becomes text.
 *
 * **Two functions rather than one, because the difference is the whole point.** A count and an
 * amount are not the same kind of number: rendering them identically is what would put `$93` on a
 * deal *count* — a currency symbol in front of a quantity, which reads as a real figure and is
 * simply false. `formatMoney` is opt-in at every call site for that reason: nothing gets a `$`
 * by being a number, only by being money. (A failure this shape is prevented here, not recovered
 * from — the card rendered a bare value before this, with no currency symbol in it at all.)
 *
 * Both group thousands. `$86950` is not how an amount reads, and an ungrouped five-digit count
 * is no better.
 *
 * Assumed USD, lifted verbatim from `CaseCard` where it already lived: the business runs on a US
 * SLA calendar and US federal holidays, and no currency is stored on the case. If a brand ever
 * bills in anything else this needs a column, not a guess here — one guess in one file, now,
 * instead of the two that were drifting apart.
 *
 * Whole units, no cents. Every figure this app shows is an order value or a period total, and
 * the cents on those are noise a reader has to skip past.
 */
const MONEY = new Intl.NumberFormat('en-US', {
  style: 'currency',
  currency: 'USD',
  maximumFractionDigits: 0,
})

const COUNT = new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 })

/** An amount, with its currency symbol: `$86,950`. Only for figures that are money. */
export function formatMoney(value: number): string {
  return MONEY.format(value)
}

/** A quantity, grouped and bare: `11,400`. Counts, percentages, hours — anything not money. */
export function formatCount(value: number): string {
  return COUNT.format(value)
}
