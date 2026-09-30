package com.ie.evalos.web;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.ContactSnapshot;
import com.ie.evalos.domain.GhlReference;
import com.ie.evalos.domain.Opportunity;
import com.ie.evalos.repository.GhlCustomFieldRepository;
import com.ie.evalos.repository.GhlUserRepository;
import com.ie.evalos.service.ContactSnapshotService;
import com.ie.evalos.service.PipelineScope;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * The deal screen's details: every custom field value the mirror holds, on the deal and on its
 * contact, <strong>by name</strong> — never a raw GHL field id.
 */
class OpportunityContactControllerTest {

	private static final UUID BRAND = UUID.randomUUID();

	private final PipelineScope scope = mock(PipelineScope.class);
	private final ContactSnapshotService contacts = mock(ContactSnapshotService.class);
	private final GhlUserRepository users = mock(GhlUserRepository.class);
	private final GhlCustomFieldRepository fields = mock(GhlCustomFieldRepository.class);
	private final OpportunityContactController controller =
			new OpportunityContactController(scope, contacts, users, fields);

	private static GhlReference.CustomField field(String id, String model, String name) {
		return new GhlReference.CustomField(BRAND, id, model, name);
	}

	@Test
	void namesEveryHeldValueAndSkipsOnesWithNoDefinition() {
		Opportunity deal = new Opportunity(BRAND, "opp-1", UUID.randomUUID());
		deal.syncCustomFields(Map.of("f_service", "Course-by-Course Evaluation", "f_gone", "orphan"));
		given(scope.requireVisible("opp-1")).willReturn(deal);

		ContactSnapshot contact = new ContactSnapshot(BRAND, "c-1");
		contact.syncFromGhl("Farai Mudzviti", "farai@example.test", null, null, null, null, null, null, null);
		contact.syncDetails("US", List.of("stage:hot"), Map.of("f_applicant", "Individual Applicant"));
		given(contacts.findOrFetch(BRAND, null)).willReturn(Optional.of(contact));

		given(fields.findByBrandIdAndModelOrderByNameAsc(BRAND, "opportunity"))
				.willReturn(List.of(field("f_service", "opportunity", "Service Requested"),
						field("f_unset", "opportunity", "Lead Source")));
		given(fields.findByBrandIdAndModelOrderByNameAsc(BRAND, "contact"))
				.willReturn(List.of(field("f_applicant", "contact", "Applicant type")));

		OpportunityContactController.ContactView view = controller.read("opp-1").data();

		// A value with no definition is dropped rather than shown under its id; a definition with
		// no value is not a row.
		assertThat(view.dealFields()).containsExactly(
				new OpportunityContactController.Field("Service Requested", "Course-by-Course Evaluation"));
		assertThat(view.country()).isEqualTo("US");
		assertThat(view.tags()).containsExactly("stage:hot");
		assertThat(view.contactFields()).containsExactly(
				new OpportunityContactController.Field("Applicant type", "Individual Applicant"));
	}

	@Test
	void aDealWithNoContactStillShowsItsOwnFields() {
		Opportunity deal = new Opportunity(BRAND, "opp-2", UUID.randomUUID());
		deal.syncCustomFields(Map.of("f_service", "Evaluation"));
		given(scope.requireVisible("opp-2")).willReturn(deal);
		given(contacts.findOrFetch(BRAND, null)).willReturn(Optional.empty());
		given(fields.findByBrandIdAndModelOrderByNameAsc(BRAND, "opportunity"))
				.willReturn(List.of(field("f_service", "opportunity", "Service Requested")));

		OpportunityContactController.ContactView view = controller.read("opp-2").data();

		assertThat(view.dealFields()).extracting(OpportunityContactController.Field::value)
				.containsExactly("Evaluation");
		assertThat(view.tags()).isEmpty();
		assertThat(view.contactFields()).isEmpty();
	}
}
