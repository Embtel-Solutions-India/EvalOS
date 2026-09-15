package com.ie.evalos.web;

import java.math.BigDecimal;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.service.SalesDeskService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Opening a new deal.
 *
 * <p><strong>Its own controller, for the reason {@link SalesCalendarController} gives.</strong>
 * {@code SalesDeskController} is mapped at {@code /api/sales/opportunities/\{opportunityId\}} —
 * every route on it acts on a deal that already exists. A create has no id yet, so it cannot hang
 * off that mapping without inventing a placeholder.
 *
 * <p><strong>Sales could not create an opportunity at all until now</strong>, and that was
 * deliberate rather than an omission: Unit 40 shipped a desk where every route was a {@code PUT}
 * on an existing deal, because Marketing opened leads and GHL's automation promoted them. This
 * route is the business asking for the other door — a salesperson taking an inbound call for a
 * client nobody has logged yet, or a repeat client buying a second service.
 *
 * <p>Thin (invariant 12): the decisions are all in {@link SalesDeskService}.
 */
@RestController
@RequestMapping("/api/sales/opportunities")
public class SalesOpportunityController {

	private final SalesDeskService desk;

	SalesOpportunityController(SalesDeskService desk) {
		this.desk = desk;
	}

	/**
	 * The form GHL's own "add opportunity" asks for, minus the fields that would be lies here.
	 *
	 * <p><strong>What GHL has and this does not, each for a reason:</strong>
	 * <ul>
	 * <li>{@code pipelineId} — the caller's own, taken from their principal. A form field here
	 *     would let a salesperson write into a colleague's pipeline.</li>
	 * <li>{@code status} — forced to {@code open}. GHL accepts {@code won} on create, which fires
	 *     Handoff A and mints a <strong>paid</strong> case with no payment behind it.</li>
	 * <li>{@code assignedTo} — GHL's user id, which EvalOS does not hold on {@code team_member}.
	 *     Sending a guess would assign the deal to nobody or to the wrong person; GHL's own
	 *     round-robin assigns it correctly when the field is absent.</li>
	 * <li>{@code forecastProbability} — GHL derives it from the stage. A second, manual number
	 *     would disagree with the first.</li>
	 * </ul>
	 *
	 * <p><strong>{@code customFields} IS carried, and an earlier version of this note wrongly said
	 * it was not.</strong> It claimed EvalOS "does not know this location's custom field
	 * definitions… they arrive with the tier-2 mirror". That was assumed rather than checked: the
	 * location defines twenty opportunity fields, and six of them are the intake facts
	 * {@code GhlOpportunityHandler} says "are a PM's to fill in" — Service Requested, Visa
	 * Category, turnaround, and the client's own description of the case. Omitting them would have
	 * meant a salesperson opening a deal that production then has to re-interview the client to
	 * complete. The definitions are read live rather than mirrored, so no tier-2 work is needed to
	 * carry them.
	 *
	 * @param confirmSecondDeal set after the caller has been shown the contact's existing open
	 *                          deal and chosen to open another anyway
	 */
	public record NewDealRequest(
			String firstName,
			String lastName,
			String email,
			String phone,
			@NotBlank String name,
			BigDecimal monetaryValue,
			String stageId,
			String expectedCloseDate,
			/**
			 * GHL custom field values, keyed by the location's own field id.
			 *
			 * <p>The ids come from {@code GET /api/sales/opportunity-fields}, so the client never
			 * invents one. This is where the intake facts live — Service Requested, Visa Category,
			 * turnaround, the client's own description of the case — which is why the form carries
			 * them rather than leaving a PM to chase the client for the same answers later.
			 */
			java.util.Map<String, String> customFields,
			boolean confirmSecondDeal) {
	}

	@PostMapping
	@PreAuthorize("hasRole('SALES')")
	public ApiResponse<SalesDeskService.Deal> create(@RequestBody @Valid NewDealRequest request) {
		return ApiResponse.ok(desk.createDeal(request.firstName(), request.lastName(),
				request.email(), request.phone(), request.name(), request.monetaryValue(),
				request.stageId(), request.expectedCloseDate(), request.customFields(),
				request.confirmSecondDeal()));
	}
}
