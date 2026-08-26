# EvalOS

Back-of-house production CRM for a multi-brand credential-evaluation business.
Monorepo: `backend/` (Java 21 + Spring Boot + PostgreSQL) and `frontend/`
(React + TypeScript + Vite + Tailwind).

Design context lives in `context/` — read `CLAUDE.md` first.

## Prerequisites

- JDK 21+
- Node 20+
- PostgreSQL 14+ running locally

## Local run

**1. Database** — create the database and role the `local` profile expects:

```sql
CREATE ROLE evalos LOGIN PASSWORD 'evalos';
CREATE DATABASE evalos OWNER evalos;
```

Or point at your own with env vars (they override the local defaults):

```bash
export DB_URL=jdbc:postgresql://localhost:5432/evalos
export DB_USER=evalos
export DB_PASSWORD=evalos
```

No secrets are committed — every value comes from the environment. The `prod`
profile (`SPRING_PROFILES_ACTIVE=prod`) has no defaults at all, including
`JWT_SECRET` (32 bytes minimum) and `EVALOS_FIELD_KEY` (base64 of exactly 32
bytes, the AES-256 key for the encrypted `expert.payment_detail`) — it will
refuse to start without either.

**Where a real credential goes on a laptop: `backend/config/application-local.yml`.**
Spring Boot reads `./config/` *after* the classpath, so that file overrides the
committed `application-local.yml`, and `backend/config/` is in `.gitignore` so it
cannot be committed. It survives restarts and needs no shell exports.

```yaml
# backend/config/application-local.yml  — untracked
evalos:
  ghl:
    token: pit-xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx   # full token, `pit-` prefix included
```

**Never paste a credential into `src/main/resources/*.yml`** — those are tracked, and
the app runs with or without the value, so nothing would catch it before a commit.
`ConfigSecretsTest` now fails the build if any `*TOKEN|SECRET|PASSWORD|KEY|CREDENTIAL`
setting carries a default in a committed profile, with a three-entry allowlist for the
documented laptop-only throwaways (`JWT_SECRET`, `EVALOS_FIELD_KEY`, `DB_PASSWORD`).

The GHL token must be a Private Integration Token scoped `opportunities.readonly` and
nothing wider — EvalOS never writes to GHL. It must include the `pit-` prefix: a bare
UUID (36 chars) fails with an HTTP 401 that reads like a scope problem, while a whole
PIT is 40. The startup log prints `token length=` so that is checkable without pasting
the value anywhere. Without it, only the GM's marketing screen is affected — it answers
502 naming what to set, and the rest of the app runs normally.

**2. Backend** (port 8080; Flyway applies `db/migration` on startup):

```bash
cd backend
./mvnw spring-boot:run
```

- `GET /api/health` → `{"success":true,"data":{"status":"UP","service":"evalos","time":"…"}}`
- `GET /actuator/health`

The `local` profile also applies `db/seed-local`, which seeds two brands and
five staff logins — all with the password `DevPassw0rd!`:

| email               | role            | brand                     |
| ------------------- | --------------- | ------------------------- |
| `gm@evalos.local`   | GM              | — (all brands)            |
| `bm.ie@evalos.local`| Brand Manager   | International Evaluations |
| `bm.xp@evalos.local`| Brand Manager   | XpertsPortal              |
| `pm.ie@evalos.local`| Project Manager | International Evaluations |
| `cm.ie@evalos.local`| Case Manager    | International Evaluations |

```bash
TOKEN=$(curl -s localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"gm@evalos.local","password":"DevPassw0rd!"}' | jq -r .data.token)

curl -s localhost:8080/api/me           -H "Authorization: Bearer $TOKEN"
curl -s localhost:8080/api/team-members -H "Authorization: Bearer $TOKEN"
```

Swap the GM token for `bm.ie@evalos.local`'s and `/api/team-members` returns only
that brand's three members. A Case Manager token gets `403`.

**3. Frontend** (port 5173; `/api` is proxied to 8080):

```bash
cd frontend
npm install
npm run dev
```

## Verify

```bash
cd backend  && ./mvnw verify
cd frontend && npm run build
```

`./mvnw verify` needs no database. The persistence checks that do — migrations
apply, `ddl-auto=validate` agrees with every entity, `payment_detail` is
ciphertext on disk, scoped finders keep two brands apart, audit rows cannot be
edited — are one opt-in command against a real PostgreSQL:

```bash
cd backend && ./mvnw test -Devalos.db.test=true -Dtest=LocalPostgresIntegrationTest \
  -DDB_URL=jdbc:postgresql://localhost:5432/evalos
```
