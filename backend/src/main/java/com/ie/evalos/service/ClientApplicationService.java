package com.ie.evalos.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.common.ForbiddenException;
import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.ClientAccount;
import com.ie.evalos.domain.ClientApplication;
import com.ie.evalos.integration.GhlPipelineClient;
import com.ie.evalos.integration.GhlWriteClient;
import com.ie.evalos.repository.ClientAccountRepository;
import com.ie.evalos.repository.ClientApplicationRepository;
import com.ie.evalos.security.PortalPrincipal;
import com.ie.evalos.security.TenantContext;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The client's request for a service: start it, save it, submit it (Unit 43).
 *
 * <p><strong>No case is created here, by any path.</strong> A case is born only of a won
 * opportunity arriving on the webhook — invariant 8, and {@code DomainInvariantsTest} fails the
 * build if a second class ever injects {@code CaseIntakeService}. What this unit produces is a
 * <em>GHL opportunity</em> and a questionnaire attached to it, which is the thing Sales prices.
 *
 * <p><strong>The service catalog is frontend data and is not mirrored here.</strong> Which
 * questions a service asks, which of them are conditional on earlier answers, and which documents
 * it wants are all in {@code serviceCatalog.ts} / {@code questionGroups.ts}, where adding a
 * service is one entry and no migration. Copying 400 lines of it into Java to re-check
 * completeness would create a second definition that drifts from the first, and the drift would
 * show up as a client being refused for an answer the form never asked. What this class stores is
 * the service id, the name as chosen, and the answers as given.
 *
 * <pre>
 * // ponytail: submit validates shape and size, not completeness — the form is the completeness
 * // gate. That is a real ceiling: a hand-crafted POST can submit an application with no answers.
 * // The blast radius is a salesperson reading a thin application and ringing the client, which
 * // 43 §5 already accepts for documents ("a missing document is a thing Sales chases, not a wall
 * // the funnel puts in front of a lead"). If completeness ever has to be enforced server-side,
 * // move the catalog to the server and serve it to the SPA — do not write a second copy.
 * </pre>
 */
@Service
public class ClientApplicationService {

	/** What the portal shows for one application. */
	public record ApplicationView(UUID id, String serviceId, String serviceName, String purpose,
			String status, String answers, Instant createdAt, Instant updatedAt, Instant submittedAt) {
	}

	/**
	 * The answers payload's ceiling, in characters.
	 *
	 * <p>The largest service in the catalog asks about thirty questions, several of them free
	 * text. 64 KB is far above any honest answer and far below anything that would trouble a
	 * jsonb column — it exists so an unbounded body cannot be posted at a route a signed-in
	 * stranger can reach, not to second-guess a client who types a lot.
	 */
	private static final int MAX_ANSWERS_CHARS = 64 * 1024;

	private final ClientApplicationRepository applications;

	private final ClientAccountRepository accounts;

	private final GhlWriteClient ghl;

	private final GhlPipelineClient pipelines;

	private final String intakePipelineName;

	ClientApplicationService(ClientApplicationRepository applications, ClientAccountRepository accounts,
			GhlWriteClient ghl, GhlPipelineClient pipelines,
			@Value("${evalos.ghl.intake-pipeline-name}") String intakePipelineName) {
		this.applications = applications;
		this.accounts = accounts;
		this.ghl = ghl;
		this.pipelines = pipelines;
		this.intakePipelineName = intakePipelineName;
	}

	/** Every application this client has, newest first. */
	@Transactional(readOnly = true)
	public List<ApplicationView> mine(PortalPrincipal principal) {
		return applications.findByClientAccountIdOrderByCreatedAtDesc(account(principal).getId()).stream()
				.map(ClientApplicationService::view)
				.toList();
	}

