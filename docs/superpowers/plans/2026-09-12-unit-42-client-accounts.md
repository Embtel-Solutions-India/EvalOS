# Unit 42 — Client Accounts and the Sign-In Door: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the EvalOS Client Portal a real front door — a welcome screen, email-first sign-in, and password set/reset over SMTP — without introducing a second credential type.

**Architecture:** A successful password check does not create a new kind of session. It mints the **party-scoped `PortalAccess` token Unit 35 already built**, so `PortalTokenFilter` and every screen behind it are untouched. Two new tables (`client_account`, `client_credential_token`) plus one mailer. The only security-config change is `permitAll` on `/api/portal/auth/**`.

**Tech Stack:** Java 21, Spring Boot (Web MVC), Spring Data JPA/Hibernate, Flyway, Spring Security (BCrypt), `spring-boot-starter-mail`, PostgreSQL. Tests: JUnit 5 + AssertJ + Mockito (BDD style). Build: Maven via `./mvnw`. Frontend: React + TypeScript + Vite + Tailwind in `client-expert/client/`.

**Spec:** `context/specs/42-client-accounts.md` — read it alongside this plan. The programme context is `context/specs/00c-ghl-independence-programme.md`.

## Global Constraints

- **Brand-scoped by default.** Every scoped query filters by `brand_id`. A query without brand scoping is a bug. `client_account`'s unique key is `(brand_id, email)`.
- **Append-only truth.** Audit rows are never updated or deleted. Use `AuditService.recordPortalEvent` for client-actor events.
- **Migrations are never edited in place.** The latest applied migration is `V42__scheduled_job.sql`; this unit adds `V43` and `V44`.
- **No credential may carry a default in a shared profile.** `ConfigSecretsTest` fails the build otherwise. `spring.mail.password` and `spring.mail.username` get `${VAR:}` with an empty default and the real value lives in `backend/config/application-local.yml` (gitignored) or the environment.
- **Tokens are stored as SHA-256, never in plaintext.** Reuse `PortalAccessService.hash(String)` — it is already package-visible `static`.
- **The portal brand comes from `evalos.portal.client-brand`**, a new setting. It is NOT `evalos.ghl.sales-brand` — see spec §8.
- **Invariant 8 is untouched.** Nothing in this unit may reach `CaseIntakeService`.
- Java: tabs for indentation, matching every file in `backend/src/main/java`.

---

## File Structure

**Backend — create:**

| File | Responsibility |
| --- | --- |
| `db/migration/V43__client_account.sql` | the two tables |
| `db/migration/V44__seed_client_accounts.sql` | backfill from `contact_snapshot` |
| `domain/ClientAccount.java` | the account entity |
| `domain/ClientCredentialToken.java` | set/reset token entity |
| `domain/CredentialPurpose.java` | `SET` \| `RESET` |
| `repository/ClientAccountRepository.java` | lookup by brand + email |
| `repository/ClientCredentialTokenRepository.java` | lookup by hash |
| `service/ClientAccountService.java` | identify / sign-in / forgot / set-password |
| `service/ClientMailer.java` | the two messages, and nothing else |
| `web/ClientAuthController.java` | the four routes |

**Backend — modify:**

| File | Change |
| --- | --- |
| `service/PortalAccessService.java` | add `mintForClientAccount`, and amend the javadoc that refuses it |
| `security/PortalSecurityConfig.java:96` | one `permitAll` matcher |
| `domain/AuditAction.java` | three new constants |
| `pom.xml` | `spring-boot-starter-mail` |
| `resources/application.yml`, `-local.yml`, `-prod.yml` | mail + portal brand settings |

**Frontend — create** (in `client-expert/client/src/`): `pages/auth/Welcome.tsx`, `pages/auth/SignIn.tsx`, `pages/auth/SetPassword.tsx`, `services/authService.ts`. **Modify:** `App.tsx` (four routes).

---

## Task 1: The schema and the entities

**Files:**
- Create: `backend/src/main/resources/db/migration/V43__client_account.sql`
- Create: `backend/src/main/java/com/ie/evalos/domain/ClientAccount.java`
- Create: `backend/src/main/java/com/ie/evalos/domain/CredentialPurpose.java`
- Create: `backend/src/main/java/com/ie/evalos/domain/ClientCredentialToken.java`
- Create: `backend/src/main/java/com/ie/evalos/repository/ClientAccountRepository.java`
- Create: `backend/src/main/java/com/ie/evalos/repository/ClientCredentialTokenRepository.java`
- Test: `backend/src/test/java/com/ie/evalos/domain/ClientAccountTest.java`

**Interfaces:**
- Consumes: `ScopedEntity` (existing base class carrying `id` and `brandId`).
- Produces: `ClientAccount` with `getId()`, `getBrandId()`, `getEmail()`, `getPasswordHash()`, `hasPassword()`, `setPasswordHash(String)`, `getGhlContactId()`, `linkGhlContact(String)`, `recordSignIn(Instant)`. `ClientCredentialToken` with `getClientAccountId()`, `getPurpose()`, `getExpiresAt()`, `getUsedAt()`, `markUsed(Instant)`, `isUsable(Instant)`. Repositories: `ClientAccountRepository.findByBrandIdAndEmailIgnoreCase(UUID, String)` → `Optional<ClientAccount>`; `ClientCredentialTokenRepository.findByTokenHash(String)` → `Optional<ClientCredentialToken>`.

- [ ] **Step 1: Write the migration**

Create `V43__client_account.sql`:

```sql
-- Unit 42 — the client's own account, and the tokens that set its password.
--
-- **`password_hash IS NULL` is the "never set a password" state.** There is deliberately no
-- status column beside it: a second column stating what the first already states is a second
-- thing to keep in step. Every account seeded from contact_snapshot (V44) starts here.
--
-- **`ghl_contact_id` is a LINK, not the identity.** Invariant 7 as amended by this unit: GHL's
-- contact id stays canonical in GHL, but a client's ability to sign in does not depend on GHL
-- holding a row. It is nullable forever, and V44 seeds it NULL because IE's GHL sub-account was
-- replaced on 2026-09-11 and every id EvalOS holds names a contact that no longer exists.

create table client_account (
    id              uuid primary key,
    brand_id        uuid        not null references brand (id),
    email           text        not null,
    password_hash   text,
    ghl_contact_id  text,
    first_name      text,
    last_name       text,
    phone           text,
    country         text,
    created_at      timestamptz not null default now(),
    last_sign_in_at timestamptz
);

-- Case-insensitive, because a client who signs up as Ana@x.com and signs in as ana@x.com is one
-- person. Done as an index on lower(email) rather than the citext extension: no extension to
-- install, and the repository's findBy...EmailIgnoreCase generates a matching lower() predicate.
create unique index client_account_brand_email_key
    on client_account (brand_id, lower(email));

create index client_account_ghl_contact_idx
    on client_account (brand_id, ghl_contact_id)
    where ghl_contact_id is not null;

-- **Only the hash, never the token** — the rule PortalAccess already follows. A database read
-- (a backup, a support query, a leaked dump) yields no working link.
create table client_credential_token (
    id                uuid primary key,
    brand_id          uuid        not null references brand (id),
    client_account_id uuid        not null references client_account (id),
    token_hash        text        not null unique,
    purpose           text        not null check (purpose in ('SET', 'RESET')),
    expires_at        timestamptz not null,
    used_at           timestamptz,
    created_at        timestamptz not null default now()
);

create index client_credential_token_account_idx
    on client_credential_token (client_account_id);
```

- [ ] **Step 2: Write the failing test**

Create `ClientAccountTest.java`:

```java
package com.ie.evalos.domain;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The account's own rules. Nothing here touches a database: what is being pinned is that
 * "has no password" is derived from the hash rather than stored beside it.
 */
class ClientAccountTest {

	private static final UUID BRAND = UUID.randomUUID();

	@Test
	void seededAccountHasNoPassword() {
		ClientAccount account = new ClientAccount(BRAND, "ana@example.com");

		assertThat(account.hasPassword()).isFalse();
		assertThat(account.getPasswordHash()).isNull();
	}

	@Test
	void settingAHashGivesItAPassword() {
		ClientAccount account = new ClientAccount(BRAND, "ana@example.com");

		account.setPasswordHash("$2a$10$abcdefghijklmnopqrstuv");

		assertThat(account.hasPassword()).isTrue();
	}

	@Test
	void aCredentialTokenIsUsableOnceAndThenNeverAgain() {
		Instant now = Instant.parse("2026-09-12T10:00:00Z");
		ClientCredentialToken token = new ClientCredentialToken(
				BRAND, UUID.randomUUID(), "hash", CredentialPurpose.SET, now.plusSeconds(1800));

		assertThat(token.isUsable(now)).isTrue();

		token.markUsed(now);

		assertThat(token.isUsable(now)).isFalse();
	}

	@Test
	void anExpiredCredentialTokenIsNotUsable() {
		Instant issued = Instant.parse("2026-09-12T10:00:00Z");
		ClientCredentialToken token = new ClientCredentialToken(
				BRAND, UUID.randomUUID(), "hash", CredentialPurpose.RESET, issued.plusSeconds(1800));

		assertThat(token.isUsable(issued.plusSeconds(1801))).isFalse();
	}
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=ClientAccountTest`
Expected: FAIL — compilation error, `ClientAccount` does not exist.

- [ ] **Step 4: Write the entities**

`CredentialPurpose.java`:

```java
package com.ie.evalos.domain;

/** Why a credential token was minted. The database check constraint in {@code V43} mirrors this. */
public enum CredentialPurpose {

	/** First password, for an account that has never had one — including every seeded account. */
	SET,

	/** Replacement password, for an account that has one and whose owner cannot remember it. */
	RESET
}
```

`ClientAccount.java`:

