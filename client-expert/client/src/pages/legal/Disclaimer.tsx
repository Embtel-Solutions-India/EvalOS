import { LEGAL } from '@/constants/legal'
import { ContactBlock, LegalPage } from './LegalPage'

/** The business's Disclaimer, as supplied 2026-09-25. Edit the text here, not in a copy. */
export default function Disclaimer() {
  return (
    <LegalPage title={LEGAL.disclaimer.label}>
      <h2>1. Not a Law Firm — No Legal Advice</h2>
      <p>
        International Evaluations is not a law firm, does not employ or hold itself out as
        immigration attorneys, and does not provide legal advice, legal representation, or petition
        strategy of any kind. Nothing on this website, in our communications, or in any deliverable
        we prepare should be treated as legal advice or relied upon as a substitute for consulting a
        qualified, licensed immigration attorney. We recommend every client work with a licensed
        attorney to determine the right filing strategy and to review the final petition before
        submission.
      </p>
      <p>
        Because we are not a law firm, communications with our team are{' '}
        <strong>not protected by attorney-client privilege</strong>.
      </p>

      <h2>2. What We Provide</h2>
      <p>
        We provide professional documentation and evaluation services in support of employment-based
        and immigration-related filings, including credential evaluations, expert opinion letters,
        certified translations, wage level letters, RFE response documentation, PERM recruitment
        advertising materials, and business plans. These are prepared to a professional standard
        intended to meet USCIS documentation expectations, but they are supporting evidence within a
        larger petition — not the petition itself, and not a guarantee of any outcome.
      </p>

      <h2>3. No Guarantee of Outcome</h2>
      <p>
        Eligibility for any visa, green card, or other immigration benefit is determined solely by
        USCIS, the Department of Labor, or the relevant adjudicating authority, based on the complete
        petition record and applicable law at the time of filing.{' '}
        <strong>
          We make no representation, warranty, or guarantee — express or implied — regarding the
          approval, denial, processing time, or outcome of any petition or application
        </strong>
        , whether or not our documentation is used. Past results for other clients do not predict the
        outcome of any individual case.
      </p>

      <h2>4. Independent Expert Opinions</h2>
      <p>
        Each expert opinion letter is written and personally signed by an independent academic or
        industry expert from our vetted network. The opinions, conclusions, and assessments in that
        letter are the expert&rsquo;s own genuine professional judgment, formed from the materials
        and information provided. They do not represent a position, endorsement, or guarantee by
        International Evaluations, and an expert&rsquo;s participation should not be read as a
        promise that USCIS or any other authority will accept the opinion or the petition it
        supports.
      </p>

      <h2>5. Accuracy of Submitted Materials</h2>
      <p>
        Our evaluations, translations, and opinion letters are prepared based on the documents,
        transcripts, and information you or your representative provide to us, typically as scanned
        or digital copies.{' '}
        <strong>
          We do not independently verify the authenticity, accuracy, or completeness of any document
          submitted to us
        </strong>
        , and neither International Evaluations nor its independent experts make any warranty as to
        the genuineness of the underlying records. Verifying that all documentation submitted with a
        petition is accurate, complete, and appropriate for the case remains the responsibility of
        the applicant, the sponsoring employer, and their immigration attorney.
      </p>
      <p>
        You are responsible for the truthfulness of any information you submit to us, and for
        promptly correcting anything that changes or that you later discover to be inaccurate.
      </p>

      <h2>6. General Information Only</h2>
      <p>
        Content on this website — including FAQs, blog posts, visa guides, and pricing pages — is
        provided for general informational purposes only. Immigration law, USCIS policy, and
        processing practices change frequently and can vary by case; nothing on this site should be
        treated as current, complete, or applicable to your specific circumstances without
        independent confirmation.
      </p>

      <h2>7. Limitation of Liability</h2>
      <p>
        To the fullest extent permitted by law, International Evaluations and its officers, staff,
        and independent experts are not liable for any indirect, incidental, or consequential loss —
        including a denied petition, a missed deadline, or a lost opportunity — arising from the use
        of our Services, our website, or any document we prepare, except where caused by our own
        gross negligence or willful misconduct.
      </p>

      <h2>8. Third-Party Resources</h2>
      <p>
        Any links to government sites, institutional sites, or third-party tools are provided for
        convenience. We do not control and are not responsible for the accuracy or availability of
        third-party content.
      </p>

      <h2>9. Contact Us</h2>
      <p>Questions about this Disclaimer can be directed to:</p>
      <ContactBlock />
    </LegalPage>
  )
}
