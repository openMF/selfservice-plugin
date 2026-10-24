# Mifos® Self Service Plugin for Apache Fineract®

[![Java CI](https://github.com/openMF/selfservice-plugin/actions/workflows/maven-build.yml/badge.svg)](https://github.com/openMF/selfservice-plugin/actions/workflows/maven-build.yml)
[![License: MPL 2.0](https://img.shields.io/badge/License-MPL_2.0-brightgreen.svg)](https://opensource.org/licenses/MPL-2.0)
![Java 5](https://img.shields.io/badge/Java-25-blue)
![Spring Boot 4](https://img.shields.io/badge/Spring_Boot-3-6DB33F)
![Fineract 1.16.0-SNAPSHOT](https://img.shields.io/badge/Fineract-1.16.0--SNAPSHOT-orange)

A Spring Boot plugin that extends [Apache Fineract](https://fineract.apache.org/) to provide **self-service banking capabilities** to end users. It lets customers register, authenticate, view their accounts, transfer funds, and manage their financial products — all without staff intervention.

---

## Table of Contents

- [What Is This?](#what-is-this)
- [What Does It Do?](#what-does-it-do)
- [Architecture Overview](#architecture-overview)
- [Prerequisites](#prerequisites)
- [Getting Started](#getting-started)
  - [Build from Source](#build-from-source)
  - [Deploy with Fineract (Docker)](#deploy-with-fineract-docker)
  - [Deploy with Fineract (Tomcat)](#deploy-with-fineract-tomcat)
- [API Reference](#api-reference)
- [Contributing](#contributing)
- [History](#history)
- [Important Notices](#important-notices)
- [License](#license)

---

## What Is This?

Apache Fineract is an open-source core banking platform. Out of the box, its APIs are designed for **back-office staff** — loan officers, tellers, and administrators. There is no built-in mechanism for a bank's actual *customers* to log in, check their balances, or initiate transfers themselves.

**This plugin bridges that gap.** It adds a complete set of customer-facing REST APIs (under `/v1/self/...`) that sit on top of Fineract's core. Think of it as the backend for a mobile banking app or a customer web portal.

> **Key point:** This is a *plugin*, not a standalone application. It ships as a JAR that you drop into your existing Fineract deployment. It extends Fineract at runtime — no forking, no patching of Fineract source code required.

## What Does It Do?

| Capability | Description |
|---|---|
| **Self-Registration & Enrollment** | Customers can sign up, receive a verification token (via email/SMS), and activate their account. |
| **Authentication** | Dedicated login endpoint with HTTP Basic Auth support, 2FA compatibility, and login attempt notifications. |
| **Password Recovery** | Request a password reset token and renew the password — all self-service. |
| **Client Profile** | View personal details, profile images, charges, and transaction history. |
| **Savings Accounts** | List savings accounts, view balances, transactions, charges, and apply for new savings products. |
| **Loan Accounts** | Browse loan details, repayment schedules, charges, guarantors, and apply for new loans. |
| **Share Accounts** | View share account holdings and apply for share products. |
| **Fund Transfers** | Transfer between own accounts or to third-party beneficiaries (with configurable daily/per-beneficiary limits). |
| **Beneficiary Management** | Add, update, and delete third-party transfer beneficiaries. |
| **Product Discovery** | Browse available savings, loan, and share products. |
| **Reports** | Run pre-configured financial reports scoped to the authenticated user. |
| **Surveys & Scorecards** | Participate in surveys and view scorecards. |
| **Notifications** | Event-driven email/SMS notifications for login activity and account activation (with cooldown and SMTP fallback). |

## Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                   Client Applications                   │
│          (Mobile App / Web Portal / Chatbot)            │
└────────────────────────┬────────────────────────────────┘
                         │  HTTPS  /v1/self/*
                         ▼
┌─────────────────────────────────────────────────────────┐
│              Self Service Plugin (this repo)            │
│  ┌─────────────┐ ┌──────────┐ ┌──────────────────────┐  │
│  │ Registration│ │ Security │ │ Notifications        │  │
│  │ & Enrollment│ │ & Auth   │ │ (Email / SMS)        │  │
│  └─────────────┘ └──────────┘ └──────────────────────┘  │
│  ┌─────────────┐ ┌──────────┐ ┌──────────────────────┐  │
│  │ Clients     │ │ Savings  │ │ Loans                │  │
│  └─────────────┘ └──────────┘ └──────────────────────┘  │
│  ┌─────────────┐ ┌──────────┐ ┌──────────────────────┐  │
│  │ Shares      │ │ Transfers│ │ Products / Reports   │  │
│  └─────────────┘ └──────────┘ └──────────────────────┘  │
└────────────────────────┬────────────────────────────────┘
                         │  delegates to
                         ▼
┌─────────────────────────────────────────────────────────┐
│               Apache Fineract Core                      │
│        (fineract-provider, fineract-core, etc.)         │
│                   Multi-Tenant DB                       │
└─────────────────────────────────────────────────────────┘
```

**Tech stack:** Java 25 · Spring Boot 4 · Spring Security (Basic Auth + OAuth2) · JAX-RS · JPA/EclipseLink · Liquibase · Lombok · Swagger/OpenAPI

## Prerequisites

| Requirement | Version |
|---|---|
| Java (JDK) | **25** |
| Maven | 3.6+ (or use the included `./mvnw` wrapper) |
| Apache Fineract | **1.16.0-SNAPSHOT** (`develop` branch) |
| Database | PostgreSQL or MySQL (managed by Fineract) |

> **Note:** You do *not* need to install Fineract from source to use the plugin. You just need a running Fineract instance (JAR or Docker) to deploy the plugin into.

## Getting Started

### Build from Source

```bash
git clone https://github.com/openMF/selfservice-plugin.git
cd selfservice-plugin
./mvnw clean package -Dmaven.test.skip=true
```

This produces a JAR at `target/selfservice-plugin-1.15.0-SNAPSHOT.jar`.

### Deploy with Fineract (Docker)

```bash
# Create a directory for plugin JARs
mkdir -p /opt/fineract/plugins

# Copy the built plugin
cp target/selfservice-plugin-*.jar /opt/fineract/plugins/

# Start Fineract with the plugin on the classpath
java -Dloader.path=/opt/fineract/plugins/ -jar fineract-provider.jar
```

### Deploy with Fineract (Tomcat)

```bash
# Copy the JAR into Fineract's library directory
cp target/selfservice-plugin-*.jar $TOMCAT_HOME/webapps/fineract-provider/WEB-INF/lib/

# Restart Tomcat
$TOMCAT_HOME/bin/shutdown.sh && $TOMCAT_HOME/bin/startup.sh
```

The plugin auto-registers its endpoints, runs its own Liquibase migrations against every configured tenant, and is ready to use — **no additional configuration needed**.

### Pre-Built JARs

Pre-built snapshots are published to JFrog Artifactory:

👉 [Download latest JAR](https://mifos.jfrog.io/ui/native/libs-snapshot-local/community/mifos/selfservice-plugin/1.15.0-SNAPSHOT/)

## API Reference

All endpoints live under `/v1/self/`. Here's a summary of the available resources:

| Base Path | Resource | Methods |
|---|---|---|
| `/v1/self/registration` | Self-Registration & Enrollment | `POST` |
| `/v1/self/authentication` | Login (HTTP Basic) | `POST` |
| `/v1/self/password` | Forgot Password / Reset | `POST` |
| `/v1/self/user` | Current User Profile (password change; step-up protected when enabled) | `PUT` |
| `/v1/self/userdetails` | Current User Details | `GET` |
| `/v1/self/clients` | Client Profile, Images, Charges, Transactions | `GET`, `POST`, `PUT`, `DELETE` |
| `/v1/self/savingsaccounts` | Savings Accounts & Transactions | `GET`, `POST` |
| `/v1/self/loans` | Loan Accounts, Charges, Guarantors | `GET`, `POST`, `PUT` |
| `/v1/self/shareaccounts` | Share Accounts | `GET`, `POST` |
| `/v1/self/accounttransfers` | Fund Transfers (Own + TPT) | `GET`, `POST` |
| `/v1/self/stepup` | Step-up (OTP) authentication for sensitive operations | `GET`, `POST` |
| `/v1/self/activity` | Activity history of the logged-in user (logins, transfers, beneficiary / profile / password changes, step-up) | `GET` |
| `/v1/self/beneficiaries/tpt` | Third-Party Transfer Beneficiaries | `GET`, `POST`, `PUT`, `DELETE` |
| `/v1/self/loanproducts` | Loan Products Catalog | `GET` |
| `/v1/self/savingsproducts` | Savings Products Catalog | `GET` |
| `/v1/self/products/share` | Share Products Catalog | `GET` |
| `/v1/self/runreports` | Self-Service Reports | `GET` |
| `/v1/self/surveys` | Surveys (SPM) | `GET` |
| `/v1/self/surveys/scorecards` | Survey Scorecards | `GET`, `POST` |

Full OpenAPI/Swagger documentation is available at runtime via the Fineract Swagger UI when the plugin is loaded.

A [Postman collection](api-reference/postman/) and a [Bruno collection](api-reference/bruno/) are also included for hands-on API exploration.

## Step-up (OTP) authentication

Sensitive operations can require a fresh one-time code (SMS or e-mail) before they are executed, so a stolen session cannot move money or change the password (US-E12-02). It is **disabled by default**: with the defaults the API behaves exactly as before.

### Enable it for a tenant

Everything is configured **per tenant** in the external service `SELF_SERVICE_STEP_UP` (tables `c_external_service` / `c_external_service_properties`, seeded and disabled by Liquibase). Nothing is read from environment variables, so enabling it for one tenant never changes another tenant.

1. Give the tenant its own HMAC key (at least 32 characters, random). It is used to hash the OTPs. Treat it like the other external-service secrets (SMTP, SMS): only administrators should be able to read the external service configuration.

   ```sql
   UPDATE c_external_service_properties SET value = '<long random value>'
   WHERE name = 'hmacSecret'
     AND external_service_id = (SELECT id FROM c_external_service WHERE name = 'SELF_SERVICE_STEP_UP');
   ```

   Until a usable key is set (the seeded placeholder is too short on purpose), challenges are refused with `error.msg.selfservice.stepup.misconfigured`.

2. Turn it on for the tenant:

   ```sql
   UPDATE c_external_service_properties SET value = 'true'
   WHERE name = 'enabled'
     AND external_service_id = (SELECT id FROM c_external_service WHERE name = 'SELF_SERVICE_STEP_UP');
   ```

   Other keys of the `SELF_SERVICE_STEP_UP` service (values outside the range fall back to the default):

   | Key | Default | Meaning |
   |---|---|---|
   | `enabled` | `false` | master switch |
   | `hmacSecret` | placeholder | key used to hash this tenant's OTPs (at least 32 characters) |
   | `protectedActions` | `TPT_TRANSFER,CONFIRM_TRANSFER,CHANGE_PASSWORD` | also available: `OWN_TRANSFER` (transfers between own accounts), `APPROVE_SECOND_SIGNATURE` (required for [dual approval](#dual-approval-entity-clients)) |
   | `channels` | `SMS,EMAIL` | channels offered to the user (the authenticator app has its own switch, see below) |
   | `otpLength` | `6` (4-8) | digits of the code |
   | `otpTtlSeconds` | `300` (60-900) | validity of a code |
   | `maxAttempts` | `5` (1-10) | wrong codes before the challenge is locked |
   | `lockoutSeconds` | `900` | how long a locked user/action must wait |
   | `resendCooldownSeconds` / `maxResends` | `60` / `3` | resend limits |
   | `grantTtlSeconds` | `120` (30-600) | validity of the token returned after verification |
   | `challengeRateLimit` / `challengeRateWindowSeconds` | `5` / `900` | challenges per user per window |
   | `retentionDays` | `30` | challenges are kept this long (audit trail) |
   | `invalidateSessionsOnPasswordChange` | follows `enabled` | revoke all sessions after a password change |

3. Make sure e-mail (SMTP) and/or SMS delivery works for the tenant: codes are sent through the self-service notification module (template `STEP_UP_OTP`, es-MX by default, English with an English `Accept-Language`).

### Flow

The client must authenticate with the **session token** returned by login, not with the user name and password. The self-service API treats that token as a bearer-like credential and, for compatibility, currently carries it in the `Authorization` header wrapped as `Basic base64(<token>)` (the `base64EncodedAuthenticationKey` of the login response). Step-up only needs a stable identifier of the session, resolved through `StepUpSessionResolver`: it already also recognises `Authorization: Bearer <token>`, so the planned OIDC based security model only has to plug in its own resolver. `user:password` Basic auth has no session and is rejected (`stepup.session.required`).

| Step | Request |
|---|---|
| 0 | `GET /v1/self/stepup/config` - enabled? protected actions, channels this user can use |
| 1 | `POST /v1/self/stepup/challenges` `{ "action": "TPT_TRANSFER", "channel": "SMS", "payload": { ...the operation about to be confirmed... } }` -> `201` with `challengeId`, `maskedDestination`, `expiresAt`, `attemptsRemaining`, `resendAvailableAt` (never the code) |
| 2 | `POST /v1/self/stepup/challenges/{id}/verify` `{ "otp": "123456" }` -> `200 { "stepUpToken": "...", "expiresAt": "..." }` |
| 2b | `POST /v1/self/stepup/challenges/{id}/resend` (cooldown and maximum apply; attempts are not reset) |
| 3 | the protected request with the header `X-Step-Up-Token: <stepUpToken>` |

The token works **once**, only for the same user, the same session, the same action and the **same payload**: the server recomputes the digest from the real request, so an approved code cannot execute a different transfer.

| Action | Protected request | Payload bound into the token |
|---|---|---|
| `TPT_TRANSFER` | `POST /v1/self/accounttransfers?type=tpt` | `fromAccountId, fromAccountType, toAccountId, toAccountType, transferAmount` |
| `OWN_TRANSFER` | `POST /v1/self/accounttransfers` | same as above |
| `CONFIRM_TRANSFER` | `POST /v1/self/accounttransfers/confirm` | `fromAccount, fromAccountType, toAccount, toAccountType, toPhoneNumber, transferType, currencyCode, transferAmount` |
| `CHANGE_PASSWORD` | `PUT /v1/self/user` | the action only (the password is never hashed or stored); all sessions are revoked afterwards |
| `APPROVE_SECOND_SIGNATURE` | `POST /v1/self/accounttransfers/approvals/{approvalId}?command=approve` | `approvalId` |

When step-up protects `CONFIRM_TRANSFER`, the older in-line transfer OTP is replaced: `/quote` no longer sends a code, `/confirm` expects the step-up token instead of `otp`, and `/otp/resend` answers `stepup.legacy.otp.disabled`.

### Errors

Errors use the usual Fineract body (`userMessageGlobalisationCode`, `defaultUserMessage`, `errors[]`) plus machine readable fields.

| Code (`error.msg.selfservice.stepup.`) | HTTP | Extra fields |
|---|---|---|
| `required` | 403 | `action` |
| `invalid` (unknown, expired, used, other user/session/action/payload) | 403 | |
| `session.required` | 403 | |
| `otp.invalid` | 400 | `attemptsRemaining` |
| `otp.expired`, `disabled`, `action.not.protected`, `channel.unavailable`, `legacy.otp.disabled`, `resend.unsupported`, `totp.disabled`, `totp.not.enrolled`, `totp.enrollment.missing` | 400 | |
| `totp.already.enrolled` | 409 | |
| `locked`, `resend.cooldown`, `resend.limit`, `rate.limited` | 429 | `retryAfterSeconds` (+ `Retry-After` header) |
| `misconfigured` | 500 | |

Browser clients served from another origin must allow the `X-Step-Up-Token` request header in the Fineract CORS configuration (`fineract.security.cors.allowed-headers`).

Step-up events (`ISSUED, RESENT, VERIFIED, FAILED, LOCKED, CONSUMED, REJECTED`) are published as Spring `StepUpEvent`s and the challenge rows are kept for `retentionDays`, so an activity feed can consume them.

### Authenticator app (TOTP)

A client can pair an authenticator app (Google Authenticator, Microsoft Authenticator, Authy, 1Password...) and use the 6-digit code of the app instead of an SMS or e-mail code for the same protected actions. SMS can be intercepted (SIM swap) and costs money; the app does neither. It is **disabled by default** and **per tenant**.

#### Enable it for a tenant

All keys belong to the same external service `SELF_SERVICE_STEP_UP` (Liquibase `089` adds them with safe defaults). Nothing is read from environment variables.

1. Give the tenant its own key (at least 32 characters, random). It protects the secrets of **this** tenant: the key of the AES-256-GCM encryption of the TOTP secrets and of the hash of the recovery codes is derived from it. Treat it like the other external-service secrets (SMTP, SMS, `hmacSecret`): only administrators should be able to read the external service configuration. Without a usable key (the seeded placeholder is too short on purpose) nobody can pair an app and the API answers `error.msg.selfservice.stepup.misconfigured`.

   ```sql
   UPDATE c_external_service_properties SET value = '<long random value>'
   WHERE name = 'totpEncryptionKey'
     AND external_service_id = (SELECT id FROM c_external_service WHERE name = 'SELF_SERVICE_STEP_UP');
   ```

   If this key is changed, the secrets already stored cannot be decrypted any more: those users have to be reset (see below) and pair again.

2. Turn it on. Step-up as a whole (`enabled`) must be on too:

   ```sql
   UPDATE c_external_service_properties SET value = 'true'
   WHERE name = 'totpEnabled'
     AND external_service_id = (SELECT id FROM c_external_service WHERE name = 'SELF_SERVICE_STEP_UP');
   ```

   | Key | Default | Meaning |
   |---|---|---|
   | `totpEnabled` | `false` | switch of the authenticator. It does not depend on `channels` |
   | `totpEncryptionKey` | placeholder | at least 32 characters, see above |
   | `totpIssuer` | `Mifos` | name the app shows next to the account (at most 40 characters) |
   | `totpWindowSteps` | `1` (0-2) | 30-second steps accepted either side of the current one (clock drift) |
   | `totpMaxFailures` | `5` (1-10) | wrong codes before the authenticator locks |
   | `totpLockoutSeconds` | `900` (60-86400) | how long it stays locked |
   | `recoveryCodeCount` | `10` (5-20) | recovery codes handed out |
   | `totpExclusive` | `true` | while a user has an active authenticator, SMS and e-mail codes are **not** offered (SIM-swap protection) |

   Digits (6), period (30 s) and SHA-1 are fixed: Google Authenticator ignores any other value, so a different setting would give codes the app can never match.

#### Pairing

All calls need the session token, like the rest of step-up, and act on the logged-in user only (there is no user id).

| Step | Request |
|---|---|
| 1 | `POST /v1/self/stepup/totp/enrollment` -> `201 { "secret", "otpauthUri", "issuer", "digits", "periodSeconds" }`. The secret is shown **once**; draw `otpauthUri` as a QR code in the client (never send it to a third party) and offer `secret` as text. Refused with `409 totp.already.enrolled` while an authenticator is active. |
| 2 | a step-up challenge for `MANAGE_AUTHENTICATOR` over SMS or e-mail (see Flow) -> `stepUpToken` |
| 3 | `POST /v1/self/stepup/totp/enrollment/confirm` `{ "code": "123456" }` with `X-Step-Up-Token` -> `200 { "recoveryCodes": [ "ABCDE-FGHJK", ... ] }`. The code of the app proves it holds the secret. The recovery codes are shown **once**. |
| | `GET /v1/self/stepup/totp` -> `{ "available", "status": "NONE\|PENDING\|ACTIVE", "recoveryCodesRemaining", "exclusive", "confirmedAt" }` |
| | `POST /v1/self/stepup/totp/recovery-codes` (with a step-up token) -> a new set; the old codes stop working |
| | `DELETE /v1/self/stepup/totp` (with a step-up token, because it is active) removes the authenticator and its recovery codes. Cancelling a pairing that was never confirmed needs no token. |

`MANAGE_AUTHENTICATOR` is protected whenever the authenticator is on, **whatever `protectedActions` says**: a tenant that saved its own list before this feature existed must not get an unprotected way to pair a stranger's phone. When step-up is off for the tenant nothing is checked, as for every other action.

#### Using it

The app is the channel `TOTP` of the normal challenges:

| Step | Request |
|---|---|
| 1 | `POST /v1/self/stepup/challenges` `{ "action": "...", "channel": "TOTP", "payload": {...} }` -> `201`. Nothing is sent or stored; there is no `maskedDestination`, and a resend is refused (`resend.unsupported`). |
| 2 | `POST /v1/self/stepup/challenges/{id}/verify` `{ "otp": "123456" }`, or `{ "recoveryCode": "ABCDE-FGHJK" }` when the phone is lost -> `200 { "stepUpToken" }` |
| 3 | the protected request with `X-Step-Up-Token`, exactly as with an SMS code |

`GET /v1/self/stepup/config` lists `TOTP` among `availableChannels` for a user who paired an app, and, with `totpExclusive`, lists **only** `TOTP` for that user.

Rules enforced by the server:

- **Replay:** each 30-second step is accepted once. A code that was used (or an older step) is refused.
- **Lock-out per user:** wrong codes count against the authenticator, not against a challenge, so opening new challenges never resets the count. After `totpMaxFailures` it is locked (`429 locked`, `Retry-After`) for `totpLockoutSeconds`, and neither the right code nor a recovery code is tried meanwhile.
- **Recovery codes** work once, only on a TOTP challenge, and are stored only as keyed hashes. They count as wrong attempts when they are wrong, so they cannot be guessed either.
- **Secrets:** the TOTP secret is stored AES-256-GCM encrypted (random nonce, the user id is bound in, so a row copied to another user does not decrypt). It is never logged, never put in an event, never returned again. Anybody who can read the database **and** the external service properties can decrypt it: that is the same trust level as the SMTP password in the same service.
- **Time:** codes use Unix time, never the tenant's local date.

#### Lost phone and lost recovery codes

A member of staff removes the pairing; the user falls back to SMS or e-mail codes and can pair a new app:

```
DELETE /v1/selfservice/users/{userId}/totp      (permission UPDATE_SELFSERVICEUSER, same office-hierarchy rule as the other user administration calls)
```

It answers `{ "removed": true|false }` and works even when the tenant has switched the authenticator off. The user sees `AUTHENTICATOR_RESET` in the activity history; the address of the staff member is **not** stored there.

#### Activity history

The history gets `AUTHENTICATOR_ENROLLED`, `AUTHENTICATOR_REMOVED`, `AUTHENTICATOR_RESET`, `AUTHENTICATOR_LOCKED`, `RECOVERY_CODES_REGENERATED` and `RECOVERY_CODE_USED`, so a user can spot an account takeover. Spring `TotpEvent`s carry the same facts, never a code, a secret or a hash.

Not in this version: security e-mails to the user when the authenticator is paired or removed, and a "required" mode that forces every client to pair one.

## Activity history

A client can see the logins and sensitive actions of their own account and spot unauthorised use (US-E12-05).

```
GET /v1/self/activity?limit=20&offset=0&type=LOGIN,TRANSFER_CONFIRMED
```

- Newest first, only the **logged-in user's own** events. There is no user id parameter.
- `limit` defaults to 20 and is capped by the tenant setting `maxPageSize`. `type` is an optional list. The answer is `{ "totalFilteredRecords": n, "pageItems": [ { "id", "type", "outcome", "occurredAt", "ipAddress", "details" } ] }`.
- `type`: `LOGIN`, `LOGIN_FAILED`, `LOGIN_NEW_DEVICE`, `LOGOUT`, `PASSWORD_CHANGED`, `PASSWORD_RESET`, `TRANSFER_CONFIRMED`, `BENEFICIARY_ADDED`, `BENEFICIARY_UPDATED`, `BENEFICIARY_DELETED`, `PROFILE_IMAGE_CHANGED`, `PROFILE_IMAGE_REMOVED`, `SINPE_SUBSCRIPTION_CHANGED`, `STEP_UP_REQUESTED`, `STEP_UP_VERIFIED`, `STEP_UP_LOCKED`, `AUTHENTICATOR_ENROLLED`, `AUTHENTICATOR_REMOVED`, `AUTHENTICATOR_RESET`, `AUTHENTICATOR_LOCKED`, `RECOVERY_CODES_REGENERATED`, `RECOVERY_CODE_USED`. The client translates the code; no text is stored.
- **Where the address comes from:** the address of the connection (`getRemoteAddr()`). The plugin never reads `X-Forwarded-For` itself, because any client can write anything in that header and the history exists to show where an action really came from. Behind a reverse proxy, configure the trusted proxies of the server (for example `server.forward-headers-strategy=native` and `server.tomcat.remoteip.internal-proxies=<your proxies>`); then `getRemoteAddr()` is the real client. Without a trusted proxy the address of the proxy is shown. The same rule applies to the address kept with step-up challenges.
- **Privacy:** the IP address is stored and returned **masked** (`203.0.x.x`, IPv6 `2001:db8:x:x:x:x:x:x`); the full address is never kept. `details` is built from a whitelist per type (for a transfer: amount, currency when known, destination name, last 4 digits of the destination account). A password, OTP, token, e-mail, phone number or full account number never reaches the history.
- A wrong-password login is recorded at most **once per minute per user**, so a guessing attack cannot flood the feed. Wrong step-up codes are not listed one by one; the request, the successful verification and a lock-out are.
- Recording never breaks the action it describes: a failure to write is only logged. A transfer is listed only after it was committed.

### Configure it per tenant

The external service `SELF_SERVICE_ACTIVITY` (tables `c_external_service` / `c_external_service_properties`, seeded by Liquibase). Nothing is read from environment variables.

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | `false` stops recording (existing rows stay until purged) |
| `retentionDays` | `90` | how long the history is kept, `1`..`3650` (some regions need longer) |
| `maxPageSize` | `100` | largest page a client may ask for, `10`..`500` |

```sql
UPDATE c_external_service_properties SET value = '365'
WHERE name = 'retentionDays'
  AND external_service_id = (SELECT id FROM c_external_service WHERE name = 'SELF_SERVICE_ACTIVITY');
```

A nightly job (batch instances only, `mifos.self.service.activity.purge.enabled` / `.cron`) deletes older rows of every tenant using that tenant's own `retentionDays`.

## Dual approval (ENTITY clients)

A business (ENTITY) client can require a second signatory for large transfers (MX-489). It is off unless a threshold row exists for the client, so every other client keeps the single-user flow.

- **Threshold:** set per client and currency in `m_selfservice_client_approval_threshold`. A transfer is held when its amount, **excluding the fee**, is **greater than or equal to** the threshold for the source account's currency. The currency is read from the account, not from the request. There is no tenant-wide threshold.
- **Signatories:** self-service users who may approve for a client are listed in `m_selfservice_entity_signatory` (`is_active = false` revokes them). The one-owner-per-client mapping (`unique_self_client`) is unchanged.
- **Approver OTP:** the tenant must have step-up enabled with `APPROVE_SECOND_SIGNATURE` in `protectedActions`. Otherwise a transfer that needs approval is refused (`validation.msg.transferapproval.approvalId.otp.not.configured`) instead of being held where nobody could approve it.

```sql
INSERT INTO m_selfservice_client_approval_threshold (client_id, currency_code, threshold_amount)
VALUES (<entityClientId>, 'CRC', 1000000);
INSERT INTO m_selfservice_entity_signatory (client_id, appuser_id, is_active, created_at)
VALUES (<entityClientId>, <selfServiceUserId>, true, CURRENT_TIMESTAMP);
```

| Step | Who | Request |
|---|---|---|
| 1 | initiator | the usual prepare, quote and confirm. At or above the threshold, `/confirm` answers `{ "status": "PENDING_APPROVAL", "approvalId": 5 }` and no money moves |
| 2 | approver | `GET /v1/self/accounttransfers/approvals` lists pending transfers of the clients where they are an active signatory (`canDecide` is false for their own) |
| 3 | approver | step-up challenge for `APPROVE_SECOND_SIGNATURE` with `payload: { "approvalId": 5 }`, then verify |
| 4 | approver | `POST /v1/self/accounttransfers/approvals/5?command=approve` with `X-Step-Up-Token`, or `?command=reject` (no OTP) |

On approval the funds are checked again and the initiator's request is executed through the normal confirm path, as the initiator. The approval is committed before any money moves, so a transfer is sent **at most once**: if execution fails (for example after a PIN or SINPE gateway has already accepted it), the approval ends as `FAILED` and can never be approved again. Reconcile it against the gateway before the initiator submits a new transfer. The initiator cannot decide on their own transfer, a non-signatory gets the same `not.found` error as a missing approval, and a transfer can be decided only once, even under concurrent requests. The legacy `POST /v1/self/accounttransfers` refuses transfers that need a second signature (`dual.approval.required`).

## Contributing

Interested in contributing? We'd love your help. See the **[Contributing Guide](CONTRIBUTING.md)** for everything you need — development setup, running tests, code style, and project structure.

We recommend that you **Watch** and **Star** this project on GitHub to stay up to date.

## History

This plugin was extracted from the [Apache Fineract](https://github.com/apache/fineract) codebase. The self-service functionality was originally part of Fineract core and was [removed in this commit](https://github.com/apache/fineract/commit/5364ddbe121ae6d7f95e06cce20450e0fb479f42) to keep Fineract modular. This repository continues that work as a standalone, community-maintained plugin under the [Mifos Initiative](https://mifos.org/).

## Important Notices

- Mifos® is not affiliated with, endorsed by, or otherwise associated with the Apache Software Foundation® (ASF) or any of its projects.
- Apache Software Foundation® is a vendor-neutral organization; its projects are governed independently.
- Apache Fineract®, Fineract, Apache, the Apache® feather, and the Apache Fineract® project logo are either registered trademarks or trademarks of the Apache Software Foundation®.

## License

This project is licensed under the [Mozilla Public License 2.0](LICENSE).