```java
package com.ie.evalos.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * A client's own EvalOS account: the thing they sign in with (Unit 42).
 *
 * <p><strong>{@code passwordHash == null} is the "never set a password" state</strong>, and it is
 * what {@code identify} answers {@code NO_PASSWORD} on. There is no status column beside it: a
 * second field stating what the first already states is a second thing to keep in step.
 *
 * <p><strong>{@code ghlContactId} is a link, not the identity.</strong> Invariant 7 as amended by
 * this unit — GHL's contact id remains canonical <em>in GHL</em>, but a client's ability to sign
 * in no longer depends on GHL holding a row. It is null on every seeded account, because IE's GHL
 * sub-account was replaced on 2026-09-11 and every id EvalOS held names a contact that is gone.
 */
@Entity
@Table(name = "client_account")
public class ClientAccount extends ScopedEntity {

	@Column(name = "email", nullable = false, updatable = false)
	private String email;

	@Column(name = "password_hash")
	private String passwordHash;

	@Column(name = "ghl_contact_id")
	private String ghlContactId;

	@Column(name = "first_name")
	private String firstName;

	@Column(name = "last_name")
	private String lastName;

	@Column(name = "phone")
	private String phone;

	@Column(name = "country")
	private String country;

	@Column(name = "last_sign_in_at")
	private Instant lastSignInAt;

	protected ClientAccount() {
		// for JPA
	}

	public ClientAccount(UUID brandId, String email) {
		super(brandId);
		this.email = email;
	}

	/** Whether this account can be signed into with a password today. */
	public boolean hasPassword() {
		return passwordHash != null && !passwordHash.isBlank();
	}

	public String getEmail() {
		return email;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public void setPasswordHash(String passwordHash) {
		this.passwordHash = passwordHash;
	}

	public String getGhlContactId() {
		return ghlContactId;
	}

	/** Records the contact GHL created for this client. Never mints one — invariant 7. */
	public void linkGhlContact(String ghlContactId) {
		this.ghlContactId = ghlContactId;
	}

	public void recordSignIn(Instant at) {
		this.lastSignInAt = at;
	}

	public Instant getLastSignInAt() {
		return lastSignInAt;
	}

	public String getFirstName() {
		return firstName;
	}

	public void setFirstName(String firstName) {
		this.firstName = firstName;
	}

	public String getLastName() {
		return lastName;
	}

	public void setLastName(String lastName) {
		this.lastName = lastName;
	}

	public String getPhone() {
		return phone;
	}

	public void setPhone(String phone) {
		this.phone = phone;
	}

	public String getCountry() {
		return country;
	}

	public void setCountry(String country) {
		this.country = country;
	}
}
```

`ClientCredentialToken.java`:

```java
package com.ie.evalos.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * A single-use link that lets somebody set a password (Unit 42).
 *
 * <p><strong>Only the SHA-256 is stored</strong>, exactly as {@code PortalAccess} stores its own:
 * a database read yields no working link. The token itself exists once, in the email.
 *
 * <p><strong>Single use is enforced by {@code usedAt}, not by deletion.</strong> A deleted row
 * cannot tell a second visitor that their link was already spent, and the difference between
 * "already used" and "never existed" is worth keeping for support.
 */
@Entity
@Table(name = "client_credential_token")
public class ClientCredentialToken extends ScopedEntity {

	@Column(name = "client_account_id", nullable = false, updatable = false)
	private UUID clientAccountId;

	@Column(name = "token_hash", nullable = false, updatable = false)
	private String tokenHash;

	@Enumerated(EnumType.STRING)
	@Column(name = "purpose", nullable = false, updatable = false)
	private CredentialPurpose purpose;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(name = "used_at")
	private Instant usedAt;

	protected ClientCredentialToken() {
		// for JPA
	}

	public ClientCredentialToken(UUID brandId, UUID clientAccountId, String tokenHash,
			CredentialPurpose purpose, Instant expiresAt) {
		super(brandId);
		this.clientAccountId = clientAccountId;
		this.tokenHash = tokenHash;
		this.purpose = purpose;
		this.expiresAt = expiresAt;
	}

	/** Unused and unexpired. Both halves, because either alone lets a spent link work again. */
	public boolean isUsable(Instant now) {
		return usedAt == null && now.isBefore(expiresAt);
	}

	public void markUsed(Instant at) {
		this.usedAt = at;
	}

	public UUID getClientAccountId() {
		return clientAccountId;
	}

	public CredentialPurpose getPurpose() {
		return purpose;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getUsedAt() {
		return usedAt;
	}
}
```

`ClientAccountRepository.java`:

```java
package com.ie.evalos.repository;

import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.ClientAccount;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientAccountRepository extends JpaRepository<ClientAccount, UUID> {

	/**
	 * The one lookup sign-in needs. <strong>Brand-scoped</strong>, matching the
	 * {@code (brand_id, lower(email))} unique index — a query here without the brand would be a
	 * cross-brand read of a credential.
	 */
	Optional<ClientAccount> findByBrandIdAndEmailIgnoreCase(UUID brandId, String email);
}
```

`ClientCredentialTokenRepository.java`:

```java
package com.ie.evalos.repository;

import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.ClientCredentialToken;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientCredentialTokenRepository extends JpaRepository<ClientCredentialToken, UUID> {

	/**
	 * <strong>By hash, and deliberately not brand-scoped.</strong> The hash is 256 bits of
	 * {@code SecureRandom} and is globally unique; requiring a brand here would mean taking one
	 * from the request, which is exactly the input a caller must not control on a credential
	 * lookup. The brand is then read off the row that comes back.
	 */
	Optional<ClientCredentialToken> findByTokenHash(String tokenHash);
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=ClientAccountTest`
Expected: PASS, 4 tests.

- [ ] **Step 6: Run the full suite to confirm the migration applies**

Run: `cd backend && ./mvnw test`
Expected: PASS. If Flyway reports a checksum or ordering problem, the migration number collides — confirm `V43` is unused with `ls src/main/resources/db/migration`.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/db/migration/V43__client_account.sql \
        backend/src/main/java/com/ie/evalos/domain/ClientAccount.java \
        backend/src/main/java/com/ie/evalos/domain/ClientCredentialToken.java \
        backend/src/main/java/com/ie/evalos/domain/CredentialPurpose.java \
        backend/src/main/java/com/ie/evalos/repository/ClientAccountRepository.java \
        backend/src/main/java/com/ie/evalos/repository/ClientCredentialTokenRepository.java \
        backend/src/test/java/com/ie/evalos/domain/ClientAccountTest.java
git commit -m "feat(42): the client account table, and the tokens that set its password"
```

---

## Task 2: The mail channel

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yml`, `application-local.yml`, `application-prod.yml`
- Create: `backend/src/main/java/com/ie/evalos/service/ClientMailer.java`
- Test: `backend/src/test/java/com/ie/evalos/service/ClientMailerTest.java`

**Interfaces:**
- Produces: `ClientMailer.sendSetPassword(String toEmail, String link)`, `ClientMailer.sendResetPassword(String toEmail, String link)`, `ClientMailer.isConfigured()` → `boolean`.

- [ ] **Step 1: Add the dependency**

In `backend/pom.xml`, beside the other `spring-boot-starter-*` entries (no `<version>` — the parent manages it):

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-mail</artifactId>
		</dependency>
```

- [ ] **Step 2: Add the settings**

In `application.yml`, inside the existing `evalos:` block:

```yaml
  mail:
    # Unit 42 — EvalOS's ONLY outbound mail, and invariant 14 is amended to exactly this much:
    # proving control of a client's own address. Set-password and reset-password, nothing else.
    # A status email, a marketing email or a notification email is a different decision and gets
    # argued in its own unit.
    #
    # **Blank degrades rather than failing the boot.** An environment that forgets it serves the
    # whole app and answers NO_PASSWORD with "please contact us" — the same reasoning that gave
    # GHL_API_TOKEN an empty default rather than a fatal one. A password reset is recoverable by
    # a support conversation; a refused boot is not recoverable by anyone.
    from: ${EVALOS_MAIL_FROM:}
  portal:
    # Which brand this portal deployment serves. **NOT evalos.ghl.sales-brand** — that one names
    # which brand's STAFF may hold the SALES and MARKETING roles, a staff-authorization ceiling
    # on the normal chain. Nothing on the portal chain has roles. A second brand is a second
    # portal deployment with its own value here.
    client-brand: ${EVALOS_PORTAL_CLIENT_BRAND:}
    # How long a set-password or reset-password link lives. Thirty minutes: long enough to walk
    # to another device, short enough that a forwarded mailbox is not a standing credential.
    credential-ttl: ${EVALOS_PORTAL_CREDENTIAL_TTL:30m}
```

And at the top level of the same file, beside `spring.datasource`:

```yaml
spring:
  mail:
    host: ${MAIL_HOST:}
    port: ${MAIL_PORT:587}
    username: ${MAIL_USERNAME:}
    # **Never a value here, not even locally** — ConfigSecretsTest fails the build on a
    # credential-shaped setting carrying a default in a shared profile. The real value goes in
    # backend/config/application-local.yml (gitignored) or the environment.
    password: ${MAIL_PASSWORD:}
    properties:
      mail.smtp.auth: true
      mail.smtp.starttls.enable: true
```

In `application-local.yml` and `application-prod.yml`, mirror the same keys with the same `${VAR:}` placeholders and **no values**, following the "every knob visible in one place" convention those files already state. In `application-local.yml` only, set `evalos.portal.client-brand: ${EVALOS_PORTAL_CLIENT_BRAND:11111111-1111-1111-1111-111111111111}` — the seeded IE brand id, for the same reason `location-id` carries one there (it is not a credential, and it is the value correct for whoever runs this repo).

- [ ] **Step 3: Write the failing test**

Create `ClientMailerTest.java`:

```java
package com.ie.evalos.service;

