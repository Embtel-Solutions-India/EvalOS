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
profile (`SPRING_PROFILES_ACTIVE=prod`) has no defaults at all.

**2. Backend** (port 8080; Flyway applies `db/migration` on startup):

```bash
cd backend
./mvnw spring-boot:run
```

- `GET /api/health` → `{"success":true,"data":{"status":"UP","service":"evalos","time":"…"}}`
- `GET /actuator/health`

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
