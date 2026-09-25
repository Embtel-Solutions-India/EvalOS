import { LEGAL } from '@/constants/legal'
import { ContactBlock, LegalPage } from './LegalPage'

/** The schedule in §3, as rows so the table and any future reader share one list. */
const SCHEDULE: { type: string; examples: string; period: string; basis: string }[] = [
  {
    type: 'Case intake and client contact information',
    examples: 'Name, email, phone, mailing address',
    period: 'Duration of client relationship + 7 years',
    basis: 'Recordkeeping, ability to reissue or reference past deliverables',
  },
  {
    type: 'Source documents submitted by applicant',
    examples: 'Transcripts, diplomas, degree certificates, employment letters, reference letters',
    period: '7 years from delivery of final report',
    basis: 'Supports the evaluation if later questioned by USCIS, an employer, or a licensing board',
  },
  {
    type: 'Final deliverables',
    examples: 'Credential evaluation reports, expert opinion letters, certified translations, wage level letters',
    period: '7 years from delivery, or longer if the case remains open',
    basis: 'Same as above; matches typical statute-of-limitations and USCIS follow-up windows',
  },
  {
    type: 'Government-issued identification copies',
    examples: 'Passport pages, national ID numbers submitted for a specific deliverable',
    period: 'Deleted or securely destroyed within 90 days of delivery unless retention is required for that specific case',
    basis: 'Minimizes exposure of high-sensitivity identifiers',
  },
  {
    type: 'Immigration and case-status details',
    examples: 'Visa category, filing history, employer details',
    period: 'Same as case file (7 years)',
    basis: 'Needed to support the case record',
  },
  {
    type: 'Correspondence with independent experts',
    examples: 'Case-matching notes, expert communications about a specific deliverable',
    period: '7 years from delivery',
    basis: "Ties the expert's work to the final letter if it is ever challenged",
  },
  {
    type: 'Payment and billing records',
    examples: 'Invoices, payment confirmations (not full card numbers, which we do not store)',
    period: '7 years',
    basis: 'U.S. federal and California tax recordkeeping requirements',
  },
  {
    type: 'Marketing and newsletter contact data',
    examples: 'Email opt-ins, campaign engagement',
    period: 'Until you unsubscribe, plus a reasonable period to honor the opt-out',
    basis: 'Consent-based; retained only while active',
  },
  {
    type: 'Website analytics data',
    examples: 'IP address, browsing behavior, cookie data',
    period: 'Per the retention window of the analytics tool in use, aggregated where possible',
    basis: 'Site improvement; not tied to case records',
  },
  {
    type: 'SMS consent and opt-out records',
    examples: 'Consent timestamp, STOP requests',
    period: '4 years from last activity',
    basis: 'TCPA compliance',
  },
  {
    type: 'Employee and contractor records (internal)',
    examples: 'Onboarding, payroll, performance',
    period: 'Per applicable employment law in the relevant jurisdiction',
    basis: 'Legal and HR compliance',
  },
]

/** The business's Document Retention Policy, as supplied 2026-09-25. Edit the text here, not in a copy. */
export default function DocumentRetention() {
  return (
    <LegalPage title={LEGAL.retention.label}>
      <h2>1. Purpose</h2>
      <p>
        This policy sets out how long International Evaluations keeps the documents and data we
        receive or create while delivering credential evaluations, expert opinion letters, certified
        translations, RFE response support, PERM documentation, and business plans, and how those
        records are stored, protected, and eventually disposed of. It exists to make sure we keep
        records long enough to stand behind our work and meet our legal obligations, without holding
        sensitive client and applicant information longer than necessary.
      </p>

      <h2>2. Scope</h2>
      <p>
        This policy covers records in any format — digital files, email, uploaded documents, physical
        mail, and payment records — held by International Evaluations staff, our production and
        delivery teams, and any vendor or subcontractor that processes records on our behalf.
      </p>

      <h2>3. Retention Schedule</h2>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[40rem] border-collapse text-left text-xs">
          <thead>
            <tr className="border-b border-border text-muted-foreground">
              <th className="py-2 pr-3 font-medium">Record type</th>
              <th className="py-2 pr-3 font-medium">Examples</th>
              <th className="py-2 pr-3 font-medium">Retention period</th>
              <th className="py-2 font-medium">Basis</th>
            </tr>
          </thead>
          <tbody>
            {SCHEDULE.map((row) => (
              <tr key={row.type} className="border-b border-border align-top">
                <td className="py-2 pr-3 font-medium">{row.type}</td>
                <td className="py-2 pr-3">{row.examples}</td>
                <td className="py-2 pr-3">{row.period}</td>
                <td className="py-2">{row.basis}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p>
        Where a case remains open, under dispute, subject to an RFE, or involved in any actual or
        threatened legal proceeding, the applicable records are retained until that matter is fully
        resolved, regardless of the schedule above (&ldquo;legal hold&rdquo;).
      </p>

      <h2>4. Why We Retain Case Records for 7 Years</h2>
      <p>
        Immigration petitions can be revisited well after filing — through an RFE, a renewal, a later
        petition that references prior evidence, or a question from an employer or licensing board.
        Keeping the source documents and final deliverable together for 7 years lets us confirm what
        we prepared, for whom, and on what basis, if that question ever comes up. Shorter or longer
        periods apply to specific categories, as shown in Section 3, based on the sensitivity of the
        data and the reason we needed it in the first place.
      </p>

      <h2>5. Storage and Security During Retention</h2>
      <p>While records are retained, they are:</p>
      <ul>
        <li>Stored in access-controlled systems, with access limited to staff and vendors who need it to do their work</li>
        <li>Encrypted in transit and, where supported by our storage provider, at rest</li>
        <li>
          Backed up according to our internal backup schedule, with backups subject to the same
          retention and disposal rules as the primary record
        </li>
        <li>
          Not used for any purpose beyond delivering the original Service, supporting that case, and
          our own legal/tax obligations
        </li>
      </ul>

      <h2>6. Disposal and Destruction</h2>
      <p>When a record reaches the end of its retention period and is not under legal hold:</p>
      <ul>
        <li>
          Digital records are permanently deleted, including from active systems and, on the next
          backup rotation, from backups
        </li>
        <li>Physical documents, if any, are shredded or otherwise destroyed so they cannot be reconstructed</li>
        <li>Government ID copies are prioritized for early, secure deletion per Section 3, ahead of the rest of the case file</li>
      </ul>

      <h2>7. Early Deletion Requests</h2>
      <p>
        You may ask us to delete your personal information or submitted documents before the end of
        the standard retention period. We will honor that request unless we are required to keep the
        record for a legal, tax, or regulatory reason, or unless the case is under legal hold — in
        which case we will tell you what we can delete now and what we must retain, and why.
      </p>

      <h2>8. Vendors and Subcontractors</h2>
      <p>
        Any vendor or subcontractor that stores or processes records on our behalf — including our
        delivery team and any hosting or software provider — is contractually required to follow
        retention and destruction practices consistent with this policy and to delete or return
        records at the end of the engagement.
      </p>

      <h2>9. Responsibility</h2>
      <p>
        Day-to-day responsibility for applying this policy sits with International Evaluations&rsquo;
        operations team. Questions about a specific record, or requests under Section 7, should be
        directed to:
      </p>
      <ContactBlock />

      <h2>10. Review of This Policy</h2>
      <p>
        This policy is reviewed periodically and updated as our services, systems, or legal
        obligations change. The &ldquo;Last Updated&rdquo; date above reflects the most recent
        revision.
      </p>
    </LegalPage>
  )
}
