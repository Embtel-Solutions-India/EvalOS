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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

	private static final Logger log = LoggerFactory.getLogger(ClientApplicationService.class);

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

	/**
	 * The GHL opportunity custom field that carries the requested service, or blank for none.
	 *
	 * <p><strong>This is what lets GHL route the deal, and it is deliberately the only routing
	 * input EvalOS supplies.</strong> A workflow triggered by opportunity-created can branch on a
	 * custom field; it cannot branch on the free text in the opportunity's <em>name</em>, which is
	 * the only place the service appeared before. So EvalOS states the fact it owns — which service
	 * was asked for — and GHL decides which pipeline that belongs on. That division is the same one
	 * that removed the {@code hot-stage-name} property from this class on the business's
	 * instruction: placement is GHL's automation's, and a second opinion here goes stale the first
	 * time somebody reworks the workflow over there.
	 *
	 * <p><strong>The service <em>id</em>, not the display name.</strong> The name is for a human
	 * reading a board and is reworded whenever the catalog is; the id is the stable slug a workflow
	 * condition can be written against. Grouping eighteen services onto four sales pipelines is a
	 * business mapping, and it lives in the workflow for the same reason the stage does.
	 *
	 * <p><strong>Blank omits the field and nothing else changes.</strong> The deal is still opened
	 * on the intake pipeline and still names the person and the service; it simply lands wherever
	 * GHL's default routing puts it. An environment that has not created the field yet must not
	 * lose the lead over it — which is the whole reason the deal is opened at service-pick.
	 */
	private final String serviceFieldId;

	/**
	 * The GHL opportunity custom field that says the questionnaire is finished, or blank for none.
	 *
	 * <p>The companion to {@link #serviceFieldId}, and it exists because of {@code D10}: the deal
	 * is opened when the client picks a service, so a deal on the board does not distinguish
	 * somebody browsing from a finished request. This field does, and it is a second workflow
	 * trigger for GHL to do whatever the business decides with — notify, move a stage, start a
	 * sequence. EvalOS sets the fact and stops.
	 *
	 * <p>Blank skips the call entirely. The request is still submitted in EvalOS, which is where it
	 * lives.
	 */
	private final String submittedFieldId;

	/**
	 * What gets written into that field. A constant, not a property: a value the deployer could
	 * change is one a GHL workflow condition would then have to be kept in step with by hand, and
	 * there is nothing to gain from the two disagreeing.
	 */
	private static final String SUBMITTED_FIELD_VALUE = "SUBMITTED";

	/**
	 * The GHL custom field carrying EvalOS's own opportunity id — the correlation key
	 * ({@code 00d} §6.1), or blank for none.
	 *
	 * <p><strong>This is what makes a retry after a timeout answerable.</strong>
	 * {@code POST /opportunities/} is not idempotent, so a create that times out leaves EvalOS
	 * unable to tell "GHL never got it" from "GHL got it and the answer was lost" — and retrying
	 * blind is precisely how one opportunity becomes two.
	 *
	 * <p><strong>It only works because the row is opened first.</strong> A key minted in memory and
	 * lost to a timeout is a key nothing can search for, which is why the create path below writes
	 * the local row before it calls GHL rather than after.
	 */
	private final String correlationFieldId;

	/** The opportunity mirror (Unit 44d) — where a portal-born deal is recorded before GHL sees it. */
	private final OpportunityMirrorService deals;

	ClientApplicationService(ClientApplicationRepository applications, ClientAccountRepository accounts,
			GhlWriteClient ghl, GhlPipelineClient pipelines,
			@Value("${evalos.ghl.intake-pipeline-name}") String intakePipelineName,
			@Value("${evalos.ghl.opportunity-service-field:}") String serviceFieldId,
			@Value("${evalos.ghl.opportunity-submitted-field:}") String submittedFieldId,
			@Value("${evalos.ghl.opportunity-correlation-field:}") String correlationFieldId,
			OpportunityMirrorService deals) {
		this.applications = applications;
		this.accounts = accounts;
		this.ghl = ghl;
		this.pipelines = pipelines;
		this.intakePipelineName = intakePipelineName;
		this.serviceFieldId = serviceFieldId == null ? "" : serviceFieldId.trim();
		this.submittedFieldId = submittedFieldId == null ? "" : submittedFieldId.trim();
		this.correlationFieldId = correlationFieldId == null ? "" : correlationFieldId.trim();
		this.deals = deals;
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
		markSubmittedInGhl(application);
		return view(application);
	}

	/**
	 * Tells GHL the questionnaire is finished.
	 *
	 * <p><strong>Why a second signal exists at all.</strong> The opportunity is opened when the
	 * client <em>picks</em> a service, not when they submit — {@code D10}, taken so a client who
	 * abandons the questionnaire still reaches a salesperson. The cost of that decision is that a
	 * deal on the board says nothing about whether it is somebody browsing or a finished request,
	 * and Sales would have to open EvalOS to tell them apart. This field is the difference, and it
	 * gives GHL a second workflow trigger — notify, move a stage, start a sequence — without EvalOS
	 * holding any opinion about which.
	 *
	 * <p><strong>Custom fields only, and deliberately through
	 * {@link GhlWriteClient#setOpportunityFields}.</strong> By now GHL's own workflow has very
	 * probably moved this opportunity onto a service-specific pipeline; an update path carrying a
	 * {@code pipelineId} could undo that routing, and that method structurally cannot.
	 *
	 * <p><strong>A failure here does not fail the submit, which is the opposite of the rule one
	 * method up.</strong> That rule exists because an application Sales cannot <em>see</em> reads
	 * to the client as "sent" and to the business as nothing at all. Here Sales can already see the
	 * deal — only the marker is missing — so refusing would throw away a completed questionnaire
	 * over a flag. EvalOS is the source of truth for the request either way; the screen and the
	 * status are right, and GHL is the copy that lags.
	 *
	 * <p>ponytail: swallowed and logged, with no retry, so a GHL outage at this exact moment loses
	 * the marker permanently. The fix is not a retry loop here — it is the outbox in Unit 45, which
	 * is where every EvalOS→GHL write is meant to end up.
	 */
	private void markSubmittedInGhl(ClientApplication application) {
		if (submittedFieldId.isEmpty()) {
			return;
		}
		try {
			ghl.setOpportunityFields(application.getGhlOpportunityId(),
					java.util.Map.of(submittedFieldId, SUBMITTED_FIELD_VALUE));
		}
		catch (RuntimeException ghlRefused) {
			log.warn("Request {} is submitted in EvalOS but GHL was not told: {}", application.getId(),
					ghlRefused.getMessage());
		}
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
			// **The local row first, and the order is the correlation key's whole mechanism.**
			// EvalOS writes its own opportunity, sends that row's id to GHL in a custom field, and
			// only then records GHL's id beside it. A create that times out therefore leaves a row
			// EvalOS can find again, which is what turns "did my create land?" from a guess into a
			// query (`00d` §6.1). A retry reuses the row rather than minting a second one.
			UUID localId = application.getOpportunityId();
			if (localId == null) {
				// Empty when the intake pipeline is not mirrored yet. The deal is still created —
				// losing a client's request because a sweep is behind would be the worse failure —
				// it simply carries no correlation key, which is today's exposure and not a new one.
				localId = deals.openLocally(intake.id(), client.getGhlContactId(),
						opportunityName(application, client)).map(com.ie.evalos.domain.Opportunity::getId)
						.orElse(null);
				if (localId != null) {
					application.linkOpportunityRow(localId);
				}
			}
			GhlWriteClient.UpsertedOpportunity opened = ghl.createOpportunity(intake.id(),
					client.getGhlContactId(), opportunityName(application, client), null,
					// No stage and no assignee — see `serviceFieldId` and `noStageAndNoAssigneeAreSent`.
					// No monetaryValue either: EvalOS holds no price list, and what the work is worth
					// is Sales' to set on the deal. A zero here would be a priced deal worth nothing
					// rather than an unpriced one.
					null, null, createFields(application, localId));
			if (localId != null) {
				deals.linkGhl(localId, opened.id());
			}
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
	/**
	 * What GHL is told on create: the requested service, and the correlation key.
	 *
	 * <p>Two fields with two jobs. The service is what a GHL workflow routes on; the correlation
	 * key is what a retry finds the deal by. Either can be unconfigured, and an unconfigured field
	 * is omitted rather than sent blank — null rather than an empty map, because a caller sending
	 * {@code {}} reads as "this request has nothing on it", which is never true.
	 */
	private java.util.Map<String, String> createFields(ClientApplication application, UUID localId) {
		java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
		if (!serviceFieldId.isEmpty() && application.getServiceId() != null) {
			fields.put(serviceFieldId, application.getServiceId());
		}
		if (!correlationFieldId.isEmpty() && localId != null) {
			fields.put(correlationFieldId, localId.toString());
		}
		return fields.isEmpty() ? null : fields;
	}

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
