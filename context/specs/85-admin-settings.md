# 85 — Admin settings, brand details and two switches (D83)

**Status:** building 2026-10-10. **Audience:** the Administrator only.

## Why

Every integration setting was an environment variable read once at boot and baked into a cached object
(GHL's `RestClient` carries the token, Boot's `JavaMailSender` the SMTP credentials, ten services copied the
selling-brand id). Rotating an SMTP password or a GHL token meant editing the deployment and restarting.

## What the Administrator can change, with no restart

| Group | Settings | Env var it overrides |
|---|---|---|
| Email | host, port, username, password (secret), from address, **outbound email on/off** | `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `EVALOS_MAIL_FROM` |
| GoHighLevel | Private Integration Token (secret), location id, opportunity correlation field, **GHL writes on/off** | `GHL_API_TOKEN`, `GHL_LOCATION_ID`, `GHL_OPPORTUNITY_CORRELATION_FIELD` |
| Selling brand | the brand that owns the GHL location (slug or id) | `GHL_SALES_BRAND_ID` |
| Business targets | cases per Case Manager, monthly expert-onboarding target, won-deal lookback days | `WORKLOAD_CASES_PER_CM`, `ROSTER_MONTHLY_TARGET`, `SALES_WON_LOOKBACK_DAYS` |
| Brands (Brands page) | name, currency, payout term days | — (was migration-only) |

**Precedence:** a value saved in the app wins; "Reset to environment" deletes it and the env var applies again.
The screen shows each value's source (Set in app / From environment / Not set).

## Never editable in the app

`JWT_SECRET`, `EVALOS_FIELD_KEY`, DB credentials (needed before the database can be read);
`ADMIN_EMAIL`/`ADMIN_PASSWORD_HASH` (seed); `EVALOS_GHL_WRITE_MODE` (stub is a boot-time bean); S3, Ably, VAPID,
portal origins and brands, job intervals. A brand's webhook token and signing secret stay hidden and
migration-only. Still **one GHL location per deployment** — per-brand GHL is Unit 25.

## Shape

- `app_setting(key PK, value, updated_at, updated_by)` (`V90`). Deployment-wide, so not brand-scoped — it
  configures the server, not a brand's rows. Cleared = row deleted.
- Secrets are AES-GCM ciphertext through `PaymentDetailConverter` (keyed by `EVALOS_FIELD_KEY`); **no API response
  ever carries a secret, not even masked** — only whether it is set. Audit rows record "set"/"cleared".
- `AppSettings.app(Setting)` returns the app-saved value; each consumer keeps its env value and asks for the
  override per use. Cache refreshed on every save here, at most 30 s stale on another instance.
- Consumers: `SmtpMailTransport` builds its sender from the effective values (rebuilt only when they change);
  `GhlHttp` rebuilds its client when the token changes, on the same bean so the shared pacer survives;
  `SellingBrand.id()` resolves per call; the ten services that copied the id now call it; the three targets
  are read per use.
- **Outbound email off** → the transport reports itself unconfigured, i.e. the existing `MAIL_UNAVAILABLE` path.
- **GHL writes off** → `post`/`put`/`delete` throw `GhlFailure.PAUSED` (retriable, stops everything): the outbox
  halts and keeps its rows pending, and drains when writes come back on. Reads (including `search`) continue.
  A direct create answers 502 "GHL writes are paused by an administrator".
- Test buttons: **Send test email** (to the signed-in Administrator's own address — invariant 14 amended for
  exactly this) and **Test connection** (one GHL read of the location).
- Routes: `GET/PUT /api/settings`, `POST /api/settings/mail/test`, `POST /api/settings/ghl/test` (Admin area);
  `PUT /api/brands/{id}` (Admin).

## Known consequences

- Changing the selling brand re-attributes **future** mirror rows only; existing rows keep the old brand.
- Rotating `EVALOS_FIELD_KEY` makes saved secrets unreadable: re-enter them after a key rotation.
- A changed GHL location applies to the next call; the mirrors refill on their own schedule.
