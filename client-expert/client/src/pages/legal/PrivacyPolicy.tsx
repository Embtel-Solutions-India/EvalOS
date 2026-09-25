import { Link } from 'react-router-dom'
import { LEGAL } from '@/constants/legal'
import { ContactBlock, LegalPage } from './LegalPage'

/** The business's Privacy Policy, as supplied 2026-09-25. Edit the text here, not in a copy. */
export default function PrivacyPolicy() {
  return (
    <LegalPage title={LEGAL.privacy.label}>
      <p>
        International Evaluations (&ldquo;International Evaluations,&rdquo; &ldquo;we,&rdquo;
        &ldquo;us,&rdquo; or &ldquo;our&rdquo;) provides immigration-focused credential
        evaluations, expert opinion letters, certified translations, RFE response support, PERM
        advertising documentation, and related professional documentation services (the
        &ldquo;Services&rdquo;) through internationalevaluations.com (the &ldquo;Site&rdquo;). This
        Privacy Policy explains what personal information we collect, how we use and share it, and
        the choices you have. By visiting the Site or using the Services, you agree to the practices
        described here.
      </p>

      <h2>1. Who This Policy Covers</h2>
      <p>
        This Policy applies to visitors to our Site and to individuals whose personal information we
        receive in connection with a Service — including applicants, employees of corporate clients,
        beneficiaries named in a petition, and individuals referred to us by an immigration
        attorney, employer, or sponsor.
      </p>

      <h2>2. Information We Collect</h2>
      <p>
        <strong>Information you or your representative provide to us:</strong>
      </p>
      <ul>
        <li>Contact details — name, email, phone number, mailing address</li>
        <li>Case information — visa category, petition type, employer, job title, and filing timeline</li>
        <li>
          Documents submitted for evaluation or translation — academic transcripts, diplomas, degree
          certificates, professional licenses, employment letters, and reference letters
        </li>
        <li>
          Identity and immigration details — nationality, country of birth, current immigration or
          visa status, and, where required for a specific deliverable, government-issued
          identification numbers
        </li>
        <li>Payment details, processed through our payment provider</li>
        <li>
          Any information you choose to share about a dependent, beneficiary, or family member, which
          you confirm you are authorized to provide
        </li>
      </ul>
      <p>
        <strong>Information collected automatically when you use the Site:</strong>
      </p>
      <ul>
        <li>IP address, browser and device type, and general location</li>
        <li>Pages viewed, links clicked, and referring pages</li>
        <li>Cookie and analytics data (see Section 5)</li>
      </ul>
      <p>
        <strong>Information we receive from others:</strong>
      </p>
      <ul>
        <li>From an immigration attorney, employer, HR team, or sponsor who submits a request on your behalf</li>
        <li>
          From the independent subject-matter expert assigned to your case, limited to what is needed
          to complete your deliverable
        </li>
        <li>From publicly available sources, where relevant to verifying institutional or professional information</li>
      </ul>

      <h2>3. How We Use This Information</h2>
      <p>We use personal information to:</p>
      <ul>
        <li>
          Prepare and deliver the credential evaluation, expert opinion letter, translation, or other
          documentation you requested
        </li>
        <li>Match your case to a field-appropriate independent expert</li>
        <li>Communicate with you about your order, required documents, and case status</li>
        <li>Process payment and maintain billing and service records</li>
        <li>Respond to inquiries and provide customer support</li>
        <li>
          Maintain the records needed to support our work if a document is later questioned by
          USCIS, an employer, or a licensing body
        </li>
        <li>Meet our own legal, tax, and recordkeeping obligations</li>
        <li>Improve the Site and our Services</li>
      </ul>
      <p>
        We do not sell personal information, and we do not use the academic, immigration, or
        identity documents you submit for any purpose beyond delivering your Service and the
        obligations above.
      </p>

      <h2>4. Text Messages and Email</h2>
      <p>
        <strong>SMS.</strong> If you provide a mobile number, we may text you about your case — for
        example, to confirm receipt of a document or a scheduled call. These are one-to-one,
        staff-sent messages, not automated marketing blasts. Message and data rates may apply. Reply{' '}
        <code>STOP</code> to opt out at any time, or <code>HELP</code> for assistance. Consent to
        receive texts is never a condition of purchasing a Service, and your consent and phone number
        are never shared with third parties for their own marketing.
      </p>
      <p>
        <strong>Email.</strong> We send service emails related to your case regardless of marketing
        preferences, since these are necessary to deliver the Service you requested. Any promotional
        emails include an unsubscribe link and identify us as the sender with a valid postal
        address, consistent with the U.S. CAN-SPAM Act. We honor opt-out requests within 10
        business days.
      </p>

      <h2>5. Cookies and Analytics</h2>
      <p>The Site uses cookies and similar technologies:</p>
      <ul>
        <li>
          <strong>Essential cookies</strong> — required for the Site to function
        </li>
        <li>
          <strong>Analytics cookies</strong> — help us understand how visitors use the Site (e.g.,
          Google Analytics); data is aggregated
        </li>
        <li>
          <strong>Marketing cookies</strong> — measure the performance of our advertising, where used
        </li>
      </ul>
      <p>
        You can control or disable cookies through your browser settings; doing so may limit some
        Site functionality.
      </p>

      <h2>6. How We Share Information</h2>
      <p>
        We do not sell personal information. We share it only as needed to deliver your Service or
        as required by law:
      </p>
      <ul>
        <li>
          <strong>With your assigned expert or translator</strong> — the independent professional who
          prepares your deliverable, bound by confidentiality
        </li>
        <li>
          <strong>With service providers</strong> — payment processors, e-signature, email delivery,
          hosting, and analytics vendors, who may access information only to perform their function
          for us
        </li>
        <li>
          <strong>At your direction</strong> — with USCIS, an educational institution, licensing
          board, employer, or your attorney, when needed to deliver or support the Service you
          requested
        </li>
        <li>
          <strong>Within our own delivery team</strong> — including staff who support case production
          in other countries (see Section 7)
        </li>
        <li>
          <strong>For legal reasons</strong> — if required by law, subpoena, or court order, or to
          protect our rights, our clients, or the public
        </li>
        <li>
          <strong>In a business transfer</strong> — if we are involved in a merger, acquisition, or
          sale of assets
        </li>
      </ul>

      <h2>7. International Data Transfers</h2>
      <p>
        We are based in Fremont, California, and use a delivery team that includes staff in India.
        Your information may be stored and processed in the United States, India, or other countries
        where we or our vendors operate. We take reasonable steps to protect information consistent
        with this Policy wherever it is processed, regardless of local law.
      </p>

      <h2>8. Data Retention</h2>
      <p>
        We keep personal information for as long as needed to deliver your Service, respond to any
        later questions about a deliverable (from you, an employer, an attorney, or USCIS), and
        satisfy our own legal, tax, and recordkeeping duties. Specific retention periods by document
        and data type are set out in our{' '}
        <Link to={LEGAL.retention.to} className="text-primary underline-offset-4 hover:underline">
          {LEGAL.retention.label}
        </Link>
        . You may ask us to delete information earlier, subject to what we are legally required to
        keep.
      </p>

      <h2>9. Data Security</h2>
      <p>
        We use reasonable technical and organizational measures — including access controls,
        encrypted transmission, and restricted staff access — to protect personal and case
        information. No method of transmission or storage is completely secure, and we cannot
        guarantee absolute protection. If you believe your account or a submission to us has been
        compromised, contact us immediately (Section 13).
      </p>

      <h2>10. Your Privacy Rights</h2>
      <p>Depending on where you live, you may have the right to:</p>
      <ul>
        <li>Access the personal information we hold about you</li>
        <li>Correct inaccurate information</li>
        <li>Request deletion of your information</li>
        <li>Request a portable copy of your data, where applicable</li>
        <li>Withdraw consent to marketing communications at any time</li>
        <li>
          Opt out of SMS by replying <code>STOP</code>
        </li>
        <li>Lodge a complaint with a data protection authority in your jurisdiction</li>
      </ul>
      <p>
        <strong>California residents (CCPA/CPRA).</strong> You may request disclosure of the
        categories and specific pieces of personal information we&rsquo;ve collected, and request
        correction or deletion, without discrimination for exercising these rights. We collect
        certain sensitive personal information — such as immigration status and, where a Service
        requires it, government identifiers — only to deliver the Service you requested. We do not
        sell personal information or share it for cross-context behavioral advertising.
      </p>
      <p>
        To exercise any of these rights, contact us using the details in Section 13. We will take
        reasonable steps to verify your identity before responding.
      </p>

      <h2>11. Children&rsquo;s Privacy</h2>
      <p>
        Our Site and Services are not directed to children under 13, and we do not knowingly collect
        information directly from them. Where a case involves a minor dependent or beneficiary, that
        information is submitted and managed by a parent, guardian, or authorized adult. If you
        believe a child has given us information directly, contact us and we will delete it
        promptly.
      </p>

      <h2>12. Third-Party Links</h2>
      <p>
        The Site may link to third-party websites, including institutional, government, or partner
        sites. We are not responsible for their privacy practices and encourage you to review their
        policies separately.
      </p>

      <h2>13. Contact Us</h2>
      <ContactBlock />

      <h2>14. Changes to This Policy</h2>
      <p>
        We may update this Policy as our practices or applicable law change. The &ldquo;Last
        Updated&rdquo; date above reflects the most recent revision. We encourage you to review this
        page periodically; continued use of the Site or Services after an update constitutes
        acceptance of the revised Policy.
      </p>
    </LegalPage>
  )
}