	/**
	 * Starts a request, and puts the lead in front of Sales in the same call.
	 *
	 * <p><strong>"In front of Sales" means on the intake pipeline and no further.</strong> EvalOS
	 * creates the opportunity and stops; the stage it lands in, the routing and the assignee are
	 * GHL's automation's, which is what {@code 00b} keeps GHL for.
	 *
	 * <p><strong>The opportunity is created here — at the first screen — and not at submit.</strong>
	 * That is `43` §6a's decision, resolved on 2026-09-15 against the alternative of waiting: the
	 * questionnaire is the longest part of the funnel and therefore exactly where people stop, and
	 * a lead who stops halfway is still a lead a salesperson can ring. Waiting for submit would
	 * leave them on no board at all. Picking a service is also the first moment there is anything
	 * to sell — before it, EvalOS holds an account and no intent.
	 *
	 * <p><strong>{@code createOpportunity}, never {@code upsertOpportunity}.</strong> A client
	 * asking for a second evaluation is a genuine second deal, and upsert means one open
	 * opportunity per contact per pipeline — it would silently overwrite the first deal's name
	 * and value instead of creating the second. This is `39` §3a's escape hatch, taken for the
	 * case that spec named. The duplicate risk it brings back is contained by the one-draft index
	 * rather than by a confirmation dialog: a client cannot have two unfinished requests.
	 *
	 * <p><strong>The row is written before GHL is called.</strong> If GHL refuses, the client
	 * keeps their draft and their answers, and {@link #save} links the opportunity on the next
	 * write. The opposite order loses the client's work to somebody else's outage.
	 */
	@Transactional
	public ApplicationView start(PortalPrincipal principal, String serviceId, String serviceName,
			String purpose) {
		ClientAccount client = account(principal);

		require(serviceId != null && !serviceId.isBlank(), "Choose a service before starting a request.");
		require(serviceName != null && !serviceName.isBlank(), "Choose a service before starting a request.");

		// A second "start" while one is unfinished is the same person having lost the first.
		// Returning it rather than refusing is what makes the Continue button on the dashboard and
		// a stale browser tab agree with each other.
		Optional<ClientApplication> inProgress = applications
				.findByClientAccountIdAndStatus(client.getId(), ClientApplication.Status.DRAFT);
		if (inProgress.isPresent()) {
			return view(linkOpportunityIfMissing(inProgress.get(), client));
		}

		ClientApplication application = applications.saveAndFlush(new ClientApplication(
				client.getBrandId(), client.getId(), serviceId.trim(), serviceName.trim(), trimToNull(purpose)));

		return view(linkOpportunityIfMissing(application, client));
	}

	/** Records the answers so far. Autosaved, so it must be cheap and must never move a stage. */
	@Transactional
	public ApplicationView save(PortalPrincipal principal, UUID applicationId, String answersJson,
			String purpose) {
		ClientAccount client = account(principal);
		ClientApplication application = owned(client, applicationId);

		require(application.isDraft(), "This request has already been submitted.");
		require(answersJson != null && answersJson.length() <= MAX_ANSWERS_CHARS,
				"That is more than we can store against one request.");

		application.saveAnswers(answersJson, purpose);
		return view(linkOpportunityIfMissing(application, client));
	}

	/**
	 * Hands the request to Sales.
	 *
	 * <p><strong>The stage does not move</strong> — it has been hot since {@link #start}. What
	 * changes is {@code status}, which is what the staff screen sorts on.
	 *
	 * <p><strong>The answers are not copied into an {@code OpportunityNote}, and `43` §6b's plan
	 * to do that is not followed.</strong> Two reasons, found in the building: `opportunity_note`
	 * requires an {@code author_id} referencing a {@code team_member}, and a client is not one —
	 * filing a client's answers there would mean making that column nullable, weakening a
	 * constraint so that a row could pretend to be staff prose. And a questionnaire flattened into
	 * note text reads far worse than the same answers rendered against their questions. Sales
	 * reads the application itself, through {@code GET /api/sales/opportunities/\{id\}/application}
	 * — which `43` §6c requires anyway, because "a review step with nowhere to read the thing being
	 * reviewed does not exist". The note stream stays what it is: what a salesperson wrote.
	 *
	 * <p><strong>No opportunity means no submit.</strong> Submitting an application Sales cannot
	 * see is the one failure this flow must not have — it reads to the client as "sent" and to
	 * the business as nothing at all. A GHL outage makes this a 502 and leaves the draft intact.
	 */
	@Transactional
	public ApplicationView submit(PortalPrincipal principal, UUID applicationId) {
		ClientAccount client = account(principal);
		ClientApplication application = owned(client, applicationId);

		require(application.isDraft(), "This request has already been submitted.");

		application = linkOpportunityIfMissing(application, client);
		require(application.getGhlOpportunityId() != null,
				"We could not reach our systems to send this. Please try again in a moment.");

		application.submit();
		return view(application);
	}

	/**
	 * The application behind a deal, for the staff who act on it (§6c), or <strong>null</strong>
	 * when the deal did not come from the portal.
	 *
	 * <p><strong>Null and 200, not a 404.</strong> Most deals on the board were opened by a
	 * salesperson or by Marketing and have no application and never will — "is there one?" is a
	 * question whose honest answer is often no, and an error status for an ordinary answer teaches
	 * the caller to swallow errors. It also keeps a real failure (GHL down, a refused scope)
	 * distinguishable from a phoned-in deal, which is the distinction the panel needs.
	 *
	 * <p><strong>Brand-scoped from the caller's own {@code TenantContext}</strong>, never from the
	 * path: the opportunity id is a GHL id a caller could have got anywhere, and the brand filter
	 * is what makes it their brand's application or nothing at all.
	 */
	@Transactional(readOnly = true)
	public ApplicationView forOpportunity(String ghlOpportunityId) {
		return applications
				.findByBrandIdAndGhlOpportunityId(TenantContext.current().brandId(), ghlOpportunityId)
				.map(ClientApplicationService::view)
				.orElse(null);
	}

