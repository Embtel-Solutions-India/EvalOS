import { apiClient, unwrap, type ApiResponse } from '@shared/services/apiClient'
import type { ClientInvoice } from '@shared/lib/portal'

/**
 * The client's invoices and payment status, from GHL by way of EvalOS (Unit 41).
 *
 * **There is no argument, and there must never be one.** The contact comes off the portal token
 * server-side — a client cannot ask for another client's billing because there is no way to name
 * a contact. Same rule as `documentService`: the case is never named here either.
 *
 * **Nothing is stored anywhere.** EvalOS reads GHL per request and holds no invoice fact. Sales
 * raises invoices in GHL and GHL's QuickBooks integration does the accounting; this is a window
 * onto the result.
 *
 * **Requires a party-scoped link.** A case-scoped token answers 403: it admits the holder to one
 * engagement, and an invoice belongs to the client, who may have several.
 */
export async function listInvoices(): Promise<ClientInvoice[]> {
  return unwrap(apiClient.get<ApiResponse<ClientInvoice[]>>('/client/invoices'))
}
