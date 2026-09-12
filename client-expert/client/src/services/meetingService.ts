import { apiClient, unwrap, type ApiResponse } from '@shared/services/apiClient'
import type { ClientMeeting } from '@shared/lib/portal'

/**
 * The client's meetings, from GHL by way of EvalOS.
 *
 * **There is no argument, and there must never be one.** The contact comes off the portal token
 * server-side — a client cannot ask for another client's diary because there is no way to name a
 * contact. Same rule as `invoiceService` and `documentService`.
 *
 * **Nothing is stored anywhere.** Sales books the meeting from their desk, GHL sends the
 * invitation and owns the calendar, and this is a window onto the result. EvalOS holds no
 * appointment row — a local copy would be a second answer to "when is the call".
 *
 * **Requires a party-scoped link.** A case-scoped token answers 403: a meeting belongs to the
 * client, who may have several cases.
 */
export async function listMeetings(): Promise<ClientMeeting[]> {
  return unwrap(apiClient.get<ApiResponse<ClientMeeting[]>>('/client/meetings'))
}