	/**
	 * Creates the GHL opportunity if this application has none yet.
	 *
	 * <p><strong>No stage and no assignee are sent, deliberately.</strong> EvalOS puts the
	 * opportunity on the intake pipeline and stops there; where it lands and who picks it up are
	 * <em>GHL's automation's</em> job, which is what {@code 00b}'s truth model says — GHL stays the
	 * pipeline engine and the automation engine underneath. An earlier version of this method
	 * resolved a configured "hot stage" by name and sent its id, which put EvalOS in the business
	 * of deciding pipeline placement: a second opinion about a thing GHL owns, gone stale the first
	 * time somebody reworks the workflow over there. {@code assignedTo} is absent for the reason
	 * {@code SalesOpportunityController} gives — no column links a GHL user to an EvalOS
	 * {@code team_member}, so a guess assigns the deal to the wrong person.
	 *
	 * <p><strong>Idempotent by the column, not by GHL.</strong> `createOpportunity` is not an
	 * upsert, so calling it twice makes two deals — the `ghl_opportunity_id` check is the only
	 * thing preventing that, which is why every caller routes through here instead of calling the
	 * client directly.
	 *
	 * <p><strong>A GHL outage is swallowed on start and save, and refused on submit.</strong> The
	 * difference is what the client loses: on the first two they keep a working draft and the next
	 * write retries, while a submit that returned "sent" with nothing behind it would be a lie.
	 */
	private ClientApplication linkOpportunityIfMissing(ClientApplication application, ClientAccount client) {
		if (application.getGhlOpportunityId() != null || client.getGhlContactId() == null) {
			return application;
		}
		try {
			GhlPipelineClient.Pipeline intake = pipelines.pipelineNamed(intakePipelineName);
			GhlWriteClient.UpsertedOpportunity opened = ghl.createOpportunity(intake.id(),
					client.getGhlContactId(), opportunityName(application, client), null,
					null, null, null);
			application.linkOpportunity(opened.id());
		}
		catch (RuntimeException ghlRefused) {
			// Deliberately not rethrown: `submit` checks the id and refuses there, which is the
			// one moment the client must not be told something untrue. Losing a half-typed
			// questionnaire to an upstream outage is the worse trade.
			return application;
		}
		return application;
	}

	/**
	 * What the deal is called in GHL: the client, then what they asked for.
	 *
	 * <p>The client's name first because a salesperson scans a board for people, and the service
	 * second because the same person can have two open requests. Falls back to the email when no
	 * name was given at sign-up — a deal named after nobody is one a salesperson cannot pick up.
	 */
	private static String opportunityName(ClientApplication application, ClientAccount client) {
		String person = java.util.stream.Stream.of(client.getFirstName(), client.getLastName())
				.filter((part) -> part != null && !part.isBlank())
				.reduce((first, second) -> first + " " + second)
				.orElse(client.getEmail());
		return person + " — " + application.getServiceName();
	}

	/**
	 * The account behind this token, by whichever of its two legal names it carries.
	 *
	 * <p>V44's constraint lets a client party row name either a GHL contact or an EvalOS account,
	 * and {@code mintForClientAccount} writes the first whenever there is one — so the common
	 * signed-in client arrives holding a contact id and nothing else. Both arms are real; neither
	 * is a fallback for a bug.
	 */
	private ClientAccount account(PortalPrincipal principal) {
		Optional<ClientAccount> found = principal.namesAnAccountDirectly()
				? accounts.findById(principal.clientAccountId())
				: Optional.ofNullable(principal.ghlContactId())
						.flatMap((contact) -> accounts.findByBrandIdAndGhlContactId(principal.brandId(), contact));

		return found.filter((account) -> account.getBrandId().equals(principal.brandId()))
				.orElseThrow(() -> new ForbiddenException("This link does not admit you to that"));
	}

	/**
	 * One application, proved to be this client's.
	 *
	 * <p>Compared on the account id rather than read through a per-account finder so that a
	 * request for somebody else's application is a <strong>403 and not an empty result</strong>:
	 * the two are the same to the caller, and only one of them is honest about what happened.
	 */
	private ClientApplication owned(ClientAccount client, UUID applicationId) {
		return applications.findById(applicationId)
				.filter((row) -> row.getClientAccountId().equals(client.getId()))
				.orElseThrow(() -> new ForbiddenException("That request is not one of yours"));
	}

	private static void require(boolean condition, String message) {
		if (!condition) {
			throw new InvalidRequestException(message);
		}
	}

	private static String trimToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private static ApplicationView view(ClientApplication row) {
		return new ApplicationView(row.getId(), row.getServiceId(), row.getServiceName(), row.getPurpose(),
				row.getStatus().name(), row.getAnswers(), row.getCreatedAt(), row.getUpdatedAt(),
				row.getSubmittedAt());
	}
}