import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * What the one mail channel EvalOS has will and will not do.
 *
 * <p>The interesting assertion is {@link #blankFromMeansNotConfigured()}: a missing sender must
 * degrade the sign-in flow, not throw from inside it.
 */
class ClientMailerTest {

	private final JavaMailSender sender = mock(JavaMailSender.class);

	@Test
	void setPasswordMailCarriesTheLink() {
		ClientMailer mailer = new ClientMailer(sender, "noreply@internationalevaluations.com");

		mailer.sendSetPassword("ana@example.com", "https://portal.example.com/set-password#tok");

		var captor = forClass(SimpleMailMessage.class);
		verify(sender).send(captor.capture());
		SimpleMailMessage sent = captor.getValue();
		assertThat(sent.getTo()).containsExactly("ana@example.com");
		assertThat(sent.getFrom()).isEqualTo("noreply@internationalevaluations.com");
		assertThat(sent.getText()).contains("https://portal.example.com/set-password#tok");
	}

	@Test
	void blankFromMeansNotConfigured() {
		ClientMailer mailer = new ClientMailer(sender, "");

		assertThat(mailer.isConfigured()).isFalse();

		mailer.sendSetPassword("ana@example.com", "https://portal.example.com/set-password#tok");

		verify(sender, never()).send(any(SimpleMailMessage.class));
	}

	@Test
	void resetMailIsADifferentMessageFromSetMail() {
		ClientMailer mailer = new ClientMailer(sender, "noreply@internationalevaluations.com");

		var captor = forClass(SimpleMailMessage.class);
		mailer.sendSetPassword("ana@example.com", "https://x/#a");
		mailer.sendResetPassword("ana@example.com", "https://x/#b");
		verify(sender, org.mockito.Mockito.times(2)).send(captor.capture());

		assertThat(captor.getAllValues().get(0).getSubject())
				.isNotEqualTo(captor.getAllValues().get(1).getSubject());
	}

	private static <T> T any(Class<T> type) {
		return org.mockito.ArgumentMatchers.any(type);
	}
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=ClientMailerTest`
Expected: FAIL — `ClientMailer` does not exist.

- [ ] **Step 5: Write the mailer**

```java
package com.ie.evalos.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * The only mail EvalOS sends (Unit 42).
 *
 * <p><strong>Two messages, and this is the boundary.</strong> Invariant 14 used to read "EvalOS
 * hosts no files and sends no email"; it now reads "…and sends email for exactly one purpose:
 * proving control of a client's own address". Set a password, reset a password. A status update,
 * a marketing message or a notification is a <em>different decision</em> and gets argued in its
 * own unit — and the first feature that asks for "just a quick email to the client" will present
 * itself as an obvious extension of this class. It is not.
 *
 * <p><strong>A blank sender degrades, it does not throw.</strong> An environment with no mail
 * configured still serves every screen; {@code ClientAccountService} asks {@link #isConfigured()}
 * and tells the client to contact support instead. A password reset is recoverable by a human; a
 * refused boot is not.
 */
@Service
public class ClientMailer {

	private static final Logger log = LoggerFactory.getLogger(ClientMailer.class);

	private final JavaMailSender sender;

	private final String from;

	ClientMailer(JavaMailSender sender, @Value("${evalos.mail.from:}") String from) {
		this.sender = sender;
		this.from = from == null ? "" : from.trim();
		if (this.from.isBlank()) {
			log.warn("evalos.mail.from is not set — client password mail is disabled. "
					+ "Sign-in still works for accounts that already have a password.");
		}
	}

	public boolean isConfigured() {
		return !from.isBlank();
	}

	public void sendSetPassword(String toEmail, String link) {
		send(toEmail, "Set your password",
				"""
				Welcome.

				Use the link below to set a password for your account. It works once and expires \
				in 30 minutes.

				%s

				If you did not expect this, you can ignore it — nothing changes until the link is \
				used.
				""".formatted(link));
	}

	public void sendResetPassword(String toEmail, String link) {
		send(toEmail, "Reset your password",
				"""
				Use the link below to choose a new password. It works once and expires in 30 \
				minutes.

				%s

				If you did not ask for this, you can ignore it — your current password still works.
				""".formatted(link));
	}

	private void send(String toEmail, String subject, String body) {
		if (!isConfigured()) {
			// Not an exception: the caller has already decided what to tell the client, and a
			// throw here would turn a configuration gap into a 500 on a sign-in attempt.
			log.warn("Mail not configured — '{}' to {} was not sent", subject, toEmail);
			return;
		}
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(from);
		message.setTo(toEmail);
		message.setSubject(subject);
		message.setText(body);
		sender.send(message);
	}
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=ClientMailerTest`
Expected: PASS, 3 tests.

- [ ] **Step 7: Confirm the secrets test still passes**

Run: `cd backend && ./mvnw test -Dtest=ConfigSecretsTest`
Expected: PASS. If it fails naming `spring.mail.password`, a value was committed — replace it with `${MAIL_PASSWORD:}`.

- [ ] **Step 8: Commit**

```bash
git add backend/pom.xml backend/src/main/resources/application*.yml \
        backend/src/main/java/com/ie/evalos/service/ClientMailer.java \
        backend/src/test/java/com/ie/evalos/service/ClientMailerTest.java
git commit -m "feat(42): EvalOS gains SMTP, for authentication mail and nothing else"
```

---

## Task 3: Minting a token for an account — and the refusal being reversed

**Files:**
- Modify: `backend/src/main/java/com/ie/evalos/service/PortalAccessService.java`
- Modify: `backend/src/main/java/com/ie/evalos/domain/AuditAction.java`
- Test: `backend/src/test/java/com/ie/evalos/service/PortalAccessServiceTest.java` (append)

**Interfaces:**
- Consumes: `PortalAccess.forParty(UUID brandId, PortalAudience, String ghlContactId, UUID expertId, String tokenHash, Instant expiresAt)`; `PortalAccessService.hash(String)`; the private `freshToken()`, `urlFor(...)`, `retirePreviousClientParty(...)`.
- Produces: `PortalAccessService.mintForClientAccount(ClientAccount account)` → `MintedLink(String url, Instant expiresAt)`.

> **⚠️ Read this before writing the method.** `PortalAccessService.mintForParty`'s javadoc currently says:
>
> > *"The party is derived from a case, never taken from the caller. There is deliberately no `mintForContact(ghlContactId)` entry point: an id arriving from a request would make this an enumeration surface — type contact ids until one mints — and the staff flow does not need it."*
>
> This task adds exactly the entry point that refuses. **The refusal was correct and its reasoning still holds** — what changes is the precondition. The enumeration risk came from an *unauthenticated id in a request body*. Here the caller has already proved possession of a password, so there is nothing to enumerate: you cannot mint for an account you cannot authenticate as. **Amend that javadoc in the same commit.** A deliberate constraint that disappears without a written reason is how this codebase loses its rules.

- [ ] **Step 1: Add the audit actions**

In `AuditAction.java`, beside `PORTAL_LINK_ISSUED`:

```java
	/**
	 * A client signed in with a password (Unit 42). The actor is {@code CLIENT}, never
	 * {@code STAFF} — invariant 13's vocabulary already has the distinction.
	 */
	CLIENT_SIGNED_IN,

	/**
	 * A password sign-in was refused. <strong>Audited deliberately.</strong> A failed sign-in is
	 * the one event a support conversation actually needs, and an unaudited one is invisible.
	 */
	CLIENT_SIGN_IN_REFUSED,

	/** A client set or reset their own password through an emailed single-use link (Unit 42). */
	CLIENT_PASSWORD_SET,
```

- [ ] **Step 2: Write the failing test**

Append to `PortalAccessServiceTest.java`:

```java
	@Test
	void mintForClientAccountIssuesAPartyTokenAndRetiresThePrevious() {
		UUID brand = UUID.randomUUID();
		ClientAccount account = new ClientAccount(brand, "ana@example.com");
		account.linkGhlContact("ghl-contact-1");
		PortalAccess previous = PortalAccess.forParty(brand, PortalAudience.CLIENT, "ghl-contact-1",
				null, "old-hash", Instant.now().plus(Duration.ofDays(7)));
		given(tokens.findByBrandIdAndGhlContactIdAndAudienceAndCaseIdIsNullOrderByCreatedAtDesc(
				brand, "ghl-contact-1", PortalAudience.CLIENT)).willReturn(List.of(previous));
		given(tokens.save(any(PortalAccess.class))).willAnswer(call -> call.getArgument(0));

		PortalAccessService.MintedLink link = service.mintForClientAccount(account);

		assertThat(link.url()).isNotBlank();
		assertThat(previous.getRevokedAt()).isNotNull();
	}

	@Test
	void mintForClientAccountWithNoGhlContactStillMints() {
		UUID brand = UUID.randomUUID();
		ClientAccount account = new ClientAccount(brand, "ana@example.com");
		given(tokens.save(any(PortalAccess.class))).willAnswer(call -> call.getArgument(0));

		PortalAccessService.MintedLink link = service.mintForClientAccount(account);

		assertThat(link.url()).isNotBlank();
	}
```

Add the imports the new tests need: `com.ie.evalos.domain.ClientAccount`.

- [ ] **Step 3: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=PortalAccessServiceTest`
Expected: FAIL — `mintForClientAccount` does not exist.

- [ ] **Step 4: Amend the javadoc on `mintForParty`**

Replace the paragraph quoted in the box above with:

```java
	 * <p><strong>The party is derived from a case here, never taken from the caller</strong> — and
	 * that remains true of <em>this</em> method. An id arriving from a request would make it an
	 * enumeration surface: type contact ids until one mints.
	 *
	 * <p><strong>Unit 42 adds {@link #mintForClientAccount} anyway, and the refusal above is why
	 * it looks the way it does.</strong> That method takes a {@link ClientAccount} the caller has
	 * <em>already authenticated as</em> — a password was verified before it is reached — so there
	 * is nothing to enumerate: you cannot mint for an account you cannot sign in to. It takes an
	 * entity rather than an id precisely so that no route can pass one in from a request body.
```

- [ ] **Step 5: Write the method**

Add below `mintForParty`:

```java
	/**
	 * Mints a party-scoped client link for an account whose password has just been verified
	 * (Unit 42).
	 *
	 * <p><strong>This is not a new credential.</strong> It is the same party-scoped
	 * {@code PortalAccess} Unit 35 built and the staff mint button issues, so every screen behind
	 * {@code PortalTokenFilter} consumes it without knowing an account exists. Sign-in is a new
	 * <em>way to obtain</em> the existing credential, not a second kind of session — which is why
	 * this unit adds no security filter chain.
	 *
	 * <p><strong>It takes a {@link ClientAccount}, deliberately, not an email or a contact id.</strong>
	 * See {@link #mintForParty}'s note on enumeration: an entity can only be produced by a lookup
	 * the caller has already passed, so no request body can steer this.
	 *
	 * <p><strong>An account with no GHL contact still mints</strong>, scoped to the account id
	 * instead. That is the case after the 2026-09-11 CRM replacement and for any client who has
	 * not yet been pushed to GHL — and it is where "EvalOS works when GHL is removed" stops being
	 * a slogan: the client signs in and reaches their documents with no GHL row anywhere.
	 *
	 * <p>Not audited here. The caller audits {@code CLIENT_SIGNED_IN} with the account as the
	 * subject, because a credential issued <em>as part of</em> a sign-in is one event, not two.
	 */
	@Transactional
	public MintedLink mintForClientAccount(ClientAccount account) {
		Instant now = Instant.now();
		String token = freshToken();
		String contact = account.getGhlContactId();

		if (contact != null) {
			retirePreviousClientParty(account.getBrandId(), contact, now);
		}

		PortalAccess minted = tokens.save(PortalAccess.forParty(account.getBrandId(),
				PortalAudience.CLIENT, contact, null, hash(token), now.plus(partyTtl)));

		return new MintedLink(urlFor(PortalAudience.CLIENT, token), minted.getExpiresAt());
	}
```

Add the import `com.ie.evalos.domain.ClientAccount`.

> **⚠️ The null-contact path needs a migration, and this is confirmed, not hypothetical.**
> `V38__portal_access_names_a_party.sql:36-39` defines:
>
> ```sql
> ADD CONSTRAINT portal_access_scope_is_one_thing CHECK (
>     (case_id IS NOT NULL)
>     OR (audience = 'CLIENT' AND ghl_contact_id IS NOT NULL)
>     OR (audience = 'EXPERT' AND expert_id IS NOT NULL));
> ```
>
> So a `CLIENT` party row **requires** a `ghl_contact_id`, and `mintForClientAccount` for an
> account without one violates it. **That account is not an edge case — it is the normal case
> after the 2026-09-11 CRM replacement**, and it is the exact path that makes "the client signs
> in with no GHL row anywhere" true. Do Step 4b below before Step 5.

- [ ] **Step 4b: Widen the scope constraint**

Create `backend/src/main/resources/db/migration/V45__portal_access_names_an_account.sql`:

```sql
-- Unit 42 — a client party token may now name an EvalOS account instead of a GHL contact.
--
-- V38's constraint required a CLIENT party row to carry a ghl_contact_id. That was right when
-- the only way to be a client was to have been a GHL contact first. Unit 42 makes an EvalOS
-- account the thing a client signs in with, and after IE's GHL sub-account was replaced on
-- 2026-09-11 **most accounts have no GHL contact at all** — so the old constraint refuses
-- exactly the row the sign-in door needs to mint.
--
-- **The constraint is widened, not dropped.** A token scoped to nothing is still refused, which
-- is the property V38 was protecting. What changes is that "a client" now has two legal names.

ALTER TABLE portal_access ADD COLUMN client_account_id uuid REFERENCES client_account (id);

ALTER TABLE portal_access DROP CONSTRAINT portal_access_scope_is_one_thing;

ALTER TABLE portal_access
    ADD CONSTRAINT portal_access_scope_is_one_thing CHECK (
        (case_id IS NOT NULL)
        OR (audience = 'CLIENT' AND ghl_contact_id IS NOT NULL)
        OR (audience = 'CLIENT' AND client_account_id IS NOT NULL)
        OR (audience = 'EXPERT' AND expert_id IS NOT NULL));

-- The one-live-token rule follows the new scope, mirroring V38's partial index for contacts.
CREATE UNIQUE INDEX portal_access_one_live_per_account
    ON portal_access (client_account_id)
    WHERE client_account_id IS NOT NULL AND revoked_at IS NULL;
```

Then add to `PortalAccess.java` a `clientAccountId` column (`updatable = false`) and a third
factory `forAccount(UUID brandId, UUID clientAccountId, String tokenHash, Instant expiresAt)`,
following the reasoning already written above `forParty`: separate factories because the shapes
are different credentials, and one constructor taking four nullable ids is one transposed
argument away from minting the wrong one.

`PortalAccessService.resolve(...)` must also learn to resolve an account-scoped row. Read its
current body — it maps a `PortalAccess` to a `PortalPrincipal`; the account-scoped branch
produces a principal whose contact id is null. **Check every consumer of
`PortalPrincipal.ghlContactId()` before doing this** (`grep -rn "ghlContactId()" backend/src/main/java`):
`PortalInvoiceService` and `PortalMeetingService` both query GHL by it and must answer an empty
list rather than throwing when it is null — which is precisely the degradation `00c` §1c
predicts for pre-cutover clients, arriving as code.

- [ ] **Step 6: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=PortalAccessServiceTest`
Expected: PASS, all existing tests plus the 2 new ones.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/ie/evalos/service/PortalAccessService.java \
        backend/src/main/java/com/ie/evalos/domain/AuditAction.java \
        backend/src/test/java/com/ie/evalos/service/PortalAccessServiceTest.java
git commit -m "feat(42): mint a portal token for an authenticated account

Reverses the deliberate refusal of a mint-by-id entry point. The refusal's
reasoning was enumeration from an unauthenticated request body; this method
takes an entity the caller has already authenticated as, so there is nothing
to enumerate. The javadoc that refused it is amended in this commit rather
than deleted."
```

---

## Task 4: `identify` — the three answers

**Files:**
- Create: `backend/src/main/java/com/ie/evalos/service/ClientAccountService.java`
- Test: `backend/src/test/java/com/ie/evalos/service/ClientAccountServiceTest.java`

**Interfaces:**
- Consumes: `ClientAccountRepository.findByBrandIdAndEmailIgnoreCase`, `ClientMailer`, `ClientCredentialTokenRepository`, `PortalAccessService.mintForClientAccount`, `AuditService.recordPortalEvent`, `PasswordEncoder` (the existing `BCryptPasswordEncoder` bean from `SecurityConfig:61`).
- Produces: `ClientAccountService.IdentifyState` enum (`PASSWORD_SET`, `NO_PASSWORD`, `UNKNOWN`); `identify(String email)` → `IdentifyState`.

- [ ] **Step 1: Write the failing test**

```java
package com.ie.evalos.service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.ClientAccount;
import com.ie.evalos.repository.ClientAccountRepository;
import com.ie.evalos.repository.ClientCredentialTokenRepository;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The three answers the sign-in screen branches on, and what each one sends.
 *
 * <p>The one worth reading is {@link #unknownEmailSendsNothing()}: {@code identify} reveals
 * whether an email is known, which is email enumeration and is an accepted decision (spec §3) —
 * but it must not also become a way to make EvalOS send mail to an arbitrary address.
 */
class ClientAccountServiceTest {

	private static final UUID BRAND = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private final ClientAccountRepository accounts = mock(ClientAccountRepository.class);

	private final ClientCredentialTokenRepository credentials = mock(ClientCredentialTokenRepository.class);

	private final ClientMailer mailer = mock(ClientMailer.class);

	private final PortalAccessService links = mock(PortalAccessService.class);

	private final AuditService audit = mock(AuditService.class);

	private final PasswordEncoder encoder = new BCryptPasswordEncoder();

	private final ClientAccountService service = new ClientAccountService(accounts, credentials,
			mailer, links, audit, encoder, BRAND, Duration.ofMinutes(30), "https://portal.example.com");

	@Test
	void anAccountWithAPasswordAnswersPasswordSet() {
		ClientAccount account = new ClientAccount(BRAND, "ana@example.com");
		account.setPasswordHash(encoder.encode("Correct!1"));
		given(accounts.findByBrandIdAndEmailIgnoreCase(BRAND, "ana@example.com"))
				.willReturn(Optional.of(account));

		assertThat(service.identify("ana@example.com"))
				.isEqualTo(ClientAccountService.IdentifyState.PASSWORD_SET);
		verify(mailer, never()).sendSetPassword(any(), any());
	}

	@Test
	void aSeededAccountAnswersNoPasswordAndIsSentASetLink() {
		given(mailer.isConfigured()).willReturn(true);
		given(accounts.findByBrandIdAndEmailIgnoreCase(BRAND, "ana@example.com"))
				.willReturn(Optional.of(new ClientAccount(BRAND, "ana@example.com")));
		given(credentials.save(any())).willAnswer(call -> call.getArgument(0));

		assertThat(service.identify("ana@example.com"))
				.isEqualTo(ClientAccountService.IdentifyState.NO_PASSWORD);
		verify(mailer).sendSetPassword(eq("ana@example.com"), any());
	}

	@Test
	void unknownEmailSendsNothing() {
		given(accounts.findByBrandIdAndEmailIgnoreCase(BRAND, "nobody@example.com"))
				.willReturn(Optional.empty());

		assertThat(service.identify("nobody@example.com"))
				.isEqualTo(ClientAccountService.IdentifyState.UNKNOWN);
		verify(mailer, never()).sendSetPassword(any(), any());
		verify(credentials, never()).save(any());
	}

	@Test
	void emailIsMatchedCaseInsensitively() {
		ClientAccount account = new ClientAccount(BRAND, "ana@example.com");
		account.setPasswordHash(encoder.encode("Correct!1"));
		given(accounts.findByBrandIdAndEmailIgnoreCase(BRAND, "ANA@Example.com"))
				.willReturn(Optional.of(account));

		assertThat(service.identify("  ANA@Example.com  "))
				.isEqualTo(ClientAccountService.IdentifyState.PASSWORD_SET);
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=ClientAccountServiceTest`
Expected: FAIL — `ClientAccountService` does not exist.

- [ ] **Step 3: Write the service with `identify` only**

```java
package com.ie.evalos.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.ie.evalos.domain.ClientAccount;
import com.ie.evalos.domain.ClientCredentialToken;
import com.ie.evalos.domain.CredentialPurpose;
import com.ie.evalos.repository.ClientAccountRepository;
import com.ie.evalos.repository.ClientCredentialTokenRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The client's sign-in door (Unit 42).
 *
 * <p><strong>This service issues no credential of its own.</strong> A verified password is
 * exchanged for the party-scoped {@code PortalAccess} token Unit 35 already built, through
 * {@link PortalAccessService#mintForClientAccount}. That is what keeps this unit to two tables
 * and one {@code permitAll} matcher rather than the "third Spring Security chain" spec 34's D1
 * predicted.
 */
@Service
public class ClientAccountService {

	/**
	 * What the sign-in screen branches on.
	 *
	 * <p><strong>Three answers rather than one, and that is the whole feature.</strong> Every
	 * client who has ever had a case is seeded with a null password, so a combined
	 * email-and-password form would tell all of them "wrong password" — which is false and
	 * unactionable. Email first lets the truthful answer be given and fixed in one step.
	 */
	public enum IdentifyState {

		/** Signed up already. Reveal the password field in place. */
		PASSWORD_SET,

		/** Known to EvalOS, never set a password. A set-password mail has just been sent. */
		NO_PASSWORD,

		/** No account. Offer Get Started, carrying the email forward. */
		UNKNOWN
	}

	private final ClientAccountRepository accounts;

	private final ClientCredentialTokenRepository credentials;

	private final ClientMailer mailer;

	private final PortalAccessService links;

	private final AuditService audit;

	private final PasswordEncoder encoder;

	private final UUID brandId;

	private final Duration credentialTtl;

	private final String portalBaseUrl;

	ClientAccountService(ClientAccountRepository accounts, ClientCredentialTokenRepository credentials,
			ClientMailer mailer, PortalAccessService links, AuditService audit, PasswordEncoder encoder,
			@Value("${evalos.portal.client-brand}") UUID brandId,
			@Value("${evalos.portal.credential-ttl}") Duration credentialTtl,
			@Value("${evalos.portal.base-url}") String portalBaseUrl) {
		this.accounts = accounts;
		this.credentials = credentials;
		this.mailer = mailer;
		this.links = links;
		this.audit = audit;
		this.encoder = encoder;
		this.brandId = brandId;
		this.credentialTtl = credentialTtl;
		this.portalBaseUrl = portalBaseUrl.endsWith("/")
				? portalBaseUrl.substring(0, portalBaseUrl.length() - 1) : portalBaseUrl;
	}

	/**
	 * Which of the three screens the client should see next.
	 *
	 * <p><strong>This reveals whether an email is known, and that is a decision.</strong> It is
	 * email enumeration, accepted because the three-way answer <em>is</em> the feature: hiding it
	 * makes the common case (existing client, no password) indistinguishable from a typo. What
	 * contains it is the per-IP limiter already running over {@code /api/portal/**} in
	 * {@code PortalTokenFilter}. See spec §3.
	 *
	 * <p><strong>An unknown email sends nothing.</strong> Otherwise this route is a way to make
	 * EvalOS mail an arbitrary address, which is a different and worse hole than enumeration.
	 */
	@Transactional
	public IdentifyState identify(String email) {
		Optional<ClientAccount> found = accounts.findByBrandIdAndEmailIgnoreCase(brandId, normalize(email));
		if (found.isEmpty()) {
			return IdentifyState.UNKNOWN;
		}
		ClientAccount account = found.get();
		if (account.hasPassword()) {
			return IdentifyState.PASSWORD_SET;
		}
		issueCredential(account, CredentialPurpose.SET);
		return IdentifyState.NO_PASSWORD;
	}

	/**
	 * Mints a single-use link and mails it.
	 *
	 * <p>Returns quietly when mail is unconfigured: {@link ClientMailer} has already logged it,
	 * and the screen's copy tells the client to contact support. A throw here would turn a
	 * configuration gap into a 500 on a sign-in attempt.
	 */
	private void issueCredential(ClientAccount account, CredentialPurpose purpose) {
		String token = PortalAccessService.freshCredentialToken();
		credentials.save(new ClientCredentialToken(account.getBrandId(), account.getId(),
				PortalAccessService.hash(token), purpose, Instant.now().plus(credentialTtl)));
		String link = portalBaseUrl + "/set-password#" + token;
		if (purpose == CredentialPurpose.SET) {
			mailer.sendSetPassword(account.getEmail(), link);
		}
		else {
			mailer.sendResetPassword(account.getEmail(), link);
		}
	}

	private static String normalize(String email) {
		return email == null ? "" : email.trim();
	}
}
```

> `PortalAccessService.freshCredentialToken()` does not exist yet. Promote the existing private `freshToken()` to a package-visible `static String freshCredentialToken()` in `PortalAccessService` (same body, same `TOKEN_BYTES`), and have the private `freshToken()` delegate to it. One generator, one place, so the two credential kinds cannot drift in entropy.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=ClientAccountServiceTest`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/ie/evalos/service/ClientAccountService.java \
        backend/src/main/java/com/ie/evalos/service/PortalAccessService.java \
        backend/src/test/java/com/ie/evalos/service/ClientAccountServiceTest.java
git commit -m "feat(42): identify answers three ways, and an unknown email sends nothing"
```

---

## Task 5: Sign-in, forgot-password, set-password

**Files:**
- Modify: `backend/src/main/java/com/ie/evalos/service/ClientAccountService.java`
- Test: `backend/src/test/java/com/ie/evalos/service/ClientAccountServiceTest.java` (append)

**Interfaces:**
- Produces: `signIn(String email, String password)` → `PortalAccessService.MintedLink`; `forgotPassword(String email)` → `void`; `setPassword(String token, String password)` → `PortalAccessService.MintedLink`. All three throw `InvalidRequestException` on refusal.

- [ ] **Step 1: Write the failing tests**

Append to `ClientAccountServiceTest.java`:

```java
	@Test
	void signInWithTheRightPasswordMintsAToken() {
		ClientAccount account = new ClientAccount(BRAND, "ana@example.com");
		account.setPasswordHash(encoder.encode("Correct!1"));
		given(accounts.findByBrandIdAndEmailIgnoreCase(BRAND, "ana@example.com"))
				.willReturn(Optional.of(account));
		given(links.mintForClientAccount(account)).willReturn(
				new PortalAccessService.MintedLink("https://portal.example.com/#tok",
						java.time.Instant.now().plusSeconds(600)));

		assertThat(service.signIn("ana@example.com", "Correct!1").url()).contains("#tok");
		verify(audit).recordPortalEvent(eq(BRAND), any(), eq("CLIENT_ACCOUNT"), eq(account.getId()),
				eq(com.ie.evalos.domain.AuditAction.CLIENT_SIGNED_IN), any(), any());
	}

	@Test
	void signInWithTheWrongPasswordIsRefusedAndAudited() {
		ClientAccount account = new ClientAccount(BRAND, "ana@example.com");
		account.setPasswordHash(encoder.encode("Correct!1"));
		given(accounts.findByBrandIdAndEmailIgnoreCase(BRAND, "ana@example.com"))
				.willReturn(Optional.of(account));

		org.assertj.core.api.Assertions
				.assertThatThrownBy(() -> service.signIn("ana@example.com", "Wrong!1"))
				.isInstanceOf(com.ie.evalos.common.InvalidRequestException.class);
		verify(audit).recordPortalEvent(eq(BRAND), any(), eq("CLIENT_ACCOUNT"), eq(account.getId()),
				eq(com.ie.evalos.domain.AuditAction.CLIENT_SIGN_IN_REFUSED), any(), any());
		verify(links, never()).mintForClientAccount(any());
	}

	@Test
	void signInToAnAccountWithNoPasswordIsRefusedWithoutComparingAHash() {
		given(accounts.findByBrandIdAndEmailIgnoreCase(BRAND, "ana@example.com"))
				.willReturn(Optional.of(new ClientAccount(BRAND, "ana@example.com")));

		org.assertj.core.api.Assertions
				.assertThatThrownBy(() -> service.signIn("ana@example.com", "anything"))
				.isInstanceOf(com.ie.evalos.common.InvalidRequestException.class);
	}

	@Test
	void forgotPasswordForAnUnknownEmailIsSilentAndSendsNothing() {
		given(accounts.findByBrandIdAndEmailIgnoreCase(BRAND, "nobody@example.com"))
				.willReturn(Optional.empty());

		service.forgotPassword("nobody@example.com");

		verify(mailer, never()).sendResetPassword(any(), any());
	}

	@Test
	void aUsedSetPasswordTokenIsRefusedTheSecondTime() {
		ClientAccount account = new ClientAccount(BRAND, "ana@example.com");
		com.ie.evalos.domain.ClientCredentialToken token = new com.ie.evalos.domain.ClientCredentialToken(
				BRAND, account.getId(), PortalAccessService.hash("tok"),
				com.ie.evalos.domain.CredentialPurpose.SET,
				java.time.Instant.now().plusSeconds(600));
		given(credentials.findByTokenHash(PortalAccessService.hash("tok")))
				.willReturn(Optional.of(token));
		given(accounts.findById(account.getId())).willReturn(Optional.of(account));
		given(links.mintForClientAccount(account)).willReturn(
				new PortalAccessService.MintedLink("https://portal.example.com/#tok",
						java.time.Instant.now().plusSeconds(600)));

		service.setPassword("tok", "Brand!New1");

		org.assertj.core.api.Assertions
				.assertThatThrownBy(() -> service.setPassword("tok", "Another!1"))
				.isInstanceOf(com.ie.evalos.common.InvalidRequestException.class);
	}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=ClientAccountServiceTest`
Expected: FAIL — `signIn` does not exist.

- [ ] **Step 3: Implement the three methods**

Add to `ClientAccountService`:

```java
	/**
	 * Verifies a password and hands back the portal credential.
	 *
	 * <p><strong>Both outcomes are audited</strong> (invariant 13, {@code actor_type = CLIENT}).
	 * A failed sign-in is the one event a support conversation actually needs, and an unaudited
	 * one is invisible forever.
	 *
	 * <p><strong>One message for every refusal.</strong> A wrong password, an account with no
	 * password and an unknown email all answer identically here — {@code identify} is where the
	 * difference is told, deliberately and once, so this route does not become a second and
	 * unthrottled enumeration surface.
	 */
	@Transactional
	public PortalAccessService.MintedLink signIn(String email, String password) {
		ClientAccount account = accounts.findByBrandIdAndEmailIgnoreCase(brandId, normalize(email))
				.orElseThrow(ClientAccountService::refused);

		// Checked before the hash comparison: encoder.matches against a null stored hash throws
		// on some encoders and returns false on others, and neither is a decision worth relying on.
		if (!account.hasPassword() || !encoder.matches(password, account.getPasswordHash())) {
			audit.recordPortalEvent(account.getBrandId(), PortalAudience.CLIENT, "CLIENT_ACCOUNT",
					account.getId(), AuditAction.CLIENT_SIGN_IN_REFUSED, null,
					"sign-in refused for " + account.getEmail());
			throw refused();
		}

		account.recordSignIn(Instant.now());
		audit.recordPortalEvent(account.getBrandId(), PortalAudience.CLIENT, "CLIENT_ACCOUNT",
				account.getId(), AuditAction.CLIENT_SIGNED_IN, null,
				"signed in as " + account.getEmail());
		return links.mintForClientAccount(account);
	}

	/**
	 * Sends a reset link if the address is known, and says nothing either way.
	 *
	 * <p><strong>Deliberately does not differentiate, unlike {@link #identify}.</strong> The
	 * three-way answer earns its enumeration on the sign-in screen because it tells a client
	 * something true and actionable. Here the client already believes they have an account, so
	 * differentiating buys nothing and the leak is not taken. The controller answers 204
	 * regardless.
	 */
	@Transactional
	public void forgotPassword(String email) {
		accounts.findByBrandIdAndEmailIgnoreCase(brandId, normalize(email))
				.ifPresent(account -> issueCredential(account, CredentialPurpose.RESET));
	}

	/**
	 * Spends a single-use link, stores the new password, and signs the client straight in.
	 *
	 * <p>Signing in here rather than bouncing to the sign-in screen is the point of returning a
	 * token: somebody who has just proved control of the mailbox and chosen a password should not
	 * immediately be asked for that password.
	 */
	@Transactional
	public PortalAccessService.MintedLink setPassword(String token, String password) {
		ClientCredentialToken credential = credentials.findByTokenHash(PortalAccessService.hash(token))
				.orElseThrow(ClientAccountService::linkRefused);
		Instant now = Instant.now();
		if (!credential.isUsable(now)) {
			throw linkRefused();
		}
		ClientAccount account = accounts.findById(credential.getClientAccountId())
				.orElseThrow(ClientAccountService::linkRefused);

		credential.markUsed(now);
		account.setPasswordHash(encoder.encode(password));
		account.recordSignIn(now);
		audit.recordPortalEvent(account.getBrandId(), PortalAudience.CLIENT, "CLIENT_ACCOUNT",
				account.getId(), AuditAction.CLIENT_PASSWORD_SET, null,
				"password set for " + account.getEmail());
		return links.mintForClientAccount(account);
	}

	private static InvalidRequestException refused() {
		return new InvalidRequestException("That email and password do not match an account.");
	}

	private static InvalidRequestException linkRefused() {
		return new InvalidRequestException(
				"This link is no longer valid. It may have been used already, or it may have expired. "
						+ "Please request a new one.");
	}
```

Add imports: `com.ie.evalos.common.InvalidRequestException`, `com.ie.evalos.domain.AuditAction`, `com.ie.evalos.domain.PortalAudience`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=ClientAccountServiceTest`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/ie/evalos/service/ClientAccountService.java \
        backend/src/test/java/com/ie/evalos/service/ClientAccountServiceTest.java
git commit -m "feat(42): sign in, forget, and set a password — refusals audited too"
```

---

## Task 6: The four routes and the one security change

**Files:**
- Create: `backend/src/main/java/com/ie/evalos/web/ClientAuthController.java`
- Modify: `backend/src/main/java/com/ie/evalos/security/PortalSecurityConfig.java`
- Test: `backend/src/test/java/com/ie/evalos/web/ClientAuthControllerTest.java`

**Interfaces:**
- Consumes: `ClientAccountService`, `ApiResponse.ok(T)`.
- Produces: `POST /api/portal/auth/identify|sign-in|forgot-password|set-password`.

- [ ] **Step 1: Write the failing test**

```java
package com.ie.evalos.web;

import java.time.Instant;

import com.ie.evalos.service.ClientAccountService;
import com.ie.evalos.service.PortalAccessService;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The auth routes' contract. The interesting one is {@link #forgotPasswordIsAlwaysNoContent()}:
 * the response must not differ between a known and an unknown email, or the non-differentiating
 * service method is undone by the controller.
 */
class ClientAuthControllerTest {

	private final ClientAccountService service = mock(ClientAccountService.class);

	private final ClientAuthController controller = new ClientAuthController(service);

	@Test
	void identifyReturnsTheStateTheServiceDecided() {
		given(service.identify("ana@example.com"))
				.willReturn(ClientAccountService.IdentifyState.NO_PASSWORD);

		assertThat(controller.identify(new ClientAuthController.EmailRequest("ana@example.com"))
				.data().state()).isEqualTo("NO_PASSWORD");
	}

	@Test
	void signInReturnsTheToken() {
		given(service.signIn("ana@example.com", "Correct!1")).willReturn(
				new PortalAccessService.MintedLink("https://portal.example.com/#abc",
						Instant.parse("2026-09-19T10:00:00Z")));

		var body = controller.signIn(
				new ClientAuthController.SignInRequest("ana@example.com", "Correct!1")).data();

		assertThat(body.token()).isEqualTo("abc");
		assertThat(body.expiresAt()).isEqualTo(Instant.parse("2026-09-19T10:00:00Z"));
	}

	@Test
	void forgotPasswordIsAlwaysNoContent() {
		controller.forgotPassword(new ClientAuthController.EmailRequest("nobody@example.com"));

		verify(service).forgotPassword("nobody@example.com");
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=ClientAuthControllerTest`
Expected: FAIL — `ClientAuthController` does not exist.

- [ ] **Step 3: Write the controller**

```java
package com.ie.evalos.web;

import java.time.Instant;

import com.ie.evalos.common.ApiResponse;
import com.ie.evalos.service.ClientAccountService;
import com.ie.evalos.service.PortalAccessService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The client's front door (Unit 42).
 *
 * <p><strong>The only unauthenticated routes on the portal chain.</strong> Everything else under
 * {@code /api/portal/**} requires a token; these four are how a client obtains one. They are
 * {@code permitAll} in {@code PortalSecurityConfig} and are still covered by that chain's per-IP
 * limiter, which is what throttles both password guessing and the enumeration {@code identify}
 * deliberately allows.
 *
 * <p><strong>The token is returned in a body, not a fragment.</strong> The fragment convention
 * protects a link that travels through a mailbox; this is a response to a POST the client's own
 * browser made, over TLS, and it goes straight into memory.
 */
@RestController
@RequestMapping("/api/portal/auth")
public class ClientAuthController {

	public record EmailRequest(@NotBlank @Email String email) {
	}

	public record SignInRequest(@NotBlank @Email String email, @NotBlank String password) {
	}

	/**
	 * @param token the credential from the emailed link's fragment
	 * @param password rules mirror {@code schemas/intake.ts} — the frontend states them, and the
	 *                 length floor is restated here because a client is not the only caller
	 */
	public record SetPasswordRequest(@NotBlank String token, @NotBlank @Size(min = 8) String password) {
	}

	/** @param state one of {@code PASSWORD_SET}, {@code NO_PASSWORD}, {@code UNKNOWN} */
	public record IdentifyView(String state) {
	}

	public record SessionView(String token, Instant expiresAt) {
	}

	private final ClientAccountService accounts;

	ClientAuthController(ClientAccountService accounts) {
		this.accounts = accounts;
	}

	@PostMapping("/identify")
	public ApiResponse<IdentifyView> identify(@Valid @RequestBody EmailRequest request) {
		return ApiResponse.ok(new IdentifyView(accounts.identify(request.email()).name()));
	}

	@PostMapping("/sign-in")
	public ApiResponse<SessionView> signIn(@Valid @RequestBody SignInRequest request) {
		return ApiResponse.ok(session(accounts.signIn(request.email(), request.password())));
	}

	/**
	 * <strong>204 always, known email or not.</strong> The service does not differentiate and
	 * neither does this — a status code that varied would undo it.
	 */
	@PostMapping("/forgot-password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void forgotPassword(@Valid @RequestBody EmailRequest request) {
		accounts.forgotPassword(request.email());
	}

	@PostMapping("/set-password")
	public ApiResponse<SessionView> setPassword(@Valid @RequestBody SetPasswordRequest request) {
		return ApiResponse.ok(session(accounts.setPassword(request.token(), request.password())));
	}

	/**
	 * Pulls the bare token out of the minted URL's fragment.
	 *
	 * <p>{@code PortalAccessService} returns a whole link because its other caller shows one to a
	 * staff member to paste. This caller's client is a browser that already knows where it is, and
	 * handing it a full URL would invite a redirect.
	 */
	private static SessionView session(PortalAccessService.MintedLink link) {
		String url = link.url();
		int hash = url.indexOf('#');
		return new SessionView(hash < 0 ? url : url.substring(hash + 1), link.expiresAt());
	}
}
```

- [ ] **Step 4: Open the routes**

In `PortalSecurityConfig.java`, replace the single `authorizeHttpRequests` line:

```java
				.authorizeHttpRequests(auth -> auth
						// **The only unauthenticated routes on this chain (Unit 42).** They are
						// how a client obtains the token every other route requires, so they
						// cannot themselves require one. Still behind the per-IP limiter below,
						// which is what throttles password guessing and the enumeration
						// `identify` deliberately allows.
						.requestMatchers("/api/portal/auth/**").permitAll()
						.anyRequest().authenticated())
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=ClientAuthControllerTest`
Expected: PASS, 3 tests.

- [ ] **Step 6: Run the full suite**

Run: `cd backend && ./mvnw test`
Expected: PASS. A failure in `SecurityFlowTest` or a portal chain test means the `permitAll` matcher was placed *below* `anyRequest()` — order matters and `anyRequest()` must be last.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/ie/evalos/web/ClientAuthController.java \
        backend/src/main/java/com/ie/evalos/security/PortalSecurityConfig.java \
        backend/src/test/java/com/ie/evalos/web/ClientAuthControllerTest.java
git commit -m "feat(42): four auth routes, and the one permitAll matcher they need"
```

---

## Task 7: Seed the clients you already have

**Files:**
- Create: `backend/src/main/resources/db/migration/V44__seed_client_accounts.sql`
- Test: `backend/src/test/java/com/ie/evalos/repository/ClientAccountSeedTest.java`

**Interfaces:**
- Consumes: the existing `contact_snapshot` table (`brand_id`, `email`, `full_name`, `phone`).

- [ ] **Step 1: Write the migration**

```sql
-- Unit 42 — every client EvalOS already knows gets an account with no password.
--
-- They land in NO_PASSWORD on their first sign-in attempt and set a password from the emailed
-- link. Without this, every existing client is told "we couldn't find that email", which is false.
--
-- **Two things about contact_snapshot this has to survive, and neither is obvious:**
--
--   1. `email` is NULLABLE. A contact that arrived without one is skipped — there is nothing to
--      sign in with, and a row with a null login can never be used.
--   2. `email` is NOT UNIQUE. ContactSnapshot's own javadoc warns that "contacts sharing an email
--      would otherwise let the second silently take over". So DISTINCT ON is load-bearing, not
--      tidiness: a naive insert violates client_account_brand_email_key and fails this migration.
--      Where two snapshots share an address they collapse into ONE account, which is correct —
--      it is one person with one inbox, and their cases are found through the party link rather
--      than through the snapshot row.
--
-- **ghl_contact_id is seeded NULL, not copied forward.** IE replaced its GHL sub-account on
-- 2026-09-11 (WY6bW2xUCI8Tz8gw7aLJ, fresh, no contact migration), so every id EvalOS holds names
-- a contact that does not exist in the new location. A column that looks authoritative and 404s
-- is worse than an absent one. See 00c §1c.

insert into client_account (id, brand_id, email, password_hash, ghl_contact_id,
                            first_name, last_name, phone, created_at)
select distinct on (c.brand_id, lower(c.email))
       gen_random_uuid(),
       c.brand_id,
       c.email,
       null,
       null,
       split_part(c.full_name, ' ', 1),
       nullif(substring(c.full_name from position(' ' in c.full_name) + 1), c.full_name),
       c.phone,
       now()
from contact_snapshot c
where c.email is not null
  and length(trim(c.email)) > 0
order by c.brand_id, lower(c.email), c.created_at asc;
```

> Before writing this, run `grep -n "full_name\|created_at\|phone" backend/src/main/java/com/ie/evalos/domain/ContactSnapshot.java` and confirm the column names. If `contact_snapshot` has no `created_at`, drop it from the `ORDER BY` and order by `id` instead — the tie-break only has to be deterministic, not meaningful.

- [ ] **Step 2: Write the failing test**

```java
package com.ie.evalos.repository;

import java.util.UUID;

import com.ie.evalos.domain.ClientAccount;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the seeding migration survives the two things contact_snapshot does that a naive insert
 * would trip over: a null email, and two contacts sharing one.
 *
 * <p>Follow whatever database-backed test base the repository package already uses — if the other
 * tests here are {@code @DataJpaTest} against Testcontainers, match them rather than introducing
 * a second style.
 */
@DataJpaTest
class ClientAccountSeedTest {

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ClientAccountRepository accounts;

	@Test
	void twoSnapshotsSharingAnEmailCollapseIntoOneAccount() {
		UUID brand = seedBrand();
		insertSnapshot(brand, "shared@example.com", "Ana One");
		insertSnapshot(brand, "SHARED@example.com", "Ana Two");

		runSeed();

		assertThat(accounts.findByBrandIdAndEmailIgnoreCase(brand, "shared@example.com")).isPresent();
		assertThat(jdbc.queryForObject(
				"select count(*) from client_account where brand_id = ?", Integer.class, brand))
				.isEqualTo(1);
	}

	@Test
	void aSnapshotWithNoEmailIsSkipped() {
		UUID brand = seedBrand();
		insertSnapshot(brand, null, "No Email");

		runSeed();

		assertThat(jdbc.queryForObject(
				"select count(*) from client_account where brand_id = ?", Integer.class, brand))
				.isZero();
	}

	@Test
	void seededAccountsHaveNoPasswordAndNoGhlContact() {
		UUID brand = seedBrand();
		insertSnapshot(brand, "ana@example.com", "Ana Perez");

		runSeed();

		ClientAccount account = accounts.findByBrandIdAndEmailIgnoreCase(brand, "ana@example.com")
				.orElseThrow();
		assertThat(account.hasPassword()).isFalse();
		assertThat(account.getGhlContactId()).isNull();
	}

	// Helpers: seedBrand() inserts a minimal active brand row; insertSnapshot() inserts one
	// contact_snapshot; runSeed() executes the body of V44 against the test database. Read the
	// column lists off V43 and the contact_snapshot migration rather than guessing them.
	private UUID seedBrand() {
		throw new UnsupportedOperationException("implement against the brand table's columns");
	}

	private void insertSnapshot(UUID brandId, String email, String fullName) {
		throw new UnsupportedOperationException("implement against contact_snapshot's columns");
	}

	private void runSeed() {
		throw new UnsupportedOperationException("execute V44's insert statement via jdbc");
	}
}
```

> The three helpers are stubs **on purpose** — their bodies depend on the exact column lists of `brand` and `contact_snapshot`, which the implementer must read rather than have guessed for them. Fill them in as the first action of Step 3. Every other line of this test is final.

- [ ] **Step 3: Fill in the helpers and run the test**

Run: `cd backend && ./mvnw test -Dtest=ClientAccountSeedTest`
Expected: FAIL first (no `V44`), then PASS once the migration is in place.

- [ ] **Step 4: Run the full suite**

Run: `cd backend && ./mvnw test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V44__seed_client_accounts.sql \
        backend/src/test/java/com/ie/evalos/repository/ClientAccountSeedTest.java
git commit -m "feat(42): every client we already know gets an account, with no password"
```

---

## Task 8: The frontend door

**Files:**
- Create: `client-expert/client/src/services/authService.ts`
- Create: `client-expert/client/src/pages/auth/Welcome.tsx`
- Create: `client-expert/client/src/pages/auth/SignIn.tsx`
- Create: `client-expert/client/src/pages/auth/SetPassword.tsx`
- Modify: `client-expert/client/src/App.tsx`
- Test: `client-expert/client/src/services/authService.test.ts`

**Interfaces:**
- Consumes: `@shared/services/apiClient`'s `setPortalToken(token)`, and `@shared/lib/portal`'s `tokenFromFragment(hash)`.
- Produces: `identify(email)` → `'PASSWORD_SET' | 'NO_PASSWORD' | 'UNKNOWN'`; `signIn(email, password)` → `{ token, expiresAt }`; `forgotPassword(email)` → `void`; `setPassword(token, password)` → `{ token, expiresAt }`.

- [ ] **Step 1: Write the failing test**

```ts
import { describe, expect, it } from 'vitest'
import { tokenFromFragment } from '@shared/lib/portal'

describe('set-password link', () => {
  it('reads the credential out of the fragment, like every other portal token', () => {
    expect(tokenFromFragment('#abc123')).toBe('abc123')
  })

  it('treats an empty fragment as no credential', () => {
    expect(tokenFromFragment('#')).toBeNull()
  })
})
```

Run: `cd client-expert && npx vitest run client/src/services/authService.test.ts`
Expected: PASS — this pins the existing helper the new screen depends on, so a change to it breaks here rather than in a browser.

- [ ] **Step 2: Write the service**

```ts
import { apiClient } from '@shared/services/apiClient'
import { setPortalToken } from '@shared/services/apiClient'

/**
 * The three answers the sign-in screen branches on. The server's vocabulary, unmapped — the same
 * rule `CHECKLIST_STATUS` and `APPROVAL_STATUS` already follow, so a fourth value fails loudly
 * rather than falling into a default branch.
 */
export type IdentifyState = 'PASSWORD_SET' | 'NO_PASSWORD' | 'UNKNOWN'

export type Session = { token: string; expiresAt: string }

export async function identify(email: string): Promise<IdentifyState> {
  const { data } = await apiClient.post('/portal/auth/identify', { email })
  return data.data.state as IdentifyState
}

/** On success the token goes straight into the API client's memory — never localStorage. */
export async function signIn(email: string, password: string): Promise<Session> {
  const { data } = await apiClient.post('/portal/auth/sign-in', { email, password })
  setPortalToken(data.data.token)
  return data.data as Session
}

/** Always resolves. The server answers 204 whether or not the address is known, by design. */
export async function forgotPassword(email: string): Promise<void> {
  await apiClient.post('/portal/auth/forgot-password', { email })
}

export async function setPassword(token: string, password: string): Promise<Session> {
  const { data } = await apiClient.post('/portal/auth/set-password', { token, password })
  setPortalToken(data.data.token)
  return data.data as Session
}
```

> Check `client-expert/shared/src/services/apiClient.ts` for the real export names and whether it exposes `post` directly or wraps it. Match what is there; do not add a second HTTP client.

- [ ] **Step 3: Write the three screens**

`Welcome.tsx` — two cards (*Start a new evaluation* → `/start`, *Sign in* → `/signin`), plus a muted line **"Opened a link we sent you?"** explaining that an existing portal link still works and should be opened from the original message. That third line is required, not decorative: every `portal_access` link already in a client's inbox must keep working, and a welcome screen offering only a password tells those clients their working link is wrong.

`SignIn.tsx` — one email field. On submit, call `identify` and branch:
- `PASSWORD_SET` → reveal a password field **in place**, no navigation, with a *Forgot password?* link that calls `forgotPassword` and shows *"If that email is in our system, we've sent a reset link."*
- `NO_PASSWORD` → *"You're in our system, but haven't set a password yet. We've emailed you a link to set one."*
- `UNKNOWN` → *"We couldn't find that email"* and a button to `/start` that **carries the email forward** in router state.

`SetPassword.tsx` — reads the credential with `tokenFromFragment(window.location.hash)`; if absent, show `NO_TOKEN` from `@shared/lib/portal`. Two password fields validated with the existing rules in `client-expert/client/src/schemas/intake.ts` (import them; do not restate them). On success, `navigate('/dashboard')` — the returned token is already in the API client.

Use the existing `@shared/components/ui/*` primitives and `FormField`; add no new dependency.

- [ ] **Step 4: Register the routes**

In `App.tsx`, add above the `PortalLayout` block, and change the index redirect:

```tsx
      <Route path="/" element={<Navigate to="/welcome" replace />} />
      <Route path="/welcome" element={<Welcome />} />
      <Route path="/signin" element={<SignIn />} />
      <Route path="/set-password" element={<SetPassword />} />
```

Keep the `lazy(...)` convention the other routes use. Update the file's header comment: the block explaining that the account shell was deleted in 34d is now historical, and should say that Unit 42 brought a door back — deliberately, in writing, and **not** the same thing, because there is still no second credential.

- [ ] **Step 5: Build both portal apps**

Run: `cd client-expert/client && npm run build`
Expected: PASS, no TypeScript errors.

Run: `cd client-expert/expert && npm run build`
Expected: PASS — the expert app shares `shared/` and must not have been broken.

- [ ] **Step 6: Commit**

```bash
git add client-expert/client/src/services/authService.ts \
        client-expert/client/src/services/authService.test.ts \
        client-expert/client/src/pages/auth client-expert/client/src/App.tsx
git commit -m "feat(42): the client portal has a front door again"
```

---

## Task 9: Align the documents — ✅ **DONE AHEAD OF THE CODE (2026-09-12)**

> **Steps 1–7 below are already applied and committed.** The house rule is that a pivot is
> specced and its documents aligned *before* it is coded, so `architecture.md` currently
> describes mail and client accounts this codebase does not yet have. **That is intended.**
> Keep this task in the plan as the record of what was changed; re-read it before Task 1 so you
> know which documents already claim the end state. The only work left here is **Step 7's
> tracker move**, once the code is actually green.


**Files:**
- Modify: `context/architecture.md` (invariants 7 and 14; the Notifications row of the stack table)
- Modify: `CLAUDE.md`
- Modify: `context/specs/00-build-plan.md` (the "no mail server — open decision" note)
- Modify: `context/process-automation.md` (same note)
- Modify: `context/specs/34-portal-frontend-wiring.md` (D1 gets a superseded header)
- Modify: `context/progress-tracker.md`
- Modify: `.serena/memories/client-expert/core.md` and any memory stating the portal has no account

- [ ] **Step 1: Amend invariant 14**

Rewrite the opening of invariant 14 in `architecture.md` — **edit it, do not annotate it**:

> 14. EvalOS hosts no files, and **sends email for exactly one purpose: proving control of a
>     client's own address** (Unit 42). Documents are objects in the S3 document store…
>
>     **What the mail amendment does and does not license.** `ClientMailer` sends two messages:
>     set your password, and reset your password. **Not licensed:** status mail, marketing mail,
>     notification mail, or any message a client did not initiate by trying to sign in. Staff
>     alerts remain in-app; clients are still reached through GHL for everything that is not
>     authentication. `00b` §2's ruling — *"what would break it is EvalOS composing and
>     dispatching a message itself"* — is partly spent here, deliberately, and the remaining line
>     is drawn at authentication.

- [ ] **Step 2: Amend invariant 7**

Add to invariant 7, after the Unit 39 amendment:

> **Unit 42 amends the first clause a second time, for one entity.** `client_account` is an
> **EvalOS-owned record** whose `ghl_contact_id` is a nullable *link*. GHL's contact id remains
> canonical **in GHL**; what changed is that a client's ability to sign in no longer depends on
> GHL holding a row. **The three-identifier rule survives verbatim** and is still load-bearing.
>
> **This is the second of three edits and the third is scheduled**: `00c` Unit 44 rewrites this
> invariant **whole**, rather than annotating it a fourth time. Three amendments across three
> units is how an invariant dies without anyone deciding to kill it.

- [ ] **Step 3: Update the stack table**

`architecture.md` line 15, the Notifications row: replace "No EvalOS mail server" with
"**SMTP for authentication mail only (Unit 42)** — `spring-boot-starter-mail`, two messages, invariant 14 as amended. No marketing, status or notification mail."

- [ ] **Step 4: Update `CLAUDE.md`**

Add to the paragraph describing the programme, after the `00b` sentence:

> **`context/specs/00c-ghl-independence-programme.md` is the next programme** (Units 42–49):
> EvalOS holds an id-faithful mirror of GHL, syncs both ways, and must keep working when the
> sync is switched off. Read it before touching the mirror, the sync engine or the client portal's
> door. **Units 42 and 43 are the client's sign-in and signup** and do not wait for the rest.

- [ ] **Step 5: Close the two "no mail" open decisions**

In `00-build-plan.md`'s Notes, the sentence *"whether EvalOS ever sends mail is still an open decision… until it is taken, no mail dependency"* becomes:

> **Decided 2026-09-11 and built in Unit 42: EvalOS sends authentication mail and nothing else.**
> `spring-boot-starter-mail`, two messages, invariant 14 amended in writing. Any other mail is a
> new decision.

Make the equivalent edit in `context/process-automation.md` wherever the same open question appears (`grep -n "mail" context/process-automation.md`).

- [ ] **Step 6: Mark spec 34's D1 superseded**

At the top of `34-portal-frontend-wiring.md`'s D1 section, add:

> **SUPERSEDED 2026-09-11 by Unit 42.** D1 refused accounts and required the reversal to be taken
> in writing; it was. **The refusal was correct on the evidence it had** — two of its four
> objections (no mail channel; four documents to edit) were right and are answered in
> `42-client-accounts.md` §2. Two were wrong: there is no third security chain, and lockout was
> already built. The text below is kept as written.

- [ ] **Step 7: Update the tracker and the memories**

In `context/progress-tracker.md`, move Units 42's entry out of "In Progress" awaiting-review into "Completed" with what was built and what was found. Then update `.serena/memories/client-expert/core.md`: it currently records that the client portal has **no account** and one link credential. **Edit that statement, do not add a note beside it** — a memory contradicting itself is worse than a stale one. Run `grep -rln "no account\|portal token\|link-based" .serena/memories/` and fix every memory that states the old model.

- [ ] **Step 8: Commit**

```bash
git add context/ CLAUDE.md .serena/memories/
git commit -m "docs: invariants 7 and 14 amended for Unit 42, and the memories with them"
```

---

## Self-Review

**Spec coverage:** §1 → Tasks 1–8. §2 (no third chain) → Task 3 + Task 6 Step 4. §3 (three answers, enumeration, forgot-password symmetry, fragment) → Tasks 4, 5, 6, 8. §4 (mail) → Task 2. §5 (data model) → Task 1. §6 (routes, no new chain, audit) → Tasks 3, 5, 6. §7 (seeding, null email, non-unique email, null `ghl_contact_id`) → Task 7. §8 (invariant impact, documents) → Task 9. §9 (not-doing) → no tasks, correct. §10 acceptance criteria 1–8 → criteria 1 and 5 need a manual pass in Task 8 Step 5; 2, 3, 4, 6, 7, 8 are covered by automated tests in Tasks 1, 2, 5, 6, 7.

**Two open items flagged in-plan rather than guessed**, both requiring the implementer to read before writing: the `portal_access_scope_is_one_thing` constraint (Task 3, Step 5 note) and `contact_snapshot`'s exact column names (Task 7, Step 1 note). Both are stated with the grep that resolves them.

**Type consistency:** `MintedLink(url, expiresAt)` is used identically in Tasks 3, 5 and 6. `IdentifyState` spelling matches across Java enum, JSON string and the TS union in Task 8. `hash(String)` and `freshCredentialToken()` are both promoted in Task 4 and used in Tasks 4 and 5.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-09-12-unit-42-client-accounts.md`.
